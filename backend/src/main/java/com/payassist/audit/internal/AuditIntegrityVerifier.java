package com.payassist.audit.internal;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

/**
 * Recomputes the audit hash chain (ADR 0013) and reports the first break.
 *
 * <ul>
 *   <li>The genesis anchor (chain_seq 1) must exist and pin the legacy rows: count, highest ID and digest.
 *   <li>Legacy rows (chain_seq NULL) must all be older than the genesis anchor.
 *   <li>Chained rows must be numbered 1..n without gaps, each linking to the previous row's hash, and each
 *       {@code row_hash} must equal SHA-256 of the canonical row recomputed here in Java.
 * </ul>
 *
 * <p>Deleting the newest rows cannot be detected from the chain alone; the report returns the head
 * ({@code headChainSeq}, {@code headRowHash}) so it can be compared with an earlier, externally kept checkpoint.
 */
@Component
class AuditIntegrityVerifier {

    static final String GENESIS_ACTION = "AUDIT_CHAIN_GENESIS";
    private static final int FETCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    AuditIntegrityVerifier(DataSource dataSource, ObjectMapper json, Clock clock) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setFetchSize(FETCH_SIZE); // own template: streams with a cursor inside the transaction
        this.json = json;
        this.clock = clock;
    }

    /** Must run inside a transaction so the rows can be streamed with a cursor. */
    AuditIntegrityReport verify() {
        Instant checkedAt = Instant.now(clock);
        List<AuditCanonicalForm.Row> genesisRows = jdbc.query(
                "select * from audit_events where chain_seq = 1", (rs, n) -> row(rs));
        if (genesisRows.isEmpty()) {
            return AuditIntegrityReport.broken(checkedAt, 0, 0, null, null,
                    new AuditIntegrityReport.Failure(null, null, "GENESIS_MISSING"));
        }
        AuditCanonicalForm.Row genesis = genesisRows.get(0);
        var walk = new Walk(genesis);

        jdbc.query("select * from audit_events order by chain_seq asc nulls first, id asc",
                (RowCallbackHandler) rs -> walk.accept(row(rs)));
        walk.finish();

        if (walk.failure == null) {
            return AuditIntegrityReport.valid(checkedAt, walk.legacyRows, walk.chainedRows, walk.headSeq, walk.headHash);
        }
        return AuditIntegrityReport.broken(checkedAt, walk.legacyRows, walk.chainedRows, walk.headSeq, walk.headHash,
                walk.failure);
    }

    /** Streaming state of one verification pass. Records the first failure and keeps going to count rows. */
    private static final class Walk {
        private final AuditCanonicalForm.Row genesis;
        private final MessageDigest legacyDigest = AuditCanonicalForm.sha256();
        private long legacyRows;
        private long legacyMaxId;
        private long chainedRows;
        private long expectedSeq = 1;
        private String expectedPrevHash = AuditCanonicalForm.ZERO_HASH;
        private Long headSeq;
        private String headHash;
        private AuditIntegrityReport.Failure failure;

        Walk(AuditCanonicalForm.Row genesis) {
            this.genesis = genesis;
        }

        void accept(AuditCanonicalForm.Row r) {
            if (r.chainSeq() == null) {
                if (r.id() > genesis.id()) {
                    fail(r, "UNCHAINED_ROW_AFTER_GENESIS");
                }
                legacyRows++;
                legacyMaxId = Math.max(legacyMaxId, r.id());
                legacyDigest.update(AuditCanonicalForm.legacy(r).getBytes(UTF_8));
                return;
            }
            chainedRows++;
            if (r.chainSeq() != expectedSeq) {
                fail(r, "CHAIN_SEQUENCE_GAP");
            } else if (!Objects.equals(r.prevHash(), expectedPrevHash)) {
                fail(r, "PREVIOUS_HASH_MISMATCH");
            } else if (!AuditCanonicalForm.sha256Hex(AuditCanonicalForm.chained(r)).equals(r.rowHash())) {
                fail(r, "ROW_HASH_MISMATCH");
            }
            // Resynchronize so one break is reported once, not for every later row.
            expectedSeq = r.chainSeq() + 1;
            expectedPrevHash = r.rowHash();
            headSeq = r.chainSeq();
            headHash = r.rowHash();
        }

        void finish() {
            Map<String, String> pinned = genesis.details() == null ? Map.of() : genesis.details();
            boolean anchorValid = GENESIS_ACTION.equals(genesis.action()) && "1".equals(pinned.get("canonicalVersion"));
            if (!anchorValid) {
                fail(genesis, "GENESIS_INVALID");
                return;
            }
            boolean legacyIntact = Long.toString(legacyRows).equals(pinned.get("legacyRows"))
                    && Long.toString(legacyMaxId).equals(pinned.get("legacyMaxId"))
                    && HexFormat.of().formatHex(legacyDigest.digest()).equals(pinned.get("legacyDigest"));
            if (!legacyIntact) {
                fail(genesis, "LEGACY_ROWS_CHANGED");
            }
        }

        private void fail(AuditCanonicalForm.Row r, String reason) {
            if (failure == null) {
                failure = new AuditIntegrityReport.Failure(r.id(), r.chainSeq(), reason);
            }
        }
    }

    private AuditCanonicalForm.Row row(ResultSet rs) throws SQLException {
        long seq = rs.getLong("chain_seq");
        Long chainSeq = rs.wasNull() ? null : seq;
        OffsetDateTime occurredAt = rs.getObject("occurred_at", OffsetDateTime.class);
        return new AuditCanonicalForm.Row(rs.getLong("id"), chainSeq, rs.getString("prev_hash"),
                rs.getString("row_hash"), occurredAt == null ? null : occurredAt.toInstant(), rs.getString("actor"),
                rs.getString("actor_subject"), rs.getString("actor_type"), rs.getString("on_behalf_of_subject"),
                rs.getString("action"), rs.getString("resource_type"), rs.getString("resource_id"),
                rs.getString("correlation_id"), rs.getString("outcome"), details(rs.getString("details")));
    }

    /** JSONB object to the text values PostgreSQL's jsonb_each_text yields (JSON null stays null). */
    private Map<String, String> details(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            Map<String, Object> parsed = json.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            Map<String, String> out = new LinkedHashMap<>();
            for (var e : parsed.entrySet()) {
                Object v = e.getValue();
                out.put(e.getKey(), v == null ? null : v instanceof String s ? s : json.writeValueAsString(v));
            }
            return out;
        } catch (Exception e) {
            // Unparseable details cannot match any hash; surface as a mismatch rather than an error.
            return Map.of("__unparseable__", raw);
        }
    }
}

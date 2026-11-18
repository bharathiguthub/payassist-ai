package com.payassist.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payassist.support.PostgresTestcontainersConfig;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The tamper-evident audit chain (V7, ADR 0013) against real PostgreSQL, without Spring. Each test gets a fresh
 * database that is upgraded from V6 with three legacy audit rows, so tests can tamper freely (as the container
 * superuser, i.e. with privileges the application's runtime role must never have).
 */
@Testcontainers
class AuditChainIT {

    @Container
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(PostgresTestcontainersConfig.IMAGE);

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private List<Map<String, Object>> legacyBefore;

    @BeforeEach
    void upgradeDatabaseWithLegacyRows() {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("6").load().migrate();

        // Legacy shapes: a Phase 1 row (no subject/outcome/details), a refund-era row with details, a denial.
        jdbc.update("insert into audit_events (occurred_at, actor, action, resource_type, resource_id) "
                + "values ('2026-11-01T09:00:00.123456Z', 'agent.alice', 'VIEW', 'PAYMENT', 'PAY-000042')");
        jdbc.update("insert into audit_events (occurred_at, actor, actor_subject, action, resource_type, resource_id, "
                + "correlation_id, outcome, details) values ('2026-11-02T10:00:00Z', 'sam.supervisor', 'sub-sam', "
                + "'REFUND_REQUEST_REJECT', 'REFUND_REQUEST', 'RFD-000001', 'cid-legacy-0001', 'SUCCESS', "
                + "'{\"status\": \"REJECTED\", \"amount\": \"10.00\", \"rejectionReason\": \"DUPLICATE\"}'::jsonb)");
        jdbc.update("insert into audit_events (occurred_at, actor, actor_subject, action, resource_type, resource_id, "
                + "outcome) values ('2026-11-03T11:00:00Z', 'ada.admin', 'sub-ada', 'ACCESS_DENIED', 'ENDPOINT', "
                + "'POST /api/v1/refunds/RFD-000001/approve', 'DENIED')");
        legacyBefore = legacyRows("true");

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @Test
    void upgradeLeavesLegacyRowsUnchangedAndAnchorsThemInTheGenesisRow() {
        assertThat(legacyRows("chain_seq is null")).isEqualTo(legacyBefore);
        assertThat(jdbc.queryForObject("select count(*) from audit_events where chain_seq is null", Long.class))
                .isEqualTo(3);

        Map<String, Object> genesis = jdbc.queryForMap("select * from audit_events where chain_seq = 1");
        assertThat(genesis).containsEntry("action", "AUDIT_CHAIN_GENESIS").containsEntry("actor", "system:flyway")
                .containsEntry("actor_type", "SYSTEM").containsEntry("prev_hash", "0".repeat(64));
        assertThat(jdbc.queryForObject("select details->>'legacyRows' from audit_events where chain_seq = 1",
                String.class)).isEqualTo("3");
        assertThat((Long) genesis.get("id")).isGreaterThan(
                jdbc.queryForObject("select max(id) from audit_events where chain_seq is null", Long.class));

        AuditIntegrityReport report = verify();
        assertThat(report.result()).isEqualTo("VALID");
        assertThat(report.legacyRows()).isEqualTo(3);
        assertThat(report.chainedRows()).isEqualTo(1);
    }

    @Test
    void newRowsAreChainedAndJavaAgreesWithSqlOnTrickyValues() {
        insert("agent.alice", "sub-alice", "VIEW_TIMELINE", "PAY-000001", null, "2026-11-16T12:00:00.000001Z");
        // Separators, multi-byte characters and key ordering must encode identically in SQL and Java.
        insert("agent.zoë", "sub;:-", "DISPUTE_CASE_TRANSITION", "DSP-0001;x:1",
                "{\"status\": \"OPEN\", \"Zkey\": \"é;:\", \"code\": \"\", \"a\": null}", "2026-11-16T12:00:01Z");
        insert("system:processor", null, "VIEW", "PAY-000002", "{}", "2026-11-16T12:00:02Z");

        assertThat(jdbc.queryForList("select chain_seq from audit_events where chain_seq is not null order by id",
                Long.class)).containsExactly(1L, 2L, 3L, 4L);
        assertThat(jdbc.queryForObject("select actor_type from audit_events where chain_seq = 4", String.class))
                .isEqualTo("SYSTEM");
        // Every row links to its predecessor.
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_events a join audit_events b on b.chain_seq = a.chain_seq + 1
                where b.prev_hash = a.row_hash""", Long.class)).isEqualTo(3);

        AuditIntegrityReport report = verify();
        assertThat(report.result()).as("first failure: %s", report.firstFailure()).isEqualTo("VALID");
        assertThat(report.headChainSeq()).isEqualTo(4);
        assertThat(report.headRowHash()).isEqualTo(
                jdbc.queryForObject("select row_hash from audit_events where chain_seq = 4", String.class));
    }

    @Test
    void writersCannotChooseTheirChainPositionOrHashes() {
        jdbc.update("insert into audit_events (occurred_at, actor, actor_subject, action, resource_type, resource_id, "
                + "chain_seq, prev_hash, row_hash) values (now(), 'agent.alice', 'sub-alice', 'VIEW', 'PAYMENT', "
                + "'PAY-000003', 999, repeat('f', 64), repeat('f', 64))");
        assertThat(jdbc.queryForMap("select chain_seq, prev_hash <> repeat('f', 64) as linked, "
                + "row_hash <> repeat('f', 64) as hashed from audit_events where resource_id = 'PAY-000003'"))
                .containsEntry("chain_seq", 2L).containsEntry("linked", true).containsEntry("hashed", true);
        assertThat(verify().result()).isEqualTo("VALID");
    }

    @Test
    void writesUnderSnapshotIsolationAreRefused() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            assertThatThrownBy(() -> c.createStatement().executeUpdate(
                    "insert into audit_events (occurred_at, actor, action, resource_type, resource_id) "
                            + "values (now(), 'agent.alice', 'VIEW', 'PAYMENT', 'PAY-000004')"))
                    .hasMessageContaining("READ COMMITTED");
            c.rollback();
        }
    }

    @Test
    void concurrentWritersProduceOneUnbrokenChain() throws Exception {
        int threads = 16;
        int perThread = 5;
        var pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> work = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                work.add(() -> {
                    for (int i = 0; i < perThread; i++) {
                        insert("agent.alice", "sub-alice", "VIEW", "PAY-T" + thread + "-" + i, null,
                                Instant.now().toString());
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(work)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        long chained = 1 + (long) threads * perThread;
        assertThat(jdbc.queryForObject("select count(distinct chain_seq) from audit_events where chain_seq is not null",
                Long.class)).isEqualTo(chained);
        assertThat(jdbc.queryForObject("select max(chain_seq) from audit_events", Long.class)).isEqualTo(chained);
        AuditIntegrityReport report = verify();
        assertThat(report.result()).as("first failure: %s", report.firstFailure()).isEqualTo("VALID");
        assertThat(report.chainedRows()).isEqualTo(chained);
    }

    @Test
    void modifiedChainedRowIsDetected() {
        insert("agent.alice", "sub-alice", "VIEW", "PAY-000005", null, "2026-11-16T12:00:00Z");
        long id = insert("agent.alice", "sub-alice", "VIEW", "PAY-000006", null, "2026-11-16T12:00:01Z");
        insert("agent.alice", "sub-alice", "VIEW", "PAY-000007", null, "2026-11-16T12:00:02Z");
        asSuperuser("trg_audit_events_no_update_delete",
                () -> jdbc.update("update audit_events set resource_id = 'PAY-999999' where id = ?", id));

        assertBroken(id, "ROW_HASH_MISMATCH");
    }

    @Test
    void deletedChainedRowIsDetected() {
        insert("agent.alice", "sub-alice", "VIEW", "PAY-000005", null, "2026-11-16T12:00:00Z");
        long deleted = insert("agent.alice", "sub-alice", "VIEW", "PAY-000006", null, "2026-11-16T12:00:01Z");
        long next = insert("agent.alice", "sub-alice", "VIEW", "PAY-000007", null, "2026-11-16T12:00:02Z");
        asSuperuser("trg_audit_events_no_update_delete",
                () -> jdbc.update("delete from audit_events where id = ?", deleted));

        assertBroken(next, "CHAIN_SEQUENCE_GAP");
    }

    @Test
    void changedOrDeletedLegacyRowIsDetected() {
        long legacyId = jdbc.queryForObject("select min(id) from audit_events", Long.class);
        asSuperuser("trg_audit_events_no_update_delete",
                () -> jdbc.update("update audit_events set actor = 'someone.else' where id = ?", legacyId));
        AuditIntegrityReport report = verify();
        assertThat(report.result()).isEqualTo("BROKEN");
        assertThat(report.firstFailure().reason()).isEqualTo("LEGACY_ROWS_CHANGED");

        asSuperuser("trg_audit_events_no_update_delete",
                () -> jdbc.update("delete from audit_events where id = ?", legacyId));
        assertThat(verify().firstFailure().reason()).isEqualTo("LEGACY_ROWS_CHANGED");
    }

    @Test
    void rowSlippedInWithTheChainTriggerDisabledIsDetected() {
        asSuperuser("trg_audit_events_chain", () -> jdbc.update(
                "insert into audit_events (occurred_at, actor, action, resource_type, resource_id) "
                        + "values (now(), 'agent.alice', 'VIEW', 'PAYMENT', 'PAY-000008')"));
        long id = jdbc.queryForObject("select id from audit_events where resource_id = 'PAY-000008'", Long.class);

        assertBroken(id, "UNCHAINED_ROW_AFTER_GENESIS");
    }

    @Test
    void missingGenesisIsDetected() {
        asSuperuser("trg_audit_events_no_update_delete",
                () -> jdbc.update("delete from audit_events where chain_seq = 1"));
        assertThat(verify().firstFailure().reason()).isEqualTo("GENESIS_MISSING");
    }

    private void assertBroken(long id, String reason) {
        AuditIntegrityReport report = verify();
        assertThat(report.result()).isEqualTo("BROKEN");
        assertThat(report.firstFailure().auditEventId()).isEqualTo(id);
        assertThat(report.firstFailure().reason()).isEqualTo(reason);
    }

    private AuditIntegrityReport verify() {
        var verifier = new AuditIntegrityVerifier(dataSource, new ObjectMapper(), Clock.systemUTC());
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(s -> verifier.verify());
    }

    private long insert(String actor, String subject, String action, String resourceId, String detailsJson,
            String occurredAt) {
        return jdbc.queryForObject("insert into audit_events (occurred_at, actor, actor_subject, action, resource_type, "
                + "resource_id, outcome, details) values (?, ?, ?, ?, 'PAYMENT', ?, 'SUCCESS', ?::jsonb) returning id",
                Long.class, Timestamp.from(Instant.parse(occurredAt)), actor, subject, action, resourceId, detailsJson);
    }

    /** Tampering needs table-owner privileges: disable a protective trigger, act, re-enable it. */
    private void asSuperuser(String trigger, Runnable tampering) {
        jdbc.execute("alter table audit_events disable trigger " + trigger);
        try {
            tampering.run();
        } finally {
            jdbc.execute("alter table audit_events enable trigger " + trigger);
        }
    }

    private List<Map<String, Object>> legacyRows(String condition) {
        return jdbc.queryForList("select id, occurred_at, actor, actor_subject, action, resource_type, resource_id, "
                + "correlation_id, outcome, details::text from audit_events where " + condition + " order by id");
    }
}

package com.payassist.audit.internal;

import com.payassist.identity.CurrentActor;
import com.payassist.identity.Roles;
import com.payassist.shared.AuditRecord;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the admin audit-integrity status (ADR 0013 section 12) from data the backend may read: the latest in-app
 * integrity check (an {@code AUDIT_INTEGRITY_CHECK} audit row) and the runs the external checkpoint job reported in
 * {@code audit_checkpoint_runs}. It never reads the ledger or the key, and never runs the O(n) verification itself.
 * ADMIN only (controller and here); every view is audited ({@code AUDIT_CHECKPOINT_STATUS_VIEW}, fail closed).
 */
@Service
public class AuditCheckpointStatusService {

    static final String ACTION = "AUDIT_CHECKPOINT_STATUS_VIEW";

    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final Duration staleAfter;

    AuditCheckpointStatusService(JdbcTemplate jdbc, ApplicationEventPublisher publisher, Clock clock,
            @Value("${payassist.audit.checkpoint-stale-after:PT24H}") Duration staleAfter) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    @PreAuthorize(Roles.ADMIN_ONLY)
    @Transactional
    public AuditCheckpointStatus status() {
        var actor = CurrentActor.require();
        Instant now = Instant.now(clock);

        Long head = jdbc.queryForObject("select max(chain_seq) from audit_events", Long.class);
        var lastCheck = jdbc.query("""
                select occurred_at, details->>'result' as result from audit_events
                where action = 'AUDIT_INTEGRITY_CHECK' order by id desc limit 1""",
                (rs, n) -> Map.entry(rs.getTimestamp("occurred_at").toInstant(), String.valueOf(rs.getString("result"))))
                .stream().findFirst().orElse(null);
        var database = new AuditCheckpointStatus.DatabaseChain(head, lastCheck == null ? null : lastCheck.getKey(),
                lastCheck == null ? null : lastCheck.getValue());

        var lastRun = jdbc.query("""
                select ran_at, mode, result, reason, checkpoints, latest_checkpoint_head, latest_checkpoint_at
                from audit_checkpoint_runs order by id desc limit 1""", (rs, n) -> new Object[] {
                        instant(rs.getTimestamp("ran_at")), rs.getString("mode"), rs.getString("result"),
                        rs.getString("reason"), rs.getInt("checkpoints"), (Long) rs.getObject("latest_checkpoint_head"),
                        instant(rs.getTimestamp("latest_checkpoint_at"))})
                .stream().findFirst().orElse(null);
        Instant lastOk = instant(jdbc.queryForObject(
                "select max(ran_at) from audit_checkpoint_runs where result = 'OK'", Timestamp.class));
        AuditCheckpointStatus.ExternalCheckpoints external;
        if (lastRun == null) {
            external = new AuditCheckpointStatus.ExternalCheckpoints(null, null, null, null, lastOk, null, null, null,
                    null);
        } else {
            Long latestHead = (Long) lastRun[5];
            external = new AuditCheckpointStatus.ExternalCheckpoints((Instant) lastRun[0], (String) lastRun[1],
                    (String) lastRun[2], (String) lastRun[3], lastOk, (Integer) lastRun[4], latestHead,
                    (Instant) lastRun[6], latestHead == null || head == null ? null : Math.max(0, head - latestHead));
        }

        List<AuditCheckpointStatus.Warning> warnings = warnings(database, external, now);
        publisher.publishEvent(AuditRecord.of(clock, actor, ACTION, AuditQueryService.RESOURCE_TYPE,
                AuditQueryService.RESOURCE_ID).withDetails(Map.of("warnings", warnings.isEmpty() ? "NONE"
                        : warnings.stream().map(AuditCheckpointStatus.Warning::code).collect(Collectors.joining(",")))));
        return new AuditCheckpointStatus(now, staleAfter.toHours(), database, external, warnings);
    }

    List<AuditCheckpointStatus.Warning> warnings(AuditCheckpointStatus.DatabaseChain db,
            AuditCheckpointStatus.ExternalCheckpoints ext, Instant now) {
        List<AuditCheckpointStatus.Warning> w = new ArrayList<>();
        if ("BROKEN".equals(db.lastResult())) {
            w.add(danger("DB_CHAIN_BROKEN", "The last in-app verification found the audit hash chain BROKEN. "
                    + "Treat this as a security incident; do not modify the audit table."));
        }
        if (db.lastVerifiedAt() == null || isOld(db.lastVerifiedAt(), now)) {
            w.add(warning("DB_CHAIN_NOT_VERIFIED", "The audit hash chain has not been verified in the app recently. "
                    + "Use Verify integrity."));
        }
        if ("FAILED".equals(ext.lastRunResult())) {
            w.add(danger("LAST_RUN_FAILED", "The last external checkpoint run failed (" + ext.lastRunReason()
                    + "). Follow docs/runbooks/audit-checkpoints.md."));
        }
        if (ext.latestCheckpointHead() == null) {
            w.add(warning("NO_CHECKPOINT", "No external checkpoint has been reported. "
                    + "Run .\\scripts\\audit-checkpoint.ps1 -Create on the host."));
        } else if (ext.latestCheckpointAt() == null || isOld(ext.latestCheckpointAt(), now)) {
            w.add(warning("CHECKPOINT_STALE", "The newest external checkpoint is older than the "
                    + staleAfter.toHours() + "-hour limit. Run .\\scripts\\audit-checkpoint.ps1 -Create."));
        }
        if (ext.lastSuccessfulRunAt() == null || isOld(ext.lastSuccessfulRunAt(), now)) {
            w.add(warning("NOT_VERIFIED_RECENTLY", "The external checkpoints have not been verified successfully "
                    + "recently. Run .\\scripts\\audit-checkpoint.ps1."));
        }
        w.sort(Comparator.comparing(x -> "DANGER".equals(x.severity()) ? 0 : 1));
        return List.copyOf(w);
    }

    private boolean isOld(Instant at, Instant now) {
        return at.plus(staleAfter).isBefore(now);
    }

    private static AuditCheckpointStatus.Warning danger(String code, String message) {
        return new AuditCheckpointStatus.Warning(code, "DANGER", message);
    }

    private static AuditCheckpointStatus.Warning warning(String code, String message) {
        return new AuditCheckpointStatus.Warning(code, "WARNING", message);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}

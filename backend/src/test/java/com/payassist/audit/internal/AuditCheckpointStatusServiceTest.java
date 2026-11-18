package com.payassist.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Warning rules of the admin audit-integrity status, without a database. */
class AuditCheckpointStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private final AuditCheckpointStatusService service = new AuditCheckpointStatusService(null, null,
            Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(24));

    private static AuditCheckpointStatus.ExternalCheckpoints external(String result, String reason, Long head,
            Instant checkpointAt, Instant lastOk) {
        return new AuditCheckpointStatus.ExternalCheckpoints(NOW.minusSeconds(60), "VERIFY", result, reason, lastOk, 1,
                head, checkpointAt, head == null ? null : 3L);
    }

    private List<String> codes(AuditCheckpointStatus.DatabaseChain db, AuditCheckpointStatus.ExternalCheckpoints ext) {
        return service.warnings(db, ext, NOW).stream().map(AuditCheckpointStatus.Warning::code).toList();
    }

    @Test
    void nothingEverVerifiedRaisesEveryWarning() {
        var db = new AuditCheckpointStatus.DatabaseChain(10L, null, null);
        var ext = new AuditCheckpointStatus.ExternalCheckpoints(null, null, null, null, null, null, null, null, null);
        assertThat(codes(db, ext)).containsExactly("DB_CHAIN_NOT_VERIFIED", "NO_CHECKPOINT", "NOT_VERIFIED_RECENTLY");
    }

    @Test
    void healthyStateHasNoWarnings() {
        var db = new AuditCheckpointStatus.DatabaseChain(10L, NOW.minusSeconds(3600), "VALID");
        assertThat(codes(db, external("OK", null, 9L, NOW.minusSeconds(7200), NOW.minusSeconds(60)))).isEmpty();
    }

    @Test
    void brokenOrFailedStatesComeFirstAsDanger() {
        var db = new AuditCheckpointStatus.DatabaseChain(10L, NOW.minusSeconds(3600), "BROKEN");
        var warnings = service.warnings(db, external("FAILED", "CHECKPOINT_MISMATCH", 9L, NOW.minusSeconds(7200),
                NOW.minus(Duration.ofDays(3))), NOW);
        assertThat(warnings).extracting(AuditCheckpointStatus.Warning::code)
                .containsExactly("DB_CHAIN_BROKEN", "LAST_RUN_FAILED", "NOT_VERIFIED_RECENTLY");
        assertThat(warnings.get(0).severity()).isEqualTo("DANGER");
        assertThat(warnings.get(1).message()).contains("CHECKPOINT_MISMATCH");
    }

    @Test
    void oldCheckpointsAreStale() {
        var db = new AuditCheckpointStatus.DatabaseChain(10L, NOW.minusSeconds(3600), "VALID");
        assertThat(codes(db, external("OK", null, 9L, NOW.minus(Duration.ofHours(25)), NOW.minusSeconds(60))))
                .containsExactly("CHECKPOINT_STALE");
    }
}

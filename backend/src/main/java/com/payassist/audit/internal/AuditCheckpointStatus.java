package com.payassist.audit.internal;

import java.time.Instant;
import java.util.List;

/**
 * What the admin UI shows about audit integrity (ADR 0013 section 12). Two different guarantees, kept apart:
 *
 * <ul>
 *   <li>{@link DatabaseChain}: the hash chain <b>inside PostgreSQL</b>, as last verified by an administrator in the
 *       app. Detects edits and gaps, not removal of the newest rows or a fully recomputed chain.</li>
 *   <li>{@link ExternalCheckpoints}: the HMAC-signed ledger <b>outside PostgreSQL</b>, as <b>reported</b> by the
 *       checkpoint job's own runs. The backend cannot read the ledger; the proof is re-established only by running
 *       the job.</li>
 * </ul>
 */
public record AuditCheckpointStatus(Instant generatedAt, long staleAfterHours, DatabaseChain databaseChain,
        ExternalCheckpoints externalCheckpoints, List<Warning> warnings) {

    /** {@code lastResult} VALID or BROKEN; {@code null} fields mean "never verified". */
    public record DatabaseChain(Long currentHeadChainSeq, Instant lastVerifiedAt, String lastResult) {
    }

    /**
     * Latest run of the checkpoint job and the newest checkpoint it reported. {@code lastRunReason} is a stable code
     * when the run failed. {@code rowsSinceLatestCheckpoint} counts chained audit rows written after it.
     */
    public record ExternalCheckpoints(Instant lastRunAt, String lastRunMode, String lastRunResult, String lastRunReason,
            Instant lastSuccessfulRunAt, Integer checkpoints, Long latestCheckpointHead, Instant latestCheckpointAt,
            Long rowsSinceLatestCheckpoint) {
    }

    /** {@code severity} is DANGER or WARNING; {@code message} is a fixed text, never data from the database. */
    public record Warning(String code, String severity, String message) {
    }
}

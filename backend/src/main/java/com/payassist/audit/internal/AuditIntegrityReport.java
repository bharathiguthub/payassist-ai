package com.payassist.audit.internal;

import java.time.Instant;

/**
 * Result of an audit chain verification.
 *
 * @param result       VALID or BROKEN
 * @param legacyRows   rows written before the chain existed (pinned by the genesis anchor, not chained)
 * @param chainedRows  rows protected by the hash chain, including the genesis anchor
 * @param headChainSeq position of the newest chained row; keep it with {@code headRowHash} as an external checkpoint
 * @param firstFailure the first break found, or {@code null}
 */
public record AuditIntegrityReport(String result, Instant checkedAt, long legacyRows, long chainedRows,
        Long headChainSeq, String headRowHash, Failure firstFailure) {

    public static final String VALID = "VALID";
    public static final String BROKEN = "BROKEN";

    /** Where the chain broke. {@code reason} is a stable code, e.g. ROW_HASH_MISMATCH. */
    public record Failure(Long auditEventId, Long chainSeq, String reason) {
    }

    static AuditIntegrityReport valid(Instant at, long legacy, long chained, Long headSeq, String headHash) {
        return new AuditIntegrityReport(VALID, at, legacy, chained, headSeq, headHash, null);
    }

    static AuditIntegrityReport broken(Instant at, long legacy, long chained, Long headSeq, String headHash,
            Failure failure) {
        return new AuditIntegrityReport(BROKEN, at, legacy, chained, headSeq, headHash, failure);
    }
}

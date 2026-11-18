package com.payassist.audit.internal;

import com.payassist.shared.AuditRecord;
import com.payassist.shared.TextGuard;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Allow-list of audit actions and, per action, the {@code details} keys they may carry. An unknown action, an
 * unexpected key or a value that looks like personal data (an e-mail address or a card-number-like digit run)
 * is refused, and with it the audited operation (fail closed). This keeps free text and accidental personal data
 * out of the append-only trail, which cannot be cleaned up afterwards.
 *
 * <p>Adding an audit action or detail key is a deliberate, reviewed change to this class.
 */
final class AuditDetailPolicy {

    /** Allowed on every action: the stable error code of a refused (DENIED/FAILED) operation. */
    private static final Set<String> COMMON = Set.of("code");

    private static final Set<String> REFUND_REQUEST = Set.of("paymentRef", "amount", "currency", "status");
    private static final Set<String> DISPUTE_CASE = Set.of("paymentRef", "status", "reasonCategory",
            "disputedAmount", "currency");

    static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
            // Reads
            Map.entry("VIEW", Set.of()),
            Map.entry("VIEW_TIMELINE", Set.of()),
            Map.entry("VIEW_INVESTIGATION_SUMMARY", Set.of()),
            Map.entry("REFUND_REQUEST_VIEW", Set.of()),
            Map.entry("DISPUTE_CASE_VIEW", Set.of()),
            // Refunds
            Map.entry("REFUND_ELIGIBILITY_CHECK", Set.of("decision", "reasonCodes", "requestedAmount", "currency",
                    "remainingRefundableAmount", "policyVersion", "paymentVersion")),
            Map.entry("REFUND_REQUEST_CREATE", union(REFUND_REQUEST, "eligibilityDecision", "idempotentReplay")),
            Map.entry("REFUND_REQUEST_APPROVE", union(REFUND_REQUEST, "ledgerSequenceNo", "paymentStatus",
                    "policyVersion")),
            Map.entry("REFUND_REQUEST_REJECT", union(REFUND_REQUEST, "rejectionReason")),
            // Disputes
            Map.entry("DISPUTE_CASE_OPEN", union(DISPUTE_CASE, "idempotentReplay")),
            Map.entry("DISPUTE_NOTE_ADD", Set.of("noteLength", "status")),
            Map.entry("DISPUTE_EVIDENCE_ADD", Set.of("evidenceType", "status")),
            Map.entry("DISPUTE_CASE_TRANSITION", union(DISPUTE_CASE, "transition", "fromStatus", "paymentStatus",
                    "reasonCode")),
            // Security
            Map.entry("ACCESS_DENIED", Set.of()),
            Map.entry("DATA_PROTECTION_REFUSED", Set.of()),
            Map.entry("LOGIN_SUCCESS", Set.of("roles")),
            Map.entry("LOGOUT", Set.of()),
            // Audit trail itself
            Map.entry("AUDIT_LOG_VIEW", Set.of("filterActor", "filterActorSubject", "filterAction", "filterResourceType",
                    "filterResourceId", "filterOutcome", "filterCorrelationId", "filterFrom", "filterTo", "page",
                    "size", "returned")),
            Map.entry("AUDIT_CHECKPOINT_STATUS_VIEW", Set.of("warnings")),
            Map.entry("AUDIT_INTEGRITY_CHECK", Set.of("result", "chainedRows", "legacyRows", "headChainSeq",
                    "failureAuditEventId", "failureReason")));

    /** Opaque identifiers (UUID-like) that can contain long digit runs by chance; only checked for e-mails. */
    private static final Set<String> OPAQUE_IDS = Set.of("filterActorSubject", "filterCorrelationId");

    private AuditDetailPolicy() {
    }

    /** @throws IllegalArgumentException if the record breaks the policy (the key or action is named, never the value) */
    static void check(AuditRecord record) {
        Set<String> allowed = ALLOWED.get(record.action());
        if (allowed == null) {
            throw new IllegalArgumentException("audit action '" + record.action() + "' is not registered");
        }
        record.details().forEach((key, value) -> {
            if (!allowed.contains(key) && !COMMON.contains(key)) {
                throw new IllegalArgumentException(
                        "audit detail '" + key + "' is not allowed for action " + record.action());
            }
            if (OPAQUE_IDS.contains(key) ? value.contains("@") : TextGuard.containsPersonalData(value)) {
                throw new IllegalArgumentException("audit detail '" + key + "' looks like personal data");
            }
        });
    }

    private static Set<String> union(Set<String> base, String... more) {
        var all = new HashSet<>(base);
        all.addAll(Set.of(more));
        return Set.copyOf(all);
    }
}

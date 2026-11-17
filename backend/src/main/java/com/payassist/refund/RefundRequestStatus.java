package com.payassist.refund;

/**
 * Lifecycle of a refund request.
 *
 * <pre>
 *                      ┌──────────── approve (CARE_SUPERVISOR, not requester, within limit) ──► APPROVED
 *  create ─► PENDING_APPROVAL
 *    │                 └──────────── reject  (CARE_SUPERVISOR, not requester) ─────────────────► REJECTED
 *    │                                                                                            ▲
 *    └─(above supervisor limit)─► ESCALATION_REQUIRED ── reject (CARE_SUPERVISOR, not requester) ─┘
 *                                       (approval needs a future REFUND_MANAGER role; not implemented)
 * </pre>
 *
 * PENDING_APPROVAL and ESCALATION_REQUIRED <em>reserve</em> the requested amount; APPROVED consumes the
 * reservation (the refund is recorded in the payment ledger); REJECTED releases it. APPROVED and REJECTED
 * are final.
 */
public enum RefundRequestStatus {
    PENDING_APPROVAL,
    ESCALATION_REQUIRED,
    APPROVED,
    REJECTED;

    /** Whether a request in this status holds a reservation against the payment's refundable amount. */
    public boolean reservesAmount() {
        return this == PENDING_APPROVAL || this == ESCALATION_REQUIRED;
    }

    public boolean isFinal() {
        return this == APPROVED || this == REJECTED;
    }
}

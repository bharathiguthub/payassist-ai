package com.payassist.refund;

/**
 * Human-in-the-loop decisions on refund requests. CARE_SUPERVISOR only; never the requester; never above the
 * supervisor approval limit; ADMIN cannot decide. This interface is intentionally <b>not</b> to be exposed as
 * an AI tool: an agent may prepare a request, but a person must approve it.
 *
 * @see RefundRequestStatus for the state machine
 */
public interface RefundApprovals {

    /**
     * Approves a PENDING_APPROVAL request: re-validates eligibility under the payment row lock, records the
     * refund in the payment ledger exactly once (simulated: no money moves) and consumes the reservation.
     *
     * @param expectedVersion optional optimistic-lock check against the version the supervisor reviewed
     */
    RefundRequestView approve(String refundRef, Long expectedVersion);

    /** Rejects a PENDING_APPROVAL or ESCALATION_REQUIRED request and releases its reservation. */
    RefundRequestView reject(String refundRef, RejectionReason reason, Long expectedVersion);
}

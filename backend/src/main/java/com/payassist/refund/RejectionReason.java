package com.payassist.refund;

/** Why a supervisor rejected a refund request. A fixed list, for the same reasons as {@link RefundReason}. */
public enum RejectionReason {
    OUTSIDE_POLICY,
    INSUFFICIENT_EVIDENCE,
    DUPLICATE_REQUEST,
    CUSTOMER_WITHDREW,
    SUSPECTED_FRAUD,
    ESCALATION_NOT_AVAILABLE
}

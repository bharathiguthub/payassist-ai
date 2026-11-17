package com.payassist.payment;

/**
 * Things that can happen to a payment.
 *
 * <ul>
 *   <li><b>Lifecycle</b> events move the payment to another status (subject to {@code PaymentTransitions}).
 *   <li><b>Informational</b> events record a workflow fact on the payment's timeline (for example a
 *       refund request awaiting approval) without changing its status.
 *   <li><b>System-only</b> events are written by migrations or the payment processor feed and can
 *       never be recorded through {@link PaymentLedger}.
 * </ul>
 */
public enum PaymentEventType {

    /** Written once by the V2 migration for payments that existed before event history. */
    LEGACY_SNAPSHOT(Kind.SYSTEM_ONLY),
    /** The payment was created at the processor. Only the processor feed creates payments. */
    CREATED(Kind.SYSTEM_ONLY),

    AUTHORIZED(Kind.LIFECYCLE),
    CAPTURED(Kind.LIFECYCLE),
    SETTLED(Kind.LIFECYCLE),
    FAILED(Kind.LIFECYCLE),
    VOIDED(Kind.LIFECYCLE),
    /** A refund was executed (simulated in PayAssist: no real money moves). Requires an amount. */
    REFUND_RECORDED(Kind.LIFECYCLE),
    DISPUTE_OPENED(Kind.LIFECYCLE),
    DISPUTE_WON(Kind.LIFECYCLE),
    DISPUTE_LOST(Kind.LIFECYCLE),

    REFUND_REQUESTED(Kind.INFORMATIONAL),
    REFUND_REJECTED(Kind.INFORMATIONAL),
    REFUND_CANCELLED(Kind.INFORMATIONAL),
    REFUND_ESCALATED(Kind.INFORMATIONAL),
    DISPUTE_UPDATED(Kind.INFORMATIONAL);

    public enum Kind { LIFECYCLE, INFORMATIONAL, SYSTEM_ONLY }

    private final Kind kind;

    PaymentEventType(Kind kind) {
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean changesStatus() {
        return kind == Kind.LIFECYCLE;
    }
}

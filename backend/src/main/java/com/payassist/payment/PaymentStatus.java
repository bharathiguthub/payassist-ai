package com.payassist.payment;

/**
 * Lifecycle status of a payment. Allowed moves between statuses are defined by
 * {@code PaymentTransitions}; this enum only names the states.
 */
public enum PaymentStatus {
    PENDING,
    AUTHORIZED,
    CAPTURED,
    SETTLED,
    FAILED,
    /** Authorization released before capture; no money moved. */
    VOIDED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    /**
     * A dispute is under investigation. This is an investigation status only: it does NOT mean the payment has
     * been reversed or charged back. Refunds are blocked while it lasts to avoid paying the customer twice.
     */
    DISPUTED,
    /** Dispute lost: the funds were returned to the cardholder through the card network. */
    CHARGED_BACK;

    /** No further lifecycle event can change a payment in a terminal status. */
    public boolean isTerminal() {
        return this == FAILED || this == VOIDED || this == REFUNDED || this == CHARGED_BACK;
    }
}

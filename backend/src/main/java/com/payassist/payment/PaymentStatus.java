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
    DISPUTED,
    /** Dispute lost: the funds were returned to the cardholder through the card network. */
    CHARGED_BACK;

    /** No further lifecycle event can change a payment in a terminal status. */
    public boolean isTerminal() {
        return this == FAILED || this == VOIDED || this == REFUNDED || this == CHARGED_BACK;
    }
}

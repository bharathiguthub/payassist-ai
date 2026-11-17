package com.payassist.payment;

/** Where a payment fact came from. Lets investigators (and future AI tools) cite the provenance of a fact. */
public enum PaymentEventSource {
    /** The payment processor (simulated in PayAssist). */
    PROCESSOR,
    /** The card network, e.g. a chargeback notification (simulated). */
    CARD_NETWORK,
    /** A PayAssist user action, e.g. an approved refund. */
    PAYASSIST,
    /** Written by a database migration. */
    MIGRATION
}

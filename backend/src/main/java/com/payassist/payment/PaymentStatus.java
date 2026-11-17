package com.payassist.payment;

/** Lifecycle status of a payment. */
public enum PaymentStatus {
    PENDING,
    AUTHORIZED,
    CAPTURED,
    SETTLED,
    FAILED,
    REFUNDED,
    DISPUTED
}

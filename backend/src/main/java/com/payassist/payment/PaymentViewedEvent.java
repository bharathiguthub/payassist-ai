package com.payassist.payment;

import java.time.Instant;

/**
 * Published when a user opens a payment's details. The audit module records it; later phases can
 * also route it to Kafka without changing the payment module.
 */
public record PaymentViewedEvent(String paymentRef, String actor, Instant occurredAt) {
}

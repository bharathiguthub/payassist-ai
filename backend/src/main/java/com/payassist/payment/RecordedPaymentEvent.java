package com.payassist.payment;

import java.util.UUID;

/** Result of {@link PaymentLedger#record}: what was stored and the payment's status/version afterwards. */
public record RecordedPaymentEvent(
        UUID eventId,
        String paymentRef,
        int sequenceNo,
        PaymentEventType type,
        PaymentStatus fromStatus,
        PaymentStatus toStatus,
        long paymentVersion) {
}

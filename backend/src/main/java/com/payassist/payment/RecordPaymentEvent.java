package com.payassist.payment;

import com.payassist.shared.Actor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Command to append an event to a payment's history via {@link PaymentLedger}.
 *
 * @param amount          required for {@code REFUND_RECORDED}; optional otherwise. Same currency as the payment.
 * @param reasonCode      machine-readable reason ({@code UPPER_SNAKE_CASE}); required for {@code FAILED}
 * @param occurredAt      when it happened; {@code null} means now. Processor events may arrive late.
 * @param expectedVersion optimistic-lock check: if set, the payment must still be at this version
 */
public record RecordPaymentEvent(
        String paymentRef,
        PaymentEventType type,
        BigDecimal amount,
        String currency,
        String reasonCode,
        Actor actor,
        PaymentEventSource source,
        Instant occurredAt,
        Long expectedVersion) {

    public RecordPaymentEvent {
        Objects.requireNonNull(paymentRef, "paymentRef");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(source, "source");
    }

    public static RecordPaymentEvent of(String paymentRef, PaymentEventType type, Actor actor,
            PaymentEventSource source) {
        return new RecordPaymentEvent(paymentRef, type, null, null, null, actor, source, null, null);
    }

    public RecordPaymentEvent withAmount(BigDecimal newAmount, String newCurrency) {
        return new RecordPaymentEvent(paymentRef, type, newAmount, newCurrency, reasonCode, actor, source, occurredAt,
                expectedVersion);
    }

    public RecordPaymentEvent withReason(String newReasonCode) {
        return new RecordPaymentEvent(paymentRef, type, amount, currency, newReasonCode, actor, source, occurredAt,
                expectedVersion);
    }

    public RecordPaymentEvent occurredAt(Instant at) {
        return new RecordPaymentEvent(paymentRef, type, amount, currency, reasonCode, actor, source, at,
                expectedVersion);
    }

    public RecordPaymentEvent expectingVersion(long version) {
        return new RecordPaymentEvent(paymentRef, type, amount, currency, reasonCode, actor, source, occurredAt,
                version);
    }
}

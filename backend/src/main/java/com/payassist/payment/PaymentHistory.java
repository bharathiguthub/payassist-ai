package com.payassist.payment;

import com.payassist.customer.CustomerSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Public read API over a payment and its recorded history. Everything returned is a recorded fact
 * from {@code payments} / {@code payment_events}; the descriptions are fixed templates filled with those
 * facts, never generated text. Callers must hold {@code Roles.INVESTIGATE_PAYMENTS}; this method does
 * not audit (callers that expose data to a user record the access themselves).
 */
public interface PaymentHistory {

    /** The payment and its events in chronological order, or {@code ResourceNotFoundException}. */
    PaymentTimeline timeline(String paymentRef);

    /**
     * The facts a refund decision depends on, computed by the same code paths the ledger uses when it
     * records a refund (state machine and refund total), so eligibility and execution cannot disagree.
     */
    RefundPosition refundPosition(String paymentRef);

    /**
     * @param paymentId               internal ID, for references held by other modules (no cross-module JPA relations)
     * @param refundTransitionAllowed whether the payment state machine allows {@code REFUND_RECORDED} now
     * @param capturedAt              when funds were first captured; {@code null} if no capture was recorded
     *                                (migrated payments with incomplete history, or never captured)
     * @param refundedAmount          sum of recorded refunds, at the currency's scale
     * @param historyComplete         false if history before the V2 migration was not recorded
     * @param version                 payment version the facts were read at
     */
    record RefundPosition(
            UUID paymentId,
            String paymentRef,
            BigDecimal amount,
            String currency,
            PaymentStatus status,
            PaymentMethod paymentMethod,
            Instant createdAt,
            Instant capturedAt,
            BigDecimal refundedAmount,
            int refundCount,
            boolean refundTransitionAllowed,
            boolean historyComplete,
            long version) {
    }

    record PaymentTimeline(PaymentSnapshot payment, List<TimelineEntry> events) {
    }

    /** Current state of a payment. Customer data is masked. */
    record PaymentSnapshot(
            String paymentRef,
            BigDecimal amount,
            String currency,
            PaymentStatus status,
            String statusDescription,
            boolean terminal,
            Set<PaymentEventType> nextLifecycleEvents,
            PaymentMethod paymentMethod,
            String merchantName,
            String failureReason,
            String failureDescription,
            Instant createdAt,
            Instant updatedAt,
            long version,
            CustomerSummary customer) {
    }

    /**
     * One recorded event.
     *
     * @param sequenceNo         order in which PayAssist recorded the event (1..n, gap-free)
     * @param toStatus           status after the event; {@code null} for informational events
     * @param actor              username, or {@code system:<name>} for processor/network/migration events
     * @param recordedOutOfOrder true if the event was recorded after an event that occurred later
     *                           (e.g. a late processor message, or history appended after a migration snapshot)
     */
    record TimelineEntry(
            int sequenceNo,
            PaymentEventType eventType,
            PaymentEventType.Kind kind,
            String description,
            PaymentStatus fromStatus,
            PaymentStatus toStatus,
            BigDecimal amount,
            String currency,
            String reasonCode,
            String reasonDescription,
            String actor,
            PaymentEventSource source,
            String correlationId,
            Instant occurredAt,
            Instant recordedAt,
            boolean recordedOutOfOrder) {
    }
}

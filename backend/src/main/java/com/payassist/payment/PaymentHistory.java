package com.payassist.payment;

import com.payassist.customer.CustomerSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Public read API over a payment and its recorded history. Everything returned is a recorded fact
 * from {@code payments} / {@code payment_events}; the descriptions are fixed templates filled with those
 * facts, never generated text. Callers must hold {@code Roles.INVESTIGATE_PAYMENTS}; this method does
 * not audit (callers that expose data to a user record the access themselves).
 */
public interface PaymentHistory {

    /** The payment and its events in chronological order, or {@code ResourceNotFoundException}. */
    PaymentTimeline timeline(String paymentRef);

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

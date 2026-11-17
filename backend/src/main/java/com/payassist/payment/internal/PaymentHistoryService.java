package com.payassist.payment.internal;

import com.payassist.customer.CustomerDirectory;
import com.payassist.customer.CustomerSummary;
import com.payassist.identity.CurrentActor;
import com.payassist.identity.Roles;
import com.payassist.payment.PaymentHistory;
import com.payassist.shared.AuditRecord;
import com.payassist.shared.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds payment timelines from {@code payment_events}, the authoritative record of what happened.
 * Method security is applied here as well as on the controller, so any future caller (for example an
 * AI tool adapter running as the user) is checked the same way.
 */
@Service
public class PaymentHistoryService implements PaymentHistory {

    public static final String ACTION_VIEW_TIMELINE = "VIEW_TIMELINE";
    static final String RESOURCE_PAYMENT = "PAYMENT";

    private final PaymentRepository payments;
    private final PaymentEventRepository events;
    private final CustomerDirectory customers;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    PaymentHistoryService(PaymentRepository payments, PaymentEventRepository events, CustomerDirectory customers,
            ApplicationEventPublisher publisher, Clock clock) {
        this.payments = payments;
        this.events = events;
        this.customers = customers;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Override
    @PreAuthorize(Roles.INVESTIGATE_PAYMENTS)
    @Transactional(readOnly = true)
    public PaymentTimeline timeline(String paymentRef) {
        Payment payment = payments.findByPaymentRef(paymentRef.toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentRef));
        CustomerSummary customer = customers.findSummary(payment.getCustomerId()).orElse(null);
        return new PaymentTimeline(snapshot(payment, customer), entries(payment.getId()));
    }

    @Override
    @PreAuthorize(Roles.INVESTIGATE_PAYMENTS)
    @Transactional(readOnly = true)
    public RefundPosition refundPosition(String paymentRef) {
        Payment p = payments.findByPaymentRef(paymentRef.toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentRef));
        return RefundPositions.of(p, events);
    }

    /** Timeline for display to the current user; the access is audited in the same transaction (fail closed). */
    @PreAuthorize(Roles.INVESTIGATE_PAYMENTS)
    @Transactional
    public PaymentTimeline viewTimeline(String paymentRef) {
        PaymentTimeline timeline = timeline(paymentRef);
        publisher.publishEvent(AuditRecord.of(clock, CurrentActor.require(), ACTION_VIEW_TIMELINE, RESOURCE_PAYMENT,
                timeline.payment().paymentRef()));
        return timeline;
    }

    private static PaymentSnapshot snapshot(Payment p, CustomerSummary customer) {
        return new PaymentSnapshot(p.getPaymentRef(), p.getAmount(), p.getCurrency(), p.getStatus(),
                PaymentDescriptions.status(p.getStatus()), p.getStatus().isTerminal(),
                PaymentTransitions.nextLifecycleEvents(p.getStatus(), p.getPaymentMethod()), p.getPaymentMethod(),
                p.getMerchantName(), p.getFailureReason(), PaymentDescriptions.reason(p.getFailureReason()),
                p.getCreatedAt(), p.getUpdatedAt(), p.getVersion(), customer);
    }

    private List<TimelineEntry> entries(UUID paymentId) {
        List<PaymentEvent> chronological = events.findByPaymentIdOrderByOccurredAtAscSequenceNoAsc(paymentId);
        Set<Integer> outOfOrder = recordedOutOfOrder(chronological);
        List<TimelineEntry> result = new ArrayList<>(chronological.size());
        for (PaymentEvent e : chronological) {
            result.add(new TimelineEntry(e.getSequenceNo(), e.getEventType(), e.getEventType().kind(),
                    PaymentDescriptions.event(e.getEventType(), e.getToStatus(), e.getAmount(), e.getCurrency()),
                    e.getFromStatus(), e.getToStatus(), e.getAmount(), e.getCurrency(), e.getReasonCode(),
                    PaymentDescriptions.reason(e.getReasonCode()), e.getActorUsername(), e.getSource(),
                    e.getCorrelationId(), e.getOccurredAt(), e.getRecordedAt(), outOfOrder.contains(e.getSequenceNo())));
        }
        return result;
    }

    /** Sequence numbers of events recorded after another event that occurred later than they did. */
    static Set<Integer> recordedOutOfOrder(List<PaymentEvent> history) {
        List<PaymentEvent> byRecordingOrder = new ArrayList<>(history);
        byRecordingOrder.sort(Comparator.comparingInt(PaymentEvent::getSequenceNo));
        Set<Integer> late = new HashSet<>();
        Instant latestSoFar = null;
        for (PaymentEvent e : byRecordingOrder) {
            if (latestSoFar != null && e.getOccurredAt().isBefore(latestSoFar)) {
                late.add(e.getSequenceNo());
            } else {
                latestSoFar = e.getOccurredAt();
            }
        }
        return late;
    }
}

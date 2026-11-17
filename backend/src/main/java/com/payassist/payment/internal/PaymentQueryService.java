package com.payassist.payment.internal;

import com.payassist.customer.CustomerDirectory;
import com.payassist.customer.CustomerSummary;
import com.payassist.payment.PaymentStatus;
import com.payassist.payment.PaymentViewedEvent;
import com.payassist.payment.internal.PaymentViews.PaymentDetails;
import com.payassist.payment.internal.PaymentViews.PaymentListItem;
import com.payassist.payment.internal.PaymentViews.PaymentStats;
import com.payassist.shared.ResourceNotFoundException;
import com.payassist.shared.web.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side use cases for payment investigation. These are the operations later phases will
 * expose to AI agents as tools (MCP), so they are kept free of web concerns.
 */
@Service
public class PaymentQueryService {

    /** Upper bound on customers matched by a reference prefix, to keep the IN clause small. */
    static final int MAX_CUSTOMER_MATCHES = 50;

    private final PaymentRepository payments;
    private final CustomerDirectory customers;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PaymentQueryService(PaymentRepository payments, CustomerDirectory customers,
            ApplicationEventPublisher events, Clock clock) {
        this.payments = payments;
        this.customers = customers;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Searches payments. {@code query} matches a payment reference prefix (e.g. {@code PAY-0001})
     * or a customer reference prefix (e.g. {@code CUS-00012}), case-insensitively.
     */
    @Transactional(readOnly = true)
    public PageResponse<PaymentListItem> search(String query, PaymentStatus status, int page, int size) {
        List<Specification<Payment>> filters = new ArrayList<>();
        if (status != null) {
            filters.add(PaymentSpecifications.hasStatus(status));
        }
        if (query != null && !query.isBlank()) {
            String normalized = query.trim().toUpperCase();
            Specification<Payment> textMatch = PaymentSpecifications.paymentRefStartsWith(normalized);
            List<UUID> customerIds = customers.findIdsByRefPrefix(normalized, MAX_CUSTOMER_MATCHES);
            if (!customerIds.isEmpty()) {
                textMatch = textMatch.or(PaymentSpecifications.customerIdIn(customerIds));
            }
            filters.add(textMatch);
        }

        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("paymentRef")));
        Page<Payment> result = payments.findAll(Specification.allOf(filters), pageRequest);

        Map<UUID, CustomerSummary> customerById = customers.findSummaries(
                result.getContent().stream().map(Payment::getCustomerId).distinct().toList());

        return PageResponse.from(result, p -> toListItem(p, customerById.get(p.getCustomerId())));
    }

    /**
     * Returns full payment details and records that {@code actor} viewed them. Runs in one
     * read-write transaction so the read and its audit record succeed or fail together.
     */
    @Transactional
    public PaymentDetails viewDetails(String paymentRef, String actor) {
        Payment payment = payments.findByPaymentRef(paymentRef.toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentRef));
        CustomerSummary customer = customers.findSummary(payment.getCustomerId()).orElse(null);

        events.publishEvent(new PaymentViewedEvent(payment.getPaymentRef(), actor, Instant.now(clock)));

        return new PaymentDetails(payment.getPaymentRef(), payment.getAmount(), payment.getCurrency(),
                payment.getStatus(), payment.getPaymentMethod(), payment.getMerchantName(),
                payment.getFailureReason(), payment.getCreatedAt(), payment.getUpdatedAt(), customer);
    }

    @Transactional(readOnly = true)
    public PaymentStats stats() {
        List<StatusCount> byStatus = payments.countByStatus();
        long total = byStatus.stream().mapToLong(StatusCount::count).sum();
        return new PaymentStats(total, byStatus);
    }

    private static PaymentListItem toListItem(Payment p, CustomerSummary customer) {
        return new PaymentListItem(p.getPaymentRef(),
                customer != null ? customer.customerRef() : null,
                customer != null ? customer.fullName() : null,
                p.getAmount(), p.getCurrency(), p.getStatus(), p.getPaymentMethod(),
                p.getMerchantName(), p.getCreatedAt());
    }
}

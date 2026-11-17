package com.payassist.payment.internal;

import com.payassist.customer.CustomerSummary;
import com.payassist.payment.PaymentMethod;
import com.payassist.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Read models returned by the payment API. Entities are never serialised directly. */
public final class PaymentViews {

    private PaymentViews() {
    }

    public record PaymentListItem(
            String paymentRef,
            String customerRef,
            String customerName,
            BigDecimal amount,
            String currency,
            PaymentStatus status,
            PaymentMethod paymentMethod,
            String merchantName,
            Instant createdAt) {
    }

    public record PaymentDetails(
            String paymentRef,
            BigDecimal amount,
            String currency,
            PaymentStatus status,
            PaymentMethod paymentMethod,
            String merchantName,
            String failureReason,
            Instant createdAt,
            Instant updatedAt,
            CustomerSummary customer) {
    }

    public record PaymentStats(long totalPayments, List<StatusCount> byStatus) {
    }
}

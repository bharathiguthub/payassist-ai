package com.payassist.payment.internal;

import com.payassist.payment.PaymentMethod;
import com.payassist.payment.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A payment. It references its customer by ID only: there is no JPA relationship across module
 * boundaries, so the payment module never depends on the customer module's tables.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "payment_ref", updatable = false, nullable = false, unique = true, length = 32)
    private String paymentRef;

    @Column(name = "customer_id", updatable = false, nullable = false)
    private UUID customerId;

    @Column(name = "amount", updatable = false, nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", updatable = false, nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", updatable = false, nullable = false, length = 32)
    private PaymentMethod paymentMethod;

    @Column(name = "merchant_name", updatable = false, nullable = false, length = 200)
    private String merchantName;

    @Column(name = "failure_reason", length = 64)
    private String failureReason;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic-locking version, incremented on every change (including appended history). */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Payment() {
        // JPA
    }

    @SuppressWarnings("java:S107") // full-state constructor used by tests and future write paths
    public Payment(UUID id, String paymentRef, UUID customerId, BigDecimal amount, String currency,
            PaymentStatus status, PaymentMethod paymentMethod, String merchantName, String failureReason,
            Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.paymentRef = paymentRef;
        this.customerId = customerId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.paymentMethod = paymentMethod;
        this.merchantName = merchantName;
        this.failureReason = failureReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getPaymentRef() {
        return paymentRef;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    /**
     * Applies the outcome of an already-validated event. Only {@code PaymentLedger} calls this, after
     * checking the transition with {@link PaymentTransitions}; the status never changes any other way.
     * Touching {@code updatedAt} also makes informational events bump the optimistic-lock version.
     */
    void applyEvent(PaymentStatus newStatus, String newFailureReason, Instant at) {
        this.status = newStatus;
        if (newFailureReason != null) {
            this.failureReason = newFailureReason;
        }
        this.updatedAt = at;
    }
}

package com.payassist.refund.internal;

import com.payassist.refund.RefundEligibility;
import com.payassist.refund.RefundReason;
import com.payassist.refund.RefundRequestStatus;
import com.payassist.shared.Actor;
import com.payassist.shared.InvalidStateTransitionException;
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
 * A refund request. References its payment by ID (no cross-module JPA relation). The status only changes
 * through {@link #transitionTo}, which enforces {@link RefundRequestTransitions}.
 */
@Entity
@Table(name = "refund_requests")
public class RefundRequest {

    @Id
    private UUID id;

    @Column(name = "refund_ref", updatable = false, nullable = false, unique = true, length = 32)
    private String refundRef;

    @Column(name = "payment_id", updatable = false, nullable = false)
    private UUID paymentId;

    @Column(name = "payment_ref", updatable = false, nullable = false, length = 32)
    private String paymentRef;

    @Column(name = "amount", updatable = false, nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", updatable = false, nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", updatable = false, nullable = false, length = 64)
    private RefundReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RefundRequestStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "eligibility_decision", updatable = false, nullable = false, length = 16)
    private RefundEligibility.Decision eligibilityDecision;

    /** Comma-separated eligibility reason codes. */
    @Column(name = "eligibility_reasons", updatable = false, nullable = false, length = 500)
    private String eligibilityReasons;

    @Column(name = "policy_version", updatable = false, nullable = false, length = 64)
    private String policyVersion;

    @Column(name = "requested_by_subject", updatable = false, nullable = false, length = 128)
    private String requestedBySubject;

    @Column(name = "requested_by_username", updatable = false, nullable = false, length = 128)
    private String requestedByUsername;

    @Column(name = "requested_at", updatable = false, nullable = false)
    private Instant requestedAt;

    @Column(name = "correlation_id", updatable = false, length = 64)
    private String correlationId;

    @Column(name = "idempotency_key", updatable = false, nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", updatable = false, nullable = false, length = 64)
    private String requestFingerprint;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RefundRequest() {
        // JPA
    }

    @SuppressWarnings("java:S107") // flat creation record
    RefundRequest(UUID id, String refundRef, UUID paymentId, String paymentRef, BigDecimal amount, String currency,
            RefundReason reason, RefundRequestStatus status, RefundEligibility.Decision eligibilityDecision,
            String eligibilityReasons, String policyVersion, Actor requester, Instant requestedAt,
            String correlationId, String idempotencyKey, String requestFingerprint) {
        if (status.isFinal()) {
            throw new IllegalArgumentException("a request is created PENDING_APPROVAL or ESCALATION_REQUIRED");
        }
        this.id = id;
        this.refundRef = refundRef;
        this.paymentId = paymentId;
        this.paymentRef = paymentRef;
        this.amount = amount;
        this.currency = currency;
        this.reason = reason;
        this.status = status;
        this.eligibilityDecision = eligibilityDecision;
        this.eligibilityReasons = eligibilityReasons;
        this.policyVersion = policyVersion;
        this.requestedBySubject = requester.subject();
        this.requestedByUsername = requester.username();
        this.requestedAt = requestedAt;
        this.correlationId = correlationId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.updatedAt = requestedAt;
    }

    /** Moves to {@code target} if the state machine allows it; otherwise 409 INVALID_STATE_TRANSITION. */
    void transitionTo(RefundRequestStatus target, Instant at) {
        if (!RefundRequestTransitions.isAllowed(status, target)) {
            throw new InvalidStateTransitionException("Refund request", refundRef, status, target);
        }
        this.status = target;
        this.updatedAt = at;
    }

    boolean isRequestedBy(Actor actor) {
        return actor.subject() != null && actor.subject().equals(requestedBySubject);
    }

    public UUID getId() {
        return id;
    }

    public String getRefundRef() {
        return refundRef;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getPaymentRef() {
        return paymentRef;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public RefundReason getReason() {
        return reason;
    }

    public RefundRequestStatus getStatus() {
        return status;
    }

    public RefundEligibility.Decision getEligibilityDecision() {
        return eligibilityDecision;
    }

    public String getEligibilityReasons() {
        return eligibilityReasons;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public String getRequestedBySubject() {
        return requestedBySubject;
    }

    public String getRequestedByUsername() {
        return requestedByUsername;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

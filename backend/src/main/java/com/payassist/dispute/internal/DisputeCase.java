package com.payassist.dispute.internal;

import com.payassist.dispute.DisputeReasonCategory;
import com.payassist.dispute.DisputeStatus;
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

/** A dispute case. References its payment by ID only; status changes only through {@link #apply}. */
@Entity
@Table(name = "dispute_cases")
public class DisputeCase {

    public static final String SOURCE_PAYASSIST = "PAYASSIST";
    public static final String SOURCE_CARD_NETWORK = "CARD_NETWORK";

    @Id
    private UUID id;

    @Column(name = "case_ref", nullable = false, unique = true, length = 32)
    private String caseRef;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "payment_ref", nullable = false, length = 32)
    private String paymentRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_category", nullable = false, length = 32)
    private DisputeReasonCategory reasonCategory;

    @Column(name = "network_reason_code", length = 16)
    private String networkReasonCode;

    @Column(name = "disputed_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal disputedAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DisputeStatus status;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "response_due_at")
    private Instant responseDueAt;

    @Column(name = "opened_by_subject", length = 128)
    private String openedBySubject;

    @Column(name = "opened_by_username", nullable = false, length = 128)
    private String openedByUsername;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "review_requested_by_subject", length = 128)
    private String reviewRequestedBySubject;

    @Column(name = "review_requested_by_username", length = 128)
    private String reviewRequestedByUsername;

    @Column(name = "review_requested_at")
    private Instant reviewRequestedAt;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", length = 64)
    private String requestFingerprint;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DisputeCase() {
        // JPA
    }

    @SuppressWarnings("java:S107") // flat creation record
    DisputeCase(UUID id, String caseRef, UUID paymentId, String paymentRef, DisputeReasonCategory reasonCategory,
            String networkReasonCode, BigDecimal disputedAmount, String currency, Instant responseDueAt, Actor opener,
            Instant openedAt, String correlationId, String idempotencyKey, String requestFingerprint) {
        this.id = id;
        this.caseRef = caseRef;
        this.paymentId = paymentId;
        this.paymentRef = paymentRef;
        this.reasonCategory = reasonCategory;
        this.networkReasonCode = networkReasonCode;
        this.disputedAmount = disputedAmount;
        this.currency = currency;
        this.status = DisputeStatus.OPEN;
        this.source = SOURCE_PAYASSIST;
        this.responseDueAt = responseDueAt;
        this.openedBySubject = opener.subject();
        this.openedByUsername = opener.username();
        this.openedAt = openedAt;
        this.correlationId = correlationId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.updatedAt = openedAt;
    }

    /** Applies a state-machine transition (already authorised by the caller); 409 if not allowed from here. */
    void apply(DisputeTransitions.Transition transition, Actor actor, Instant at) {
        if (!transition.allowedFrom(status)) {
            throw new InvalidStateTransitionException("Dispute case", caseRef, status, transition.action);
        }
        if (transition == DisputeTransitions.Transition.REQUEST_REVIEW) {
            reviewRequestedBySubject = actor.subject();
            reviewRequestedByUsername = actor.username();
            reviewRequestedAt = at;
        }
        status = transition.to;
        updatedAt = at;
    }

    /** Marks a change that is not a status change (note, evidence), so the optimistic version moves on. */
    void touch(Instant at) {
        updatedAt = at;
    }

    boolean openedBy(Actor actor) {
        return actor.subject() != null && actor.subject().equals(openedBySubject);
    }

    boolean reviewRequestedBy(Actor actor) {
        return actor.subject() != null && actor.subject().equals(reviewRequestedBySubject);
    }

    boolean isOverdue(Instant now) {
        return responseDueAt != null && !status.isFinal() && now.isAfter(responseDueAt);
    }

    public UUID getId() {
        return id;
    }

    public String getCaseRef() {
        return caseRef;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getPaymentRef() {
        return paymentRef;
    }

    public DisputeReasonCategory getReasonCategory() {
        return reasonCategory;
    }

    public String getNetworkReasonCode() {
        return networkReasonCode;
    }

    public BigDecimal getDisputedAmount() {
        return disputedAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public DisputeStatus getStatus() {
        return status;
    }

    public String getSource() {
        return source;
    }

    public Instant getResponseDueAt() {
        return responseDueAt;
    }

    public String getOpenedBySubject() {
        return openedBySubject;
    }

    public String getOpenedByUsername() {
        return openedByUsername;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public String getReviewRequestedByUsername() {
        return reviewRequestedByUsername;
    }

    public String getCorrelationId() {
        return correlationId;
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

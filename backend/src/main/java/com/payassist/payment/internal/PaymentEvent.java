package com.payassist.payment.internal;

import com.payassist.payment.PaymentEventSource;
import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One entry in a payment's append-only history. {@code @Immutable} stops Hibernate from ever issuing
 * an UPDATE; a database trigger additionally rejects UPDATE, DELETE and TRUNCATE.
 */
@Entity
@Immutable
@Table(name = "payment_events")
public class PaymentEvent {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private PaymentEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 32)
    private PaymentStatus toStatus;

    @Column(name = "amount", precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "actor_subject", length = 128)
    private String actorSubject;

    @Column(name = "actor_username", nullable = false, length = 128)
    private String actorUsername;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32)
    private PaymentEventSource source;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected PaymentEvent() {
        // JPA
    }

    @SuppressWarnings("java:S107") // an event is a flat record of facts
    public PaymentEvent(UUID id, UUID paymentId, int sequenceNo, PaymentEventType eventType, PaymentStatus fromStatus,
            PaymentStatus toStatus, BigDecimal amount, String currency, String reasonCode, String actorSubject,
            String actorUsername, String correlationId, PaymentEventSource source, Instant occurredAt,
            Instant recordedAt) {
        this.id = id;
        this.paymentId = paymentId;
        this.sequenceNo = sequenceNo;
        this.eventType = eventType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.amount = amount;
        this.currency = currency;
        this.reasonCode = reasonCode;
        this.actorSubject = actorSubject;
        this.actorUsername = actorUsername;
        this.correlationId = correlationId;
        this.source = source;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public int getSequenceNo() {
        return sequenceNo;
    }

    public PaymentEventType getEventType() {
        return eventType;
    }

    public PaymentStatus getFromStatus() {
        return fromStatus;
    }

    public PaymentStatus getToStatus() {
        return toStatus;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getActorSubject() {
        return actorSubject;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public PaymentEventSource getSource() {
        return source;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}

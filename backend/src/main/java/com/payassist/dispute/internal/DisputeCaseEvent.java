package com.payassist.dispute.internal;

import com.payassist.dispute.DisputeStatus;
import com.payassist.shared.Actor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** One entry in a case's append-only history (transitions, notes and evidence additions). */
@Entity
@Immutable
@Table(name = "dispute_case_events")
public class DisputeCaseEvent {

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private DisputeStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 32)
    private DisputeStatus toStatus;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "actor_subject", length = 128)
    private String actorSubject;

    @Column(name = "actor_username", nullable = false, length = 128)
    private String actorUsername;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "ledger_event_id", unique = true)
    private UUID ledgerEventId;

    protected DisputeCaseEvent() {
    }

    @SuppressWarnings("java:S107")
    DisputeCaseEvent(UUID caseId, int sequenceNo, String eventType, DisputeStatus fromStatus, DisputeStatus toStatus,
            String reasonCode, Actor actor, String correlationId, Instant occurredAt, UUID ledgerEventId) {
        this.id = UUID.randomUUID();
        this.caseId = caseId;
        this.sequenceNo = sequenceNo;
        this.eventType = eventType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reasonCode = reasonCode;
        this.actorSubject = actor.subject();
        this.actorUsername = actor.username();
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
        this.ledgerEventId = ledgerEventId;
    }

    int getSequenceNo() {
        return sequenceNo;
    }

    String getEventType() {
        return eventType;
    }

    DisputeStatus getFromStatus() {
        return fromStatus;
    }

    DisputeStatus getToStatus() {
        return toStatus;
    }

    String getReasonCode() {
        return reasonCode;
    }

    String getActorUsername() {
        return actorUsername;
    }

    String getCorrelationId() {
        return correlationId;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }

    UUID getLedgerEventId() {
        return ledgerEventId;
    }
}

package com.payassist.refund.internal;

import com.payassist.refund.RefundRequestStatus;
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

/**
 * The single, immutable decision on a refund request. The table allows one row per request (unique) and is
 * append-only (trigger), so double approval is impossible even below the application layer.
 */
@Entity
@Immutable
@Table(name = "refund_decisions")
public class RefundDecision {

    static final String APPROVED_REASON = "APPROVED";

    @Id
    private UUID id;

    @Column(name = "refund_request_id", nullable = false, unique = true)
    private UUID refundRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 16)
    private RefundRequestStatus decision;

    @Column(name = "reason_code", nullable = false, length = 64)
    private String reasonCode;

    @Column(name = "decided_by_subject", nullable = false, length = 128)
    private String decidedBySubject;

    @Column(name = "decided_by_username", nullable = false, length = 128)
    private String decidedByUsername;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** The REFUND_RECORDED payment event created by an approval; null for rejections. */
    @Column(name = "ledger_event_id", unique = true)
    private UUID ledgerEventId;

    protected RefundDecision() {
        // JPA
    }

    RefundDecision(UUID refundRequestId, RefundRequestStatus decision, String reasonCode, Actor decidedBy,
            Instant decidedAt, String correlationId, UUID ledgerEventId) {
        if (!decision.isFinal()) {
            throw new IllegalArgumentException("a decision is APPROVED or REJECTED");
        }
        if ((decision == RefundRequestStatus.APPROVED) != (ledgerEventId != null)) {
            throw new IllegalArgumentException("an approval must reference exactly one ledger event");
        }
        this.id = UUID.randomUUID();
        this.refundRequestId = refundRequestId;
        this.decision = decision;
        this.reasonCode = reasonCode;
        this.decidedBySubject = decidedBy.subject();
        this.decidedByUsername = decidedBy.username();
        this.decidedAt = decidedAt;
        this.correlationId = correlationId;
        this.ledgerEventId = ledgerEventId;
    }

    public UUID getRefundRequestId() {
        return refundRequestId;
    }

    public RefundRequestStatus getDecision() {
        return decision;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getDecidedBySubject() {
        return decidedBySubject;
    }

    public String getDecidedByUsername() {
        return decidedByUsername;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public UUID getLedgerEventId() {
        return ledgerEventId;
    }
}

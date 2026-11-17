package com.payassist.dispute.internal;

import com.payassist.dispute.EvidenceType;
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

/** Append-only evidence metadata. No document content is stored; only where it lives. */
@Entity
@Immutable
@Table(name = "dispute_evidence")
public class DisputeEvidenceItem {

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_type", nullable = false, length = 32)
    private EvidenceType type;

    @Column(name = "document_reference", nullable = false, length = 128)
    private String documentReference;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "added_by_subject", nullable = false, length = 128)
    private String addedBySubject;

    @Column(name = "added_by_username", nullable = false, length = 128)
    private String addedByUsername;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    protected DisputeEvidenceItem() {
    }

    @SuppressWarnings("java:S107")
    DisputeEvidenceItem(UUID caseId, EvidenceType type, String documentReference, String description, Instant receivedAt,
            Actor addedBy, Instant addedAt, String correlationId, String idempotencyKey, String requestFingerprint) {
        this.id = UUID.randomUUID();
        this.caseId = caseId;
        this.type = type;
        this.documentReference = documentReference;
        this.description = description;
        this.receivedAt = receivedAt;
        this.addedBySubject = addedBy.subject();
        this.addedByUsername = addedBy.username();
        this.addedAt = addedAt;
        this.correlationId = correlationId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
    }

    UUID getCaseId() {
        return caseId;
    }

    EvidenceType getType() {
        return type;
    }

    String getDocumentReference() {
        return documentReference;
    }

    String getDescription() {
        return description;
    }

    Instant getReceivedAt() {
        return receivedAt;
    }

    String getAddedByUsername() {
        return addedByUsername;
    }

    Instant getAddedAt() {
        return addedAt;
    }

    String getRequestFingerprint() {
        return requestFingerprint;
    }
}

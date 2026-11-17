package com.payassist.dispute.internal;

import com.payassist.shared.Actor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** An append-only investigation note. Plain text, validated by {@code TextGuard}; rendered as text only. */
@Entity
@Immutable
@Table(name = "dispute_notes")
public class DisputeNote {

    @Id
    private UUID id;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "body", nullable = false, length = 2000)
    private String body;

    @Column(name = "author_subject", nullable = false, length = 128)
    private String authorSubject;

    @Column(name = "author_username", nullable = false, length = 128)
    private String authorUsername;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    protected DisputeNote() {
    }

    DisputeNote(UUID caseId, String body, Actor author, Instant createdAt, String correlationId, String idempotencyKey,
            String requestFingerprint) {
        this.id = UUID.randomUUID();
        this.caseId = caseId;
        this.body = body;
        this.authorSubject = author.subject();
        this.authorUsername = author.username();
        this.createdAt = createdAt;
        this.correlationId = correlationId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
    }

    UUID getId() {
        return id;
    }

    UUID getCaseId() {
        return caseId;
    }

    String getBody() {
        return body;
    }

    String getAuthorUsername() {
        return authorUsername;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    String getRequestFingerprint() {
        return requestFingerprint;
    }
}

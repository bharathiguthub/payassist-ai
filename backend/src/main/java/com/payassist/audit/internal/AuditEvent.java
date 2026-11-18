package com.payassist.audit.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only record of who accessed or changed which sensitive resource and when. DB triggers block updates
 * and deletes and chain every new row into a tamper-evident hash chain (V7, ADR 0013): {@code chainSeq},
 * {@code prevHash} and {@code rowHash} are always assigned by the database, never by the application.
 * Rows written before V7 have no chain columns ("legacy"); they are pinned by the genesis anchor instead.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor", nullable = false, length = 128)
    private String actor;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 64)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, length = 128)
    private String resourceId;

    @Column(name = "actor_subject", length = 128)
    private String actorSubject;

    /** USER, SYSTEM or (later phases) AGENT; NULL on legacy rows. */
    @Column(name = "actor_type", length = 16)
    private String actorType;

    /** The human an AGENT acted for. Reserved for agent delegation; always NULL in this phase. */
    @Column(name = "on_behalf_of_subject", length = 128)
    private String onBehalfOfSubject;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** SUCCESS, DENIED or FAILED; NULL on Phase 1 rows, which were all successful reads. */
    @Column(name = "outcome", length = 16)
    private String outcome;

    /** Non-sensitive structured context (decision, codes, versions). Stored as JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private Map<String, String> details;

    @Column(name = "chain_seq", insertable = false, updatable = false)
    private Long chainSeq;

    @Column(name = "prev_hash", length = 64, insertable = false, updatable = false)
    private String prevHash;

    @Column(name = "row_hash", length = 64, insertable = false, updatable = false)
    private String rowHash;

    protected AuditEvent() {
        // JPA
    }

    @SuppressWarnings("java:S107") // flat audit record
    public AuditEvent(Instant occurredAt, String actor, String actorSubject, String actorType, String action,
            String resourceType, String resourceId, String correlationId, Map<String, String> details,
            String outcome) {
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.actorSubject = actorSubject;
        this.actorType = actorType;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.correlationId = correlationId;
        this.details = details == null || details.isEmpty() ? null : new LinkedHashMap<>(details);
        this.outcome = outcome;
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getActorSubject() {
        return actorSubject;
    }

    public String getActorType() {
        return actorType;
    }

    public String getOnBehalfOfSubject() {
        return onBehalfOfSubject;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getOutcome() {
        return outcome == null ? "SUCCESS" : outcome;
    }

    public Map<String, String> getDetails() {
        return details == null ? Map.of() : Collections.unmodifiableMap(details);
    }

    /** Position in the hash chain, or {@code null} for a legacy (pre-V7) row. */
    public Long getChainSeq() {
        return chainSeq;
    }

    public String getRowHash() {
        return rowHash;
    }
}

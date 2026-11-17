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

/** Append-only record of who accessed which sensitive resource and when (a DB trigger blocks changes). */
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

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** Non-sensitive structured context (decision, codes, versions). Stored as JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private Map<String, String> details;

    protected AuditEvent() {
        // JPA
    }

    public AuditEvent(Instant occurredAt, String actor, String action, String resourceType, String resourceId) {
        this(occurredAt, actor, null, action, resourceType, resourceId, null, null);
    }

    @SuppressWarnings("java:S107") // flat audit record
    public AuditEvent(Instant occurredAt, String actor, String actorSubject, String action, String resourceType,
            String resourceId, String correlationId, Map<String, String> details) {
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.actorSubject = actorSubject;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.correlationId = correlationId;
        this.details = details == null || details.isEmpty() ? null : new LinkedHashMap<>(details);
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

    public String getCorrelationId() {
        return correlationId;
    }

    public Map<String, String> getDetails() {
        return details == null ? Map.of() : Collections.unmodifiableMap(details);
    }
}

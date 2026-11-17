package com.payassist.shared;

import com.payassist.shared.web.CorrelationIdFilter;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.MDC;

/**
 * A domain event asking the audit module to record an access or action. Any module can publish it
 * without depending on the audit module. The audit listener runs synchronously in the publisher's
 * transaction, so if the audit record cannot be written the audited operation fails too (fail closed).
 *
 * <p>Fields must never contain personal data: references, codes and actors only.
 */
public record AuditRecord(
        Instant occurredAt,
        Actor actor,
        String action,
        String resourceType,
        String resourceId,
        String correlationId) {

    public AuditRecord {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
    }

    /** An audit record for now, carrying the current request's correlation ID. */
    public static AuditRecord of(Clock clock, Actor actor, String action, String resourceType, String resourceId) {
        return new AuditRecord(Instant.now(clock), actor, action, resourceType, resourceId,
                MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}

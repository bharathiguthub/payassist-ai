package com.payassist.shared;

import com.payassist.shared.web.CorrelationIdFilter;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * A domain event asking the audit module to record an access or action. Any module can publish it
 * without depending on the audit module. The audit listener runs synchronously in the publisher's
 * transaction, so if the audit record cannot be written the audited operation fails too (fail closed).
 *
 * <p>Fields must never contain personal data: references, codes, amounts and versions only.
 * {@code details} keys are restricted to {@code camelCase} identifiers and values are length-limited,
 * which keeps free text (and with it accidental personal data) out of the audit trail.
 */
public record AuditRecord(
        Instant occurredAt,
        Actor actor,
        String action,
        String resourceType,
        String resourceId,
        String correlationId,
        Map<String, String> details,
        Outcome outcome) {

    /** Result of the audited operation. */
    public enum Outcome {
        SUCCESS,
        /** Refused by an authorization or separation-of-duties rule. */
        DENIED,
        /** Refused by a business rule (e.g. not eligible, invalid state). */
        FAILED
    }

    private static final Pattern DETAIL_KEY = Pattern.compile("^[a-z][A-Za-z0-9]{0,39}$");
    private static final int MAX_DETAIL_VALUE = 200;
    private static final int MAX_DETAILS = 20;

    public AuditRecord {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
        outcome = outcome == null ? Outcome.SUCCESS : outcome;
        details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
        if (details.size() > MAX_DETAILS) {
            throw new IllegalArgumentException("too many audit details");
        }
        details.forEach((key, value) -> {
            if (!DETAIL_KEY.matcher(key).matches() || value == null || value.length() > MAX_DETAIL_VALUE) {
                throw new IllegalArgumentException("invalid audit detail '" + key + "'");
            }
        });
    }

    /** An audit record for now, carrying the current request's correlation ID. */
    public static AuditRecord of(Clock clock, Actor actor, String action, String resourceType, String resourceId) {
        return new AuditRecord(Instant.now(clock), actor, action, resourceType, resourceId,
                MDC.get(CorrelationIdFilter.MDC_KEY), Map.of(), Outcome.SUCCESS);
    }

    /** The same record with non-sensitive structured details (insertion order is preserved). */
    public AuditRecord withDetails(Map<String, String> newDetails) {
        return new AuditRecord(occurredAt, actor, action, resourceType, resourceId, correlationId, newDetails, outcome);
    }

    /** The same record with a different outcome. */
    public AuditRecord withOutcome(Outcome newOutcome) {
        return new AuditRecord(occurredAt, actor, action, resourceType, resourceId, correlationId, details, newOutcome);
    }
}

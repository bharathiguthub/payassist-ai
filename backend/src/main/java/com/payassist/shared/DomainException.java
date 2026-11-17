package com.payassist.shared;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class for business-rule failures. Each carries a stable, machine-readable {@code code} (returned
 * to clients in Problem Details and usable by future AI tools) and a message that must never contain
 * personal data: only references, statuses, amounts and rule names. Optional {@code properties} are added
 * to the Problem Details (for example the eligibility reason codes behind a rejection).
 */
public abstract class DomainException extends RuntimeException {

    private final String code;
    private final Map<String, Object> properties;

    protected DomainException(String code, String message) {
        this(code, message, Map.of());
    }

    protected DomainException(String code, String message, Map<String, Object> properties) {
        super(message);
        this.code = code;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    /** The request conflicts with the resource's current state (HTTP 409). */
    public static class Conflict extends DomainException {
        public Conflict(String code, String message) {
            super(code, message);
        }
    }

    /** The request is well-formed but violates a business rule (HTTP 422). */
    public static class RuleViolation extends DomainException {
        public RuleViolation(String code, String message) {
            super(code, message);
        }

        public RuleViolation(String code, String message, Map<String, Object> properties) {
            super(code, message, properties);
        }
    }

    /**
     * The user is authenticated and holds the right role, but a business authorization rule forbids this
     * particular action, e.g. approving one's own request or exceeding an approval limit (HTTP 403).
     */
    public static class Forbidden extends DomainException {
        public Forbidden(String code, String message) {
            super(code, message);
        }
    }
}

package com.payassist.shared;

/**
 * Base class for business-rule failures. Each carries a stable, machine-readable {@code code} (returned
 * to clients in Problem Details and usable by future AI tools) and a message that must never contain
 * personal data: only references, statuses, amounts and rule names.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
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
    }
}

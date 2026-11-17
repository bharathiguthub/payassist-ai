package com.payassist.identity;

import java.util.Set;

/**
 * Application roles. They are Keycloak realm roles, exposed to the backend through the ID token
 * {@code roles} claim and mapped to Spring authorities {@code ROLE_<name>}.
 *
 * <p>The SpEL constants are used in {@code @PreAuthorize} so every module expresses access rules
 * the same way. Roles are listed explicitly (no implicit hierarchy) so each rule is readable on
 * its own.
 */
public final class Roles {

    public static final String CARE_AGENT = "CARE_AGENT";
    public static final String CARE_SUPERVISOR = "CARE_SUPERVISOR";
    public static final String ADMIN = "ADMIN";

    /** Only these Keycloak roles are ever mapped; everything else in the token is ignored. */
    public static final Set<String> ALL = Set.of(CARE_AGENT, CARE_SUPERVISOR, ADMIN);

    public static final String ANY_CARE_ROLE = "hasAnyRole('CARE_AGENT','CARE_SUPERVISOR','ADMIN')";
    public static final String SUPERVISOR_OR_ADMIN = "hasAnyRole('CARE_SUPERVISOR','ADMIN')";
    public static final String ADMIN_ONLY = "hasRole('ADMIN')";

    /**
     * Read-only payment investigation (details, timeline, investigation summary). ADMIN is granted this
     * explicitly, as read-only access for oversight; it confers no right to act on payments.
     */
    public static final String INVESTIGATE_PAYMENTS = "hasAnyRole('CARE_AGENT','CARE_SUPERVISOR','ADMIN')";

    /**
     * Advisory refund eligibility check (read-only; creates nothing). ADMIN is granted this explicitly as
     * read-only oversight; requesting or approving refunds will be separate, narrower permissions.
     */
    public static final String CHECK_REFUND_ELIGIBILITY = "hasAnyRole('CARE_AGENT','CARE_SUPERVISOR','ADMIN')";

    private Roles() {
    }
}

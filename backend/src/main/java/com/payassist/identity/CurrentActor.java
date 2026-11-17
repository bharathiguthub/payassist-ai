package com.payassist.identity;

import com.payassist.shared.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Resolves the {@link Actor} for the authenticated user from the security context.
 *
 * <p>Services resolve the actor themselves instead of accepting it as a parameter, so no caller
 * (including a future AI tool adapter) can act or be audited under someone else's identity.
 */
public final class CurrentActor {

    private CurrentActor() {
    }

    /** The current user, or {@link AuthenticationCredentialsNotFoundException} (HTTP 401) if there is none. */
    public static Actor require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AuthenticationCredentialsNotFoundException("No authenticated user");
        }
        return from(authentication);
    }

    /**
     * OIDC logins (the only login type in PayAssist) use the immutable {@code sub} claim as subject and
     * {@code preferred_username} for display. Any other authentication type falls back to its name for
     * both, which only happens in tests.
     */
    public static Actor from(Authentication authentication) {
        try {
            if (authentication.getPrincipal() instanceof OidcUser oidc) {
                String username = oidc.getPreferredUsername() != null ? oidc.getPreferredUsername()
                        : authentication.getName();
                return Actor.user(oidc.getSubject(), username);
            }
            return Actor.user(authentication.getName(), authentication.getName());
        } catch (IllegalArgumentException | NullPointerException e) {
            // E.g. a username using the reserved "system:" prefix, or an over-long or missing identity.
            // Such an identity cannot be recorded unambiguously in history or audit, so the request is
            // refused (HTTP 403) instead of failing with a server error. The value is not logged or echoed.
            LOG.warn("Refusing request: authenticated identity cannot be used as an actor ({})", e.getMessage());
            throw new AccessDeniedException("This account cannot perform audited actions");
        }
    }

    /** Whether the current user holds the PayAssist role (e.g. {@code Roles.CARE_SUPERVISOR}). */
    public static boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + role).equals(a.getAuthority()));
    }

    private static final Logger LOG = LoggerFactory.getLogger(CurrentActor.class);
}

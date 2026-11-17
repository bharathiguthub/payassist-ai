package com.payassist.identity;

import com.payassist.shared.Actor;
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
        if (authentication.getPrincipal() instanceof OidcUser oidc) {
            String username = oidc.getPreferredUsername() != null ? oidc.getPreferredUsername() : authentication.getName();
            return Actor.user(oidc.getSubject(), username);
        }
        return Actor.user(authentication.getName(), authentication.getName());
    }
}

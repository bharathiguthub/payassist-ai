package com.payassist.identity.security;

import com.payassist.identity.CurrentActor;
import com.payassist.shared.Actor;
import com.payassist.shared.AuditRecord;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Component;

/**
 * Audits interactive sign-in and sign-out ({@code LOGIN_SUCCESS}, {@code LOGOUT}).
 *
 * <ul>
 *   <li>Only identifiers are recorded: the OIDC {@code sub}, the username, the client registration and the mapped
 *       PayAssist roles. Never tokens, authorization codes, session IDs or claims such as e-mail.
 *   <li><b>Login is fail closed</b>: if the audit record cannot be written, the new session is invalidated and the
 *       user is sent to the login-failed page, so no unaudited session exists.
 *   <li><b>Logout is best effort</b>: signing out must always work, so an audit failure is logged, not raised.
 *   <li>Failed password attempts happen inside Keycloak and are not visible here (deferred; see ADR 0013).
 * </ul>
 */
@Component
class AuthenticationAuditor {

    static final String ACTION_LOGIN = "LOGIN_SUCCESS";
    static final String ACTION_LOGOUT = "LOGOUT";
    static final String RESOURCE_TYPE = "AUTHENTICATION";

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationAuditor.class);

    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    AuthenticationAuditor(ApplicationEventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    /** Wraps the redirect-to-SPA success handler with the login audit. */
    AuthenticationSuccessHandler loginSuccessHandler(String targetUrl, String failureUrl) {
        var redirect = new SimpleUrlAuthenticationSuccessHandler(targetUrl);
        redirect.setAlwaysUseDefaultTargetUrl(true);
        return new AuthenticationSuccessHandler() {
            @Override
            public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                    Authentication authentication) throws IOException, ServletException {
                try {
                    record(ACTION_LOGIN, authentication, Map.of("roles", roles(authentication)));
                } catch (RuntimeException e) {
                    LOG.error("Login refused because it could not be audited ({})", e.getClass().getSimpleName());
                    new SecurityContextLogoutHandler().logout(request, response, authentication);
                    response.sendRedirect(failureUrl);
                    return;
                }
                redirect.onAuthenticationSuccess(request, response, authentication);
            }
        };
    }

    /** Runs before the session is invalidated, while the user is still known. */
    LogoutHandler logoutHandler() {
        return (request, response, authentication) -> {
            if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
                return;
            }
            try {
                record(ACTION_LOGOUT, authentication, Map.of());
            } catch (RuntimeException e) {
                LOG.warn("Logout could not be audited ({})", e.getClass().getSimpleName());
            }
        };
    }

    private void record(String action, Authentication authentication, Map<String, String> details) {
        Actor actor = CurrentActor.from(authentication);
        String registration = authentication instanceof OAuth2AuthenticationToken oauth
                ? oauth.getAuthorizedClientRegistrationId() : "local";
        publisher.publishEvent(AuditRecord.of(clock, actor, action, RESOURCE_TYPE, "oidc:" + registration)
                .withDetails(details));
    }

    /** Mapped PayAssist roles, e.g. "CARE_AGENT", or "NONE" for a user without a PayAssist role. */
    static String roles(Authentication authentication) {
        String roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .sorted()
                .collect(Collectors.joining(","));
        return roles.isEmpty() ? "NONE" : roles;
    }
}

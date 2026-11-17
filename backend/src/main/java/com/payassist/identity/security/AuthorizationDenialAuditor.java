package com.payassist.identity.security;

import com.payassist.identity.CurrentActor;
import com.payassist.shared.Actor;
import com.payassist.shared.AuditRecord;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Records role-based authorization denials (URL rules and {@code @PreAuthorize}) of <em>authenticated</em>
 * users as {@code ACCESS_DENIED} audit records with outcome DENIED, e.g. an agent or admin trying to approve a
 * refund. Anonymous requests (401) are not audited: there is no actor, and they are mostly noise.
 *
 * <p>The resource is the HTTP method and path (never the query string or body) or the denied service method.
 */
@Component
class AuthorizationDenialAuditor {

    static final String ACTION = "ACCESS_DENIED";
    private static final int MAX_RESOURCE_ID = 128;

    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    AuthorizationDenialAuditor(ApplicationEventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    @EventListener
    void on(AuthorizationDeniedEvent<?> event) {
        Authentication authentication = event.getAuthentication().get();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return;
        }
        Actor actor;
        try {
            actor = CurrentActor.from(authentication);
        } catch (AccessDeniedException e) {
            return; // identity cannot be represented (e.g. reserved "system:" name); CurrentActor already logged it
        }
        String resourceType;
        String resourceId;
        if (event.getObject() instanceof HttpServletRequest request) {
            resourceType = "ENDPOINT";
            resourceId = request.getMethod() + " " + request.getRequestURI();
        } else if (event.getObject() instanceof MethodInvocation invocation) {
            resourceType = "METHOD";
            resourceId = invocation.getMethod().getDeclaringClass().getSimpleName() + "."
                    + invocation.getMethod().getName();
        } else {
            resourceType = "OTHER";
            resourceId = event.getObject().getClass().getSimpleName();
        }
        if (resourceId.length() > MAX_RESOURCE_ID) {
            resourceId = resourceId.substring(0, MAX_RESOURCE_ID);
        }
        publisher.publishEvent(AuditRecord.of(clock, actor, ACTION, resourceType, resourceId)
                .withOutcome(AuditRecord.Outcome.DENIED));
    }
}

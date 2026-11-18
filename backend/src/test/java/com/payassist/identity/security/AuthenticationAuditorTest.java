package com.payassist.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.payassist.shared.AuditRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class AuthenticationAuditorTest {

    private static final String TOKEN_VALUE = "eyJ-secret-id-token-value";

    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final AuthenticationAuditor auditor = new AuthenticationAuditor(publisher,
            Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), java.time.ZoneOffset.UTC));

    static OAuth2AuthenticationToken login(String subject, String username, String... roles) {
        var idToken = new OidcIdToken(TOKEN_VALUE, Instant.parse("2026-11-16T11:59:00Z"),
                Instant.parse("2026-11-16T12:59:00Z"),
                Map.of("sub", subject, "preferred_username", username, "email", username + "@example.test"));
        var authorities = java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
        return new OAuth2AuthenticationToken(new DefaultOidcUser(authorities, idToken), authorities, "keycloak");
    }

    @Test
    void loginIsAuditedWithIdentifiersOnlyThenRedirectsToTheSpa() throws Exception {
        var response = new MockHttpServletResponse();
        auditor.loginSuccessHandler("http://localhost:3000/", "http://localhost:3000/?error=login_failed")
                .onAuthenticationSuccess(new MockHttpServletRequest(), response,
                        login("sub-alice", "agent.alice", "CARE_AGENT"));

        var captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(publisher).publishEvent(captor.capture());
        AuditRecord record = captor.getValue();
        assertThat(record.action()).isEqualTo("LOGIN_SUCCESS");
        assertThat(record.actor().subject()).isEqualTo("sub-alice");
        assertThat(record.actorType()).isEqualTo(AuditRecord.ActorType.USER);
        assertThat(record.resourceType()).isEqualTo("AUTHENTICATION");
        assertThat(record.resourceId()).isEqualTo("oidc:keycloak");
        assertThat(record.details()).containsExactly(Map.entry("roles", "CARE_AGENT"));
        assertThat(record.toString()).doesNotContain(TOKEN_VALUE).doesNotContain("@example.test");
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:3000/");
    }

    @Test
    void loginIsRefusedAndTheSessionEndedWhenTheAuditFails() throws Exception {
        doThrow(new IllegalStateException("db down")).when(publisher).publishEvent(any(Object.class));
        var request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        request.setSession(session);
        var response = new MockHttpServletResponse();

        auditor.loginSuccessHandler("http://localhost:3000/", "http://localhost:3000/?error=login_failed")
                .onAuthenticationSuccess(request, response, login("sub-alice", "agent.alice", "CARE_AGENT"));

        assertThat(session.isInvalid()).isTrue();
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:3000/?error=login_failed");
    }

    @Test
    void usersWithoutAPayAssistRoleAreRecordedAsNone() {
        assertThat(AuthenticationAuditor.roles(login("sub-nora", "nora.norole"))).isEqualTo("NONE");
        assertThat(AuthenticationAuditor.roles(login("sub-ada", "ada.admin", "ADMIN", "CARE_SUPERVISOR")))
                .isEqualTo("ADMIN,CARE_SUPERVISOR");
    }

    @Test
    void logoutIsAuditedButNeverBlocked() {
        var handler = auditor.logoutHandler();
        handler.logout(new MockHttpServletRequest(), new MockHttpServletResponse(),
                login("sub-alice", "agent.alice", "CARE_AGENT"));
        var captor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("LOGOUT");

        doThrow(new IllegalStateException("db down")).when(publisher).publishEvent(any(Object.class));
        handler.logout(new MockHttpServletRequest(), new MockHttpServletResponse(),
                login("sub-alice", "agent.alice", "CARE_AGENT")); // no exception
    }

    @Test
    void anonymousLogoutIsNotAudited() {
        auditor.logoutHandler().logout(new MockHttpServletRequest(), new MockHttpServletResponse(), null);
        verify(publisher, never()).publishEvent(any(Object.class));
    }
}

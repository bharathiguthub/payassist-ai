package com.payassist.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import java.time.Instant;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The synthetic personas of the Keycloak realm, with stable test subjects (the real realm pins its own IDs).
 * Use {@link #mvc} for MockMvc requests and {@link #authentication} to run service calls as a user on a
 * worker thread (concurrency tests).
 */
public enum TestUsers {
    ALICE("sub-agent-alice", "agent.alice", "CARE_AGENT"),
    SAM("sub-sam-supervisor", "sam.supervisor", "CARE_SUPERVISOR"),
    TARA("sub-tara-supervisor", "tara.supervisor", "CARE_SUPERVISOR"),
    ADA("sub-ada-admin", "ada.admin", "ADMIN"),
    NORA("sub-nora-norole", "nora.norole", null);

    public final String subject;
    public final String username;
    public final String role;

    TestUsers(String subject, String username, String role) {
        this.subject = subject;
        this.username = username;
        this.role = role;
    }

    public RequestPostProcessor mvc() {
        var login = oidcLogin().idToken(t -> t.subject(subject).claim("preferred_username", username));
        return role == null ? login : login.authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    public Authentication authentication() {
        var user = new DefaultOidcUser(AuthorityUtils.NO_AUTHORITIES, new OidcIdToken("test-token", Instant.EPOCH,
                Instant.EPOCH.plusSeconds(3600), Map.of("sub", subject, "preferred_username", username)));
        return new OAuth2AuthenticationToken(user, role == null ? AuthorityUtils.NO_AUTHORITIES
                : AuthorityUtils.createAuthorityList("ROLE_" + role), "keycloak");
    }
}

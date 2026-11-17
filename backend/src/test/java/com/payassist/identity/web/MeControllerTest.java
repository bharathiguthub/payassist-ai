package com.payassist.identity.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payassist.identity.security.SecurityTestConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MeController.class)
@Import(SecurityTestConfig.class)
@ActiveProfiles("test")
class MeControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void anonymousGets401() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsProfileAndOnlyPayAssistRolesWithoutTokens() throws Exception {
        mvc.perform(get("/api/v1/me").with(oidcLogin()
                        .idToken(t -> t.claim("preferred_username", "agent.alice")
                                .claim("name", "Alice Agent")
                                .claim("email", "agent.alice@example.com"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CARE_AGENT"),
                                new SimpleGrantedAuthority("SCOPE_openid"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("agent.alice"))
                .andExpect(jsonPath("$.displayName").value("Alice Agent"))
                .andExpect(jsonPath("$.roles.length()").value(1))
                .andExpect(jsonPath("$.roles[0]").value("CARE_AGENT"))
                .andExpect(jsonPath("$.idToken").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    /**
     * Regression: after a real OIDC login, mapped roles live on the authentication token while the
     * OidcUser principal keeps only the provider's authorities (OIDC_USER, SCOPE_*).
     */
    @Test
    void readsRolesFromAuthenticationNotFromPrincipal() throws Exception {
        var idToken = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("sub", "123", "preferred_username", "agent.alice"));
        var principal = new DefaultOidcUser(List.of(new OidcUserAuthority(idToken)), idToken, "preferred_username");
        var authentication = new OAuth2AuthenticationToken(principal,
                List.of(new SimpleGrantedAuthority("OIDC_USER"), new SimpleGrantedAuthority("ROLE_CARE_AGENT")),
                "keycloak");

        mvc.perform(get("/api/v1/me").with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("agent.alice"))
                .andExpect(jsonPath("$.roles[0]").value("CARE_AGENT"));
    }

    @Test
    void loggedInUserWithoutRolesStillGetsProfileWithEmptyRoles() throws Exception {
        mvc.perform(get("/api/v1/me").with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isEmpty());
    }

    @Test
    void issuesReadableCsrfCookieForTheSpa() throws Exception {
        mvc.perform(get("/api/v1/me").with(oidcLogin()))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
    }
}

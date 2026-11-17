package com.payassist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payassist.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** End-to-end security behaviour of the BFF against the full application context. */
@IntegrationTest
class SecurityIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ClientRegistrationRepository clientRegistrations;

    @Test
    void loginStartsAuthorizationCodeFlowWithPkce() throws Exception {
        var result = mvc.perform(get("/oauth2/authorization/keycloak"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith("http://localhost:8180/realms/payassist/protocol/openid-connect/auth?");
        String query = URLDecoder.decode(URI.create(location).getRawQuery(), StandardCharsets.UTF_8);
        assertThat(query)
                .contains("response_type=code")
                .contains("client_id=payassist-bff")
                .contains("code_challenge_method=S256")
                .contains("code_challenge=")
                .contains("state=")
                .contains("nonce=")
                .contains("redirect_uri=http://localhost:3000/login/oauth2/code/keycloak")
                .doesNotContain("client_secret");
    }

    @Test
    void unauthenticatedBrowserNavigationRedirectsToLogin() throws Exception {
        // Browsers send Accept: text/html; only then does Spring redirect to the IdP.
        mvc.perform(get("/swagger-ui.html").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void nonBrowserClientsGet401InsteadOfRedirect() throws Exception {
        mvc.perform(get("/swagger-ui.html").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void roleMatrixIsEnforced() throws Exception {
        // anonymous
        mvc.perform(get("/api/v1/payments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/audit-events")).andExpect(status().isUnauthorized());

        // CARE_AGENT
        mvc.perform(get("/api/v1/payments").with(as("CARE_AGENT"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/payments/stats").with(as("CARE_AGENT"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/audit-events").with(as("CARE_AGENT"))).andExpect(status().isForbidden());

        // CARE_SUPERVISOR
        mvc.perform(get("/api/v1/payments/stats").with(as("CARE_SUPERVISOR"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/audit-events").with(as("CARE_SUPERVISOR"))).andExpect(status().isForbidden());

        // ADMIN
        mvc.perform(get("/api/v1/payments").with(as("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/payments/stats").with(as("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/audit-events").with(as("ADMIN"))).andExpect(status().isOk());

        // authenticated but no PayAssist role
        mvc.perform(get("/api/v1/me").with(oidcLogin())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/payments").with(oidcLogin())).andExpect(status().isForbidden());
    }

    @Test
    void logoutRequiresCsrfToken() throws Exception {
        mvc.perform(post("/logout").with(keycloakUser("CARE_AGENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void logoutWithCsrfCookieAndHeaderEndsKeycloakSession() throws Exception {
        // 1. Any request issues the readable XSRF-TOKEN cookie (double-submit pattern).
        Cookie csrfCookie = mvc.perform(get("/api/v1/me").with(keycloakUser("CARE_AGENT")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.isHttpOnly()).isFalse();

        // 2. The SPA echoes it in the X-XSRF-TOKEN header.
        var result = mvc.perform(post("/logout")
                        .with(keycloakUser("CARE_AGENT"))
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        // 3. RP-initiated logout at Keycloak, returning to the SPA.
        String location = URLDecoder.decode(result.getResponse().getRedirectedUrl(), StandardCharsets.UTF_8);
        assertThat(location)
                .startsWith("http://localhost:8180/realms/payassist/protocol/openid-connect/logout?")
                .contains("id_token_hint=")
                .contains("post_logout_redirect_uri=http://localhost:3000/");
    }

    @Test
    void logoutFormFieldIsAcceptedForFullPageLogout() throws Exception {
        Cookie csrfCookie = mvc.perform(get("/api/v1/me").with(keycloakUser("CARE_AGENT")))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");

        mvc.perform(post("/logout")
                        .with(keycloakUser("CARE_AGENT"))
                        .cookie(csrfCookie)
                        .param("_csrf", csrfCookie.getValue()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void forgedCsrfTokenIsRejected() throws Exception {
        Cookie csrfCookie = mvc.perform(get("/api/v1/me").with(keycloakUser("CARE_AGENT")))
                .andReturn().getResponse().getCookie("XSRF-TOKEN");

        mvc.perform(post("/logout")
                        .with(keycloakUser("CARE_AGENT"))
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", "forged-token"))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor as(String role) {
        return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private RequestPostProcessor keycloakUser(String role) {
        return oidcLogin()
                .clientRegistration(clientRegistrations.findByRegistrationId("keycloak"))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}

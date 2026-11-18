package com.payassist.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.payassist.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Login and logout auditing against the real database. The OIDC round trip itself needs Keycloak and is covered by
 * {@code scripts/smoke-test.ps1}; here the success and logout handlers run with an authenticated OIDC user.
 */
@IntegrationTest
class AuthenticationAuditIT {

    @Autowired
    private AuthenticationAuditor auditor;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void loginAndLogoutAreChainedAuditRowsWithoutTokensOrClaims() throws Exception {
        var authentication = AuthenticationAuditorTest.login("sub-login-audit", "login.audit", "CARE_AGENT");
        var response = new MockHttpServletResponse();
        auditor.loginSuccessHandler("http://localhost:3000/", "http://localhost:3000/?error=login_failed")
                .onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);
        auditor.logoutHandler().logout(new MockHttpServletRequest(), new MockHttpServletResponse(), authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:3000/");
        var rows = jdbc.queryForList("""
                select action, actor, actor_type, resource_type, resource_id, outcome, details::text as details,
                       chain_seq is not null as chained
                from audit_events where actor_subject = 'sub-login-audit' order by id""");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("action", "LOGIN_SUCCESS").containsEntry("actor", "login.audit")
                .containsEntry("actor_type", "USER").containsEntry("resource_type", "AUTHENTICATION")
                .containsEntry("resource_id", "oidc:keycloak").containsEntry("outcome", "SUCCESS")
                .containsEntry("chained", true);
        assertThat((String) rows.get(0).get("details")).contains("CARE_AGENT")
                .doesNotContain("eyJ").doesNotContain("@example.test");
        assertThat(rows.get(1)).containsEntry("action", "LOGOUT");
        assertThat(rows).allSatisfy(r -> assertThat(r.values().toString()).doesNotContain("secret-id-token"));
    }
}

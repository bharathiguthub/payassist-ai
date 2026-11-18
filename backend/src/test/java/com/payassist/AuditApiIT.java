package com.payassist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payassist.support.IntegrationTest;
import com.payassist.support.TestUsers;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Audit trail API (ADR 0013): ADMIN-only search and integrity verification, both of which are themselves audited.
 * Read-only with respect to business data; it adds audit rows like every other read.
 */
@IntegrationTest
class AuditApiIT {

    private static final String BASE = "/api/v1/admin/audit-events";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void onlyAdminsCanSearchOrVerifyAndDenialsAreAudited() throws Exception {
        for (TestUsers user : new TestUsers[] {TestUsers.ALICE, TestUsers.SAM, TestUsers.NORA}) {
            mvc.perform(get(BASE).with(user.mvc())).andExpect(status().isForbidden());
            mvc.perform(get(BASE + "/integrity").with(user.mvc())).andExpect(status().isForbidden());
        }
        mvc.perform(get(BASE + "/integrity")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_events where action = 'ACCESS_DENIED' and actor_subject = ?
                and resource_id = 'GET /api/v1/admin/audit-events/integrity'""", Long.class, TestUsers.SAM.subject))
                .isPositive();
    }

    @Test
    void adminSearchIsFilteredShowsProtectionAndIsItselfAudited() throws Exception {
        mvc.perform(get("/api/v1/payments/PAY-000042/timeline").with(TestUsers.ALICE.mvc()))
                .andExpect(status().isOk());

        mvc.perform(get(BASE).with(TestUsers.ADA.mvc())
                        .param("actorSubject", TestUsers.ALICE.subject)
                        .param("action", "VIEW_TIMELINE")
                        .param("resourceId", "PAY-000042")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("VIEW_TIMELINE"))
                .andExpect(jsonPath("$.content[0].actor").value(TestUsers.ALICE.username))
                .andExpect(jsonPath("$.content[0].actorType").value("USER"))
                .andExpect(jsonPath("$.content[0].protection").value("CHAINED"))
                .andExpect(jsonPath("$.content[0].chainSeq").isNumber())
                .andExpect(jsonPath("$.content[0].rowHash").isString());

        Map<String, Object> view = jdbc.queryForMap("""
                select actor_subject, outcome, details->>'filterAction' as action,
                       details->>'filterResourceId' as resource, details->>'size' as size, chain_seq
                from audit_events where action = 'AUDIT_LOG_VIEW' order by id desc limit 1""");
        assertThat(view).containsEntry("actor_subject", TestUsers.ADA.subject).containsEntry("outcome", "SUCCESS")
                .containsEntry("action", "VIEW_TIMELINE").containsEntry("resource", "PAY-000042")
                .containsEntry("size", "5");
        assertThat(view.get("chain_seq")).isNotNull();
    }

    @Test
    void integrityCheckReportsAValidChainAndIsAudited() throws Exception {
        mvc.perform(get(BASE + "/integrity").with(TestUsers.ADA.mvc()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("VALID"))
                .andExpect(jsonPath("$.legacyRows").value(0))
                .andExpect(jsonPath("$.chainedRows").isNumber())
                .andExpect(jsonPath("$.headChainSeq").isNumber())
                .andExpect(jsonPath("$.headRowHash").isString())
                .andExpect(jsonPath("$.firstFailure").doesNotExist());

        assertThat(jdbc.queryForMap("""
                select outcome, details->>'result' as result from audit_events
                where action = 'AUDIT_INTEGRITY_CHECK' order by id desc limit 1"""))
                .containsEntry("outcome", "SUCCESS").containsEntry("result", "VALID");
    }

    @Test
    void invalidFiltersAreRejectedBeforeAnythingIsRead() throws Exception {
        mvc.perform(get(BASE).with(TestUsers.ADA.mvc()).param("action", "view; drop table"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE).with(TestUsers.ADA.mvc()).param("outcome", "MAYBE"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE).with(TestUsers.ADA.mvc()).param("actor", "jane@example.com"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE).with(TestUsers.ADA.mvc())
                        .param("from", "2026-11-16T12:00:00Z").param("to", "2026-11-16T11:00:00Z"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
        // A card number typed into the search box is refused, so it never reaches the audit trail.
        mvc.perform(get(BASE).with(TestUsers.ADA.mvc()).param("resourceId", "4111 1111 1111 1111"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PERSONAL_DATA_IN_FILTER"));
        assertThat(jdbc.queryForObject("select count(*) from audit_events where details::text like '%4111%'",
                Long.class)).isZero();
    }

    @Test
    void phaseOneOutcomeFilterTreatsNullAsSuccess() throws Exception {
        mvc.perform(get(BASE).with(TestUsers.ADA.mvc()).param("outcome", "SUCCESS").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].outcome").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("SUCCESS"))));
    }
}

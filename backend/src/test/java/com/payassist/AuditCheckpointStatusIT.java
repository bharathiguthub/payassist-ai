package com.payassist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.payassist.support.MutatingIntegrationTest;
import com.payassist.support.OwnerJdbc;
import com.payassist.support.TestUsers;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Admin audit-integrity status (ADR 0013 section 12): the in-app database-chain verification and the externally
 * reported checkpoint runs are reported separately, with warnings; ADMIN only; every view audited. "Now" is the fixed
 * test clock 2026-11-16T12:00Z; checkpoint runs are inserted as the job would (here as the owner).
 */
@MutatingIntegrationTest
class AuditCheckpointStatusIT {

    private static final String URL = "/api/v1/admin/audit-events/checkpoint-status";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OwnerJdbc owner;

    @Test
    void onlyAdminsSeeTheStatus() throws Exception {
        for (TestUsers user : List.of(TestUsers.ALICE, TestUsers.SAM, TestUsers.TARA, TestUsers.NORA)) {
            mvc.perform(get(URL).with(user.mvc())).andExpect(status().isForbidden());
        }
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void reportsBothGuaranteesSeparatelyWithWarningsAndIsAudited() throws Exception {
        // 1. Whatever ran before: a fresh, failed and then a successful recent run, newest last.
        run("2026-11-16T09:00:00Z", "VERIFY", "FAILED", "CHECKPOINT_MISMATCH", null, null);
        String body = fetchStatus();
        assertThat(codes(body)).contains("LAST_RUN_FAILED");
        assertThat(codes(body).get(0)).as("danger first").isEqualTo("LAST_RUN_FAILED");
        assertThat((String) JsonPath.read(body, "$.externalCheckpoints.lastRunReason")).isEqualTo("CHECKPOINT_MISMATCH");

        run("2026-11-16T10:00:00Z", "CREATE", "OK", null, 1L, "2026-11-16T10:00:00Z");
        body = fetchStatus();
        assertThat(codes(body)).doesNotContain("LAST_RUN_FAILED", "NO_CHECKPOINT", "CHECKPOINT_STALE",
                "NOT_VERIFIED_RECENTLY");
        assertThat((Integer) JsonPath.read(body, "$.externalCheckpoints.latestCheckpointHead")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body, "$.externalCheckpoints.rowsSinceLatestCheckpoint")).isPositive();
        assertThat((Integer) JsonPath.read(body, "$.staleAfterHours")).isEqualTo(24);

        // 2. The database chain is reported separately from the external checkpoints.
        mvc.perform(get("/api/v1/admin/audit-events/integrity").with(TestUsers.ADA.mvc())).andExpect(status().isOk());
        body = fetchStatus();
        assertThat((String) JsonPath.read(body, "$.databaseChain.lastResult")).isEqualTo("VALID");
        assertThat(codes(body)).doesNotContain("DB_CHAIN_NOT_VERIFIED", "DB_CHAIN_BROKEN");

        // 3. A newest checkpoint older than 24 hours is stale.
        run("2026-11-16T11:00:00Z", "VERIFY", "OK", null, 1L, "2026-11-14T08:00:00Z");
        assertThat(codes(fetchStatus())).contains("CHECKPOINT_STALE").doesNotContain("NOT_VERIFIED_RECENTLY");

        // 4. No key, MAC, full hash or file path is ever exposed.
        assertThat(body).doesNotContain("mac").doesNotContain("/checkpoints").doesNotContain("KEY");
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_events where action = 'AUDIT_CHECKPOINT_STATUS_VIEW' and actor_subject = ?""",
                Long.class, TestUsers.ADA.subject)).isGreaterThanOrEqualTo(4);
    }

    @Test
    void theBackendCannotWriteCheckpointRuns() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                insert into audit_checkpoint_runs (mode, result, checkpoints, appended) values ('VERIFY', 'OK', 1, false)
                """)).satisfies(e -> assertThat(((org.springframework.dao.DataAccessException) e)
                        .getMostSpecificCause().getMessage()).contains("permission denied"));
    }

    private String fetchStatus() throws Exception {
        return mvc.perform(get(URL).with(TestUsers.ADA.mvc())).andExpect(status().isOk())
                .andExpect(jsonPath("$.databaseChain.currentHeadChainSeq").isNumber())
                .andReturn().getResponse().getContentAsString();
    }

    private static List<String> codes(String body) {
        return JsonPath.read(body, "$.warnings[*].code");
    }

    private void run(String ranAt, String mode, String result, String reason, Long head, String checkpointAt) {
        owner.template().update("""
                insert into audit_checkpoint_runs (ran_at, mode, result, reason, checkpoints, appended,
                    latest_checkpoint_head, latest_checkpoint_at, db_head_chain_seq)
                values (?::timestamptz, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?)""", ranAt, mode, result, reason,
                head == null ? 0 : 1, "CREATE".equals(mode), head, checkpointAt, head);
    }
}

package com.payassist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.payassist.support.MutatingIntegrationTest;
import com.payassist.support.OwnerJdbc;
import com.payassist.support.RealCsrf;
import com.payassist.support.RefundTestPayments;
import com.payassist.support.TestUsers;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Writes refused by the database itself (ADR 0013 section 11) reach the client as stable problem codes without the
 * database message, carry the correlation ID and are audited as DATA_PROTECTION_REFUSED. Normal flows never hit these
 * layers, so the test provokes them as the schema owner (temporarily revoking a grant / adding a raising trigger) and
 * always restores the database.
 */
@MutatingIntegrationTest
class DataProtectionRefusalIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OwnerJdbc owner;

    @Test
    void aMissingGrantIsA500WithAStableCodeAndNoDatabaseDetails() throws Exception {
        String ref = createRequest(RefundTestPayments.PERMISSION_REFUSAL);
        owner.template().execute("revoke update (status) on refund_requests from payassist_app");
        String body;
        try {
            body = mvc.perform(post("/api/v1/refunds/" + ref + "/reject").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"OUTSIDE_POLICY\"}").header("X-Correlation-Id", "it-refusal-0001")
                            .with(TestUsers.SAM.mvc()).with(RealCsrf.token(mvc)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("DATABASE_PERMISSION_DENIED"))
                    .andExpect(jsonPath("$.correlationId").value("it-refusal-0001"))
                    .andReturn().getResponse().getContentAsString();
        } finally {
            owner.template().execute("grant update (status) on refund_requests to payassist_app");
        }
        assertThat(body).doesNotContainIgnoringCase("permission denied for").doesNotContain("refund_requests")
                .doesNotContain("SQL").doesNotContain("payassist_app");
        assertRefusalAudited(ref, "DATABASE_PERMISSION_DENIED");
        assertThat(jdbc.queryForObject("select status from refund_requests where refund_ref = ?", String.class, ref))
                .isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void aGuardTriggerRefusalIsA409RecordProtectedWithoutTheTriggerMessage() throws Exception {
        String ref = createRequest(RefundTestPayments.GUARD_REFUSAL);
        owner.template().execute("""
                create function it_block_refund_update() returns trigger language plpgsql as $$
                begin raise exception 'it_block_refund_update: secret internal detail %', old.refund_ref; end $$""");
        owner.template().execute("create trigger it_block_refund_update before update on refund_requests "
                + "for each row execute function it_block_refund_update()");
        String body;
        try {
            body = mvc.perform(post("/api/v1/refunds/" + ref + "/reject").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"OUTSIDE_POLICY\"}").with(TestUsers.SAM.mvc())
                            .with(RealCsrf.token(mvc)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("RECORD_PROTECTED"))
                    .andExpect(jsonPath("$.correlationId").isString())
                    .andReturn().getResponse().getContentAsString();
        } finally {
            owner.template().execute("drop trigger it_block_refund_update on refund_requests");
            owner.template().execute("drop function it_block_refund_update()");
        }
        assertThat(body).doesNotContain("secret internal detail").doesNotContain("it_block_refund_update");
        assertRefusalAudited(ref, "RECORD_PROTECTED");
    }

    private void assertRefusalAudited(String ref, String code) {
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_events where action = 'DATA_PROTECTION_REFUSED' and outcome = 'DENIED'
                and actor_subject = ? and resource_id = ? and details->>'code' = ? and chain_seq is not null""",
                Long.class, TestUsers.SAM.subject, "POST /api/v1/refunds/" + ref + "/reject", code)).isEqualTo(1);
    }

    private String createRequest(String payment) throws Exception {
        RefundTestPayments.fresh(jdbc, payment);
        String body = mvc.perform(post("/api/v1/refunds").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "it-" + UUID.randomUUID().toString().substring(0, 12))
                        .content("{\"paymentRef\":\"" + payment + "\",\"amount\":\"1.00\",\"currency\":\"INR\","
                                + "\"reason\":\"CUSTOMER_REQUEST\"}")
                        .with(TestUsers.ALICE.mvc()).with(RealCsrf.token(mvc)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.refundRef");
    }
}

package com.payassist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.payassist.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Timeline and investigation-summary APIs end to end: seed data, security, auditing. */
@IntegrationTest
class InvestigationApiIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void timelineIsChronologicalAndCarriesRecordedFacts() throws Exception {
        String ref = seedPayment("SETTLED");

        String body = mvc.perform(get("/api/v1/payments/" + ref + "/timeline").with(as("CARE_AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentRef").value(ref))
                .andExpect(jsonPath("$.currentStatus").value("SETTLED"))
                .andExpect(jsonPath("$.eventCount").value(4))
                .andExpect(jsonPath("$.events[*].eventType").value(
                        org.hamcrest.Matchers.contains("CREATED", "AUTHORIZED", "CAPTURED", "SETTLED")))
                .andExpect(jsonPath("$.events[*].sequenceNo").value(org.hamcrest.Matchers.contains(1, 2, 3, 4)))
                .andExpect(jsonPath("$.events[*].correlationId").value(everyItem(startsWith("seed-pay-"))))
                .andExpect(jsonPath("$.events[*].recordedOutOfOrder").value(everyItem(org.hamcrest.Matchers.is(false))))
                .andExpect(jsonPath("$.events[3].description").value("Funds settled to the merchant."))
                .andExpect(jsonPath("$.events[3].source").value("PROCESSOR"))
                .andReturn().getResponse().getContentAsString();

        List<String> times = JsonPath.read(body, "$.events[*].occurredAt");
        assertThat(times).extracting(Instant::parse).isSorted();
        assertThat(body).doesNotContain("actorSubject").doesNotContain("@example.com");
    }

    @Test
    void refundedPaymentTimelineShowsTheFullRefundAmount() throws Exception {
        String ref = seedPayment("REFUNDED");
        String amount = jdbc.queryForObject("select amount::text from payments where payment_ref = ?", String.class, ref);

        mvc.perform(get("/api/v1/payments/" + ref + "/timeline").with(as("CARE_SUPERVISOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[4].eventType").value("REFUND_RECORDED"))
                .andExpect(jsonPath("$.events[4].amount").value(Double.parseDouble(amount)))
                .andExpect(jsonPath("$.events[4].reasonCode").value("CUSTOMER_REQUEST"))
                .andExpect(jsonPath("$.events[4].toStatus").value("REFUNDED"));
    }

    @Test
    void summaryOfFailedPaymentStatesTheRecordedReason() throws Exception {
        String ref = seedPayment("FAILED");
        String reason = jdbc.queryForObject("select failure_reason from payments where payment_ref = ?", String.class, ref);

        mvc.perform(get("/api/v1/payments/" + ref + "/investigation-summary").with(as("CARE_AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("FAILED"))
                .andExpect(jsonPath("$.payment.terminal").value(true))
                .andExpect(jsonPath("$.payment.failureReason").value(reason))
                .andExpect(jsonPath("$.findings[0].code").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.findings[0].severity").value("WARNING"))
                .andExpect(jsonPath("$.findings[0].evidence[0]").value(2))
                .andExpect(jsonPath("$.possibleNextEvents").isEmpty())
                .andExpect(jsonPath("$.history.complete").value(true));
    }

    @Test
    void summaryOfDisputedAndRefundedPayments() throws Exception {
        String disputed = seedPayment("DISPUTED");
        mvc.perform(get("/api/v1/payments/" + disputed + "/investigation-summary").with(as("CARE_AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispute.state").value("OPEN"))
                .andExpect(jsonPath("$.dispute.reasonCode").isNotEmpty())
                .andExpect(jsonPath("$.findings[*].code").value(hasItem("DISPUTE_OPEN")))
                .andExpect(jsonPath("$.possibleNextEvents").value(
                        org.hamcrest.Matchers.containsInAnyOrder("DISPUTE_WON", "DISPUTE_LOST", "DISPUTE_WITHDRAWN")));

        String refunded = seedPayment("REFUNDED");
        mvc.perform(get("/api/v1/payments/" + refunded + "/investigation-summary").with(as("CARE_AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.money.refundCount").value(1))
                .andExpect(jsonPath("$.money.notRefundedAmount").value(0.0))
                .andExpect(jsonPath("$.findings[*].code").value(hasItem("REFUNDS_RECORDED")));
    }

    @Test
    void summaryMasksCustomerDataAndNeverContainsTheRawEmail() throws Exception {
        String body = mvc.perform(get("/api/v1/payments/PAY-000042/investigation-summary").with(as("CARE_AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.customer.maskedEmail").value("c***@example.com"))
                .andExpect(jsonPath("$.payment.customer.email").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContainPattern("customer\\d{5}@");
    }

    @Test
    void roleMatrixForInvestigationEndpoints() throws Exception {
        for (String url : List.of("/api/v1/payments/PAY-000001/timeline",
                "/api/v1/payments/PAY-000001/investigation-summary")) {
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).with(oidcLogin())).andExpect(status().isForbidden());
            mvc.perform(get(url).with(as("CARE_AGENT"))).andExpect(status().isOk());
            mvc.perform(get(url).with(as("CARE_SUPERVISOR"))).andExpect(status().isOk());
            mvc.perform(get(url).with(as("ADMIN"))).andExpect(status().isOk());
        }
    }

    @Test
    void missingPaymentIs404AndNotAudited() throws Exception {
        mvc.perform(get("/api/v1/payments/PAY-999999/timeline").with(as("CARE_AGENT")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"));
        mvc.perform(get("/api/v1/payments/PAY-999999/investigation-summary").with(as("CARE_AGENT")))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select count(*) from audit_events where resource_id = 'PAY-999999'",
                Long.class)).isZero();
    }

    @Test
    void eachInvestigationAccessIsAuditedWithActorAndCorrelationId() throws Exception {
        String ref = "PAY-000077";

        mvc.perform(get("/api/v1/payments/" + ref + "/timeline").with(as("CARE_AGENT"))
                        .header("X-Correlation-Id", "it-timeline-0001"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/payments/" + ref + "/investigation-summary").with(as("ADMIN"))
                        .header("X-Correlation-Id", "it-summary-0001"))
                .andExpect(status().isOk());

        Map<String, Object> timelineAudit = latestAudit(ref, "VIEW_TIMELINE");
        assertThat(timelineAudit).containsEntry("actor", "agent.alice")
                .containsEntry("actor_subject", "sub-care_agent")
                .containsEntry("resource_type", "PAYMENT")
                .containsEntry("correlation_id", "it-timeline-0001");
        assertThat(latestAudit(ref, "VIEW_INVESTIGATION_SUMMARY"))
                .containsEntry("actor_subject", "sub-admin")
                .containsEntry("correlation_id", "it-summary-0001");

        mvc.perform(get("/api/v1/admin/audit-events").with(as("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("VIEW_INVESTIGATION_SUMMARY"))
                .andExpect(jsonPath("$.content[0].correlationId").value("it-summary-0001"));
    }

    @Test
    void auditTrailCannotBeModified() {
        Long id = jdbc.queryForObject("select min(id) from audit_events", Long.class);
        if (id == null) {
            jdbc.update("insert into audit_events (occurred_at, actor, action, resource_type, resource_id) "
                    + "values (now(), 'system:test', 'VIEW', 'PAYMENT', 'PAY-000001')");
            id = jdbc.queryForObject("select min(id) from audit_events", Long.class);
        }
        Long auditId = id;
        assertThatThrownBy(() -> jdbc.update("update audit_events set actor = 'someone-else' where id = ?", auditId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_events where id = ?", auditId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    private Map<String, Object> latestAudit(String ref, String action) {
        return jdbc.queryForMap("""
                select actor, actor_subject, action, resource_type, correlation_id from audit_events
                where resource_id = ? and action = ? order by id desc limit 1""", ref, action);
    }

    private String seedPayment(String status) {
        return jdbc.queryForObject("select min(payment_ref) from payments where status = ?", String.class, status);
    }

    /** A user with one PayAssist role; subject "sub-<role>", display name "agent.alice" for the agent. */
    private static RequestPostProcessor as(String role) {
        String username = "CARE_AGENT".equals(role) ? "agent.alice" : role.toLowerCase();
        return oidcLogin()
                .idToken(t -> t.subject("sub-" + role.toLowerCase()).claim("preferred_username", username))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}

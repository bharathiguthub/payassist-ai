package com.payassist;

import static com.payassist.support.TestUsers.ADA;
import static com.payassist.support.TestUsers.ALICE;
import static com.payassist.support.TestUsers.NORA;
import static com.payassist.support.TestUsers.SAM;
import static com.payassist.support.TestUsers.TARA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.payassist.support.OwnerJdbc;
import com.payassist.support.DisputeTestPayments;
import com.payassist.support.MutatingIntegrationTest;
import com.payassist.support.RealCsrf;
import com.payassist.support.RefundTestPayments;
import com.payassist.support.TestUsers;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Dispute management end to end: HTTP, security filter chain, CSRF, PostgreSQL, payment ledger, refund interplay
 * and audit. Each test uses its own allocated seed payment. "Now" is 2026-11-16T12:00Z.
 */
@MutatingIntegrationTest
class DisputeWorkflowIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OwnerJdbc owner;

    @Test
    void wonDisputeRestoresThePaymentAndOnlyThenThePendingRefundCanBeApprovedOnce() throws Exception {
        String payment = DisputeTestPayments.WON_LIFECYCLE; // 706.68 GBP
        RefundTestPayments.fresh(jdbc, payment);
        String refund = refundRef(requestRefund(ALICE, payment, "100.00", "GBP").andExpect(status().isCreated()));

        String body = "{\"paymentRef\":\"" + payment + "\",\"reasonCategory\":\"PRODUCT_NOT_RECEIVED\","
                + "\"networkReasonCode\":\"13.1\",\"responseDueAt\":\"2026-12-01T00:00:00Z\"}";
        String ref = caseRef(open(ALICE, body, key())
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.disputedAmount").value(706.68))
                .andExpect(jsonPath("$.networkReasonCode").value("13.1"))
                .andExpect(jsonPath("$.overdue").value(false))
                .andExpect(jsonPath("$.pendingRefunds[*].refundRef").value(contains(refund)))
                .andExpect(jsonPath("$.allowedActions").value(contains("ADD_NOTE", "ADD_EVIDENCE", "START_INVESTIGATION"))));
        assertThat(paymentStatus(payment)).isEqualTo("DISPUTED");

        // D5: while the dispute is active, no new refunds and no approval of the pending one; its reservation stays.
        eligibility(ALICE, payment)
                .andExpect(jsonPath("$.reasons[*].code").value(contains("PAYMENT_DISPUTED")))
                .andExpect(jsonPath("$.reservedPendingAmount").value(100.0));
        requestRefund(ALICE, payment, "10.00", "GBP").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasons").value(contains("PAYMENT_DISPUTED")));
        approveRefund(SAM, refund).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REFUND_NO_LONGER_ELIGIBLE"));

        long v = version(action(ALICE, ref, "start-investigation", "{\"expectedVersion\":0}")
                .andExpect(jsonPath("$.status").value("UNDER_INVESTIGATION")));
        action(ALICE, ref, "request-review", "{\"expectedVersion\":" + v + "}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EVIDENCE_REQUIRED"));
        evidence(ALICE, ref, "{\"type\":\"PROOF_OF_DELIVERY\",\"documentReference\":\"DOC-2026-11-0042\","
                + "\"description\":\"Courier confirmation\",\"receivedAt\":\"2026-11-10T09:00:00Z\"}", key())
                .andExpect(status().isOk());
        v = version(note(ALICE, ref, "Customer says the parcel never arrived; courier shows delivered.", key())
                .andExpect(jsonPath("$.notes[0].author").value("agent.alice")));
        v = version(action(ALICE, ref, "request-review", "{\"expectedVersion\":" + v + "}")
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.reviewRequestedBy").value("agent.alice")));
        action(ALICE, ref, "approve-submission", "{\"expectedVersion\":" + v + "}").andExpect(status().isForbidden());
        v = version(action(SAM, ref, "approve-submission", "{\"expectedVersion\":" + v + "}")
                .andExpect(jsonPath("$.status").value("EVIDENCE_SUBMITTED")));
        action(TARA, ref, "record-outcome", "{\"outcome\":\"WON\",\"expectedVersion\":" + v + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WON"))
                .andExpect(jsonPath("$.allowedActions").value(empty()))
                .andExpect(jsonPath("$.history[*].eventType").value(contains("OPENED", "INVESTIGATION_STARTED",
                        "EVIDENCE_ADDED", "NOTE_ADDED", "REVIEW_REQUESTED", "SUBMISSION_APPROVED", "OUTCOME_WON")))
                .andExpect(jsonPath("$.history[0].paymentLedgerUpdated").value(true))
                .andExpect(jsonPath("$.history[6].paymentLedgerUpdated").value(true))
                .andExpect(jsonPath("$.history[6].actor").value("tara.supervisor"));
        assertThat(paymentStatus(payment)).isEqualTo("SETTLED");

        // Once the dispute is won, the preserved pending refund can be approved, exactly once.
        approveRefund(SAM, refund).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        approveRefund(TARA, refund).andExpect(status().isConflict());
        assertThat(paymentStatus(payment)).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(refundTotal(payment)).isEqualByComparingTo("100.00");
        assertThat(eventTypes(payment)).containsSubsequence("DISPUTE_OPENED", "DISPUTE_UPDATED", "DISPUTE_WON",
                "REFUND_RECORDED");
        mvc.perform(get("/api/v1/payments/" + payment + "/investigation-summary").with(ALICE.mvc()))
                .andExpect(jsonPath("$.dispute.state").value("WON"));
    }

    @Test
    void lostDisputeChargesBackAndThePendingRefundCanNeverBeApproved() throws Exception {
        String payment = DisputeTestPayments.LOST_OUTCOME; // WALLET 865.06 INR
        RefundTestPayments.fresh(jdbc, payment);
        String refund = refundRef(requestRefund(ALICE, payment, "50.00", "INR").andExpect(status().isCreated()));
        String ref = caseRef(open(ALICE, openBody(payment, "NOT_AS_DESCRIBED"), key()).andExpect(status().isCreated()));

        long v = version(action(ALICE, ref, "start-investigation", "{\"expectedVersion\":0}"));
        v = version(evidence(ALICE, ref, "{\"type\":\"CUSTOMER_COMMUNICATION\",\"documentReference\":\"CRM:4411\"}", key()));
        v = version(action(ALICE, ref, "request-review", "{\"expectedVersion\":" + v + "}"));
        v = version(action(TARA, ref, "approve-submission", "{\"expectedVersion\":" + v + "}"));
        v = version(action(SAM, ref, "record-outcome", "{\"outcome\":\"LOST\",\"expectedVersion\":" + v + "}")
                .andExpect(jsonPath("$.status").value("LOST")));
        assertThat(paymentStatus(payment)).isEqualTo("CHARGED_BACK");

        // The customer already got the money back through the network: the pending refund must never pay out.
        approveRefund(SAM, refund).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasons").value(contains("PAYMENT_CHARGED_BACK")));
        rejectRefund(TARA, refund).andExpect(status().isOk()); // a person resolves it; reservation released
        requestRefund(ALICE, payment, "1.00", "INR").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasons").value(contains("PAYMENT_CHARGED_BACK")));
        assertThat(refundTotal(payment)).isEqualByComparingTo("0");

        note(ALICE, ref, "Late note", key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_CASE_CLOSED"));
        action(TARA, ref, "withdraw", "{\"reason\":\"CUSTOMER_WITHDREW\",\"expectedVersion\":" + v + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    void separationOfDutiesUsesImmutableUserIdsAndDisputesCoverTheUnrefundedAmount() throws Exception {
        String payment = DisputeTestPayments.SEPARATION_OF_DUTIES; // 107.24 GBP
        RefundTestPayments.fresh(jdbc, payment);
        String refund = refundRef(requestRefund(ALICE, payment, "20.00", "GBP").andExpect(status().isCreated()));
        approveRefund(TARA, refund).andExpect(status().isOk());
        assertThat(paymentStatus(payment)).isEqualTo("PARTIALLY_REFUNDED");

        open(SAM, "{\"paymentRef\":\"" + payment + "\",\"reasonCategory\":\"DUPLICATE_CHARGE\","
                + "\"disputedAmount\":\"107.24\",\"currency\":\"GBP\"}", key())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DISPUTED_AMOUNT_MISMATCH"));
        String ref = caseRef(open(SAM, "{\"paymentRef\":\"" + payment + "\",\"reasonCategory\":\"DUPLICATE_CHARGE\","
                + "\"disputedAmount\":\"87.24\",\"currency\":\"GBP\"}", key())
                .andExpect(status().isCreated()).andExpect(jsonPath("$.disputedAmount").value(87.24)));

        long v = version(action(SAM, ref, "start-investigation", "{\"expectedVersion\":0}"));
        v = version(evidence(SAM, ref, "{\"type\":\"TRANSACTION_RECEIPT\",\"documentReference\":\"RCPT-1\"}", key()));
        v = version(action(SAM, ref, "request-review", "{\"expectedVersion\":" + v + "}"));
        read(SAM, ref).andExpect(jsonPath("$.allowedActions").value(not(hasItem("APPROVE_SUBMISSION"))))
                .andExpect(jsonPath("$.allowedActions").value(not(hasItem("ACCEPT_LIABILITY"))));
        read(TARA, ref).andExpect(jsonPath("$.allowedActions").value(hasItem("APPROVE_SUBMISSION")))
                .andExpect(jsonPath("$.allowedActions").value(hasItem("ACCEPT_LIABILITY")));

        action(SAM, ref, "approve-submission", "{\"expectedVersion\":" + v + "}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_REVIEW_FORBIDDEN"));
        action(SAM, ref, "accept-liability", "{\"expectedVersion\":" + v + "}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_LIABILITY_FORBIDDEN"));
        action(TARA, ref, "accept-liability", "{\"expectedVersion\":" + v + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOST"));
        assertThat(paymentStatus(payment)).isEqualTo("CHARGED_BACK");

        assertThat(jdbc.queryForList("""
                select actor_subject || '/' || outcome || '/' || coalesce(details->>'code', '-') from audit_events
                where action = 'DISPUTE_CASE_TRANSITION' and resource_id = ? order by id""", String.class, ref))
                .containsSubsequence(SAM.subject + "/DENIED/SELF_REVIEW_FORBIDDEN",
                        SAM.subject + "/DENIED/SELF_LIABILITY_FORBIDDEN", TARA.subject + "/SUCCESS/-");
    }

    @Test
    void withdrawalRestoresThePreDisputeStatusAndStaleVersionsAreRefused() throws Exception {
        String payment = DisputeTestPayments.WITHDRAWAL; // 819.95 USD
        RefundTestPayments.fresh(jdbc, payment);
        String ref = caseRef(open(ALICE, openBody(payment, "FRAUDULENT"), key()).andExpect(status().isCreated()));

        action(TARA, ref, "withdraw", "{\"reason\":\"CUSTOMER_WITHDREW\",\"expectedVersion\":9}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        action(TARA, ref, "withdraw", "{\"reason\":\"CUSTOMER_WITHDREW\",\"expectedVersion\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.history[1].reasonCode").value("CUSTOMER_WITHDREW"));
        assertThat(paymentStatus(payment)).isEqualTo("SETTLED");
        eligibility(ALICE, payment).andExpect(jsonPath("$.decision").value("ELIGIBLE"));
        assertThat(eventTypes(payment)).containsSubsequence("DISPUTE_OPENED", "DISPUTE_WITHDRAWN");
    }

    @Test
    void caseWorkIdempotencyValidationVisibilityAndAudit() throws Exception {
        String payment = DisputeTestPayments.CASE_WORK; // 220.51 USD
        RefundTestPayments.fresh(jdbc, payment);
        String key = key();
        String body = "{\"paymentRef\":\"" + payment + "\",\"reasonCategory\":\"SUBSCRIPTION_CANCELLED\","
                + "\"responseDueAt\":\"2026-11-10T00:00:00Z\"}"; // already past at the fixed test time
        String ref = caseRef(open(ALICE, body, key).andExpect(status().isCreated())
                .andExpect(jsonPath("$.overdue").value(true)));
        open(ALICE, body, key).andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.caseRef").value(ref));
        open(ALICE, openBody(payment, "OTHER"), key).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        open(SAM, openBody(payment, "OTHER"), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_ALREADY_OPEN"));
        assertThat(jdbc.queryForObject("select count(*) from dispute_cases where payment_ref = ?", Long.class, payment))
                .isEqualTo(1);

        // Notes: validated plain text, append-only, idempotent; never copied into the audit trail.
        note(ALICE, ref, "Card 4111 1111 1111 1111 mentioned on the call", key()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CARD_NUMBER_DETECTED"))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("4111"))));
        note(ALICE, ref, "Customer wrote from jane@example.com", key()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EMAIL_ADDRESS_DETECTED"));
        String noteKey = key();
        String markup = "<img src=x onerror=alert(1)> customer upset";
        note(ALICE, ref, markup, noteKey).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.notes[0].body").value(markup));
        note(ALICE, ref, markup, noteKey).andExpect(status().isOk());
        note(ALICE, ref, "different text", noteKey).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("select count(*) from dispute_notes n join dispute_cases c on c.id = n.case_id "
                + "where c.case_ref = ?", Long.class, ref)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_events where details::text like '%onerror%'",
                Long.class)).isZero();
        evidence(ALICE, ref, "{\"type\":\"OTHER\",\"documentReference\":\"DOC 1\"}", key()).andExpect(status().isBadRequest());
        evidence(ALICE, ref, "{\"type\":\"OTHER\",\"documentReference\":\"DOC-1\",\"receivedAt\":\"2027-01-01T00:00:00Z\"}",
                key()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_RECEIVED_AT"));

        // ADMIN reads everything but changes nothing; users without a role see nothing.
        read(ADA, ref).andExpect(status().isOk()).andExpect(jsonPath("$.allowedActions").value(empty()));
        note(ADA, ref, "admin note", key()).andExpect(status().isForbidden());
        open(ADA, openBody(payment, "OTHER"), key()).andExpect(status().isForbidden());
        read(NORA, ref).andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/disputes").param("overdue", "true").param("size", "100").with(SAM.mvc()))
                .andExpect(jsonPath("$.content[*].caseRef").value(hasItem(ref)));
        mvc.perform(get("/api/v1/disputes").param("paymentRef", payment).with(ADA.mvc()))
                .andExpect(jsonPath("$.content[*].caseRef").value(contains(ref)));
        mvc.perform(get("/api/v1/disputes").param("mine", "true").param("size", "100").with(TARA.mvc()))
                .andExpect(jsonPath("$.content[*].caseRef").value(not(hasItem(ref))));

        assertThat(jdbc.queryForList("""
                select action || '/' || outcome from audit_events where resource_id = ? and actor_subject = ?
                order by id""", String.class, ref, ALICE.subject))
                .contains("DISPUTE_CASE_OPEN/SUCCESS", "DISPUTE_NOTE_ADD/SUCCESS", "DISPUTE_NOTE_ADD/FAILED");
        assertThat(jdbc.queryForObject("select correlation_id is not null from audit_events where resource_id = ? "
                + "and action = 'DISPUTE_CASE_OPEN' order by id limit 1", Boolean.class, ref)).isTrue();

        // History, notes and evidence are immutable in the database: the application role has no DELETE privilege,
        // and the append-only triggers stop even the schema owner.
        for (String table : List.of("dispute_case_events", "dispute_notes")) {
            assertThatThrownBy(() -> jdbc.update("delete from " + table)).isInstanceOf(DataAccessException.class)
                    .satisfies(e -> assertThat(((DataAccessException) e).getMostSpecificCause().getMessage())
                            .contains("permission denied"));
            assertThatThrownBy(() -> owner.template().update("delete from " + table))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        }
    }

    @Test
    void onlyCapturedUnrefundedPaymentsCanBeDisputed() throws Exception {
        String refunded = jdbc.queryForObject("select min(payment_ref) from payments where status = 'REFUNDED'",
                String.class);
        open(ALICE, openBody(refunded, "OTHER"), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        String failed = jdbc.queryForObject("select min(payment_ref) from payments where status = 'FAILED'", String.class);
        open(ALICE, openBody(failed, "OTHER"), key()).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from dispute_cases where payment_ref in (?, ?)", Long.class,
                refunded, failed)).isZero();

        // A seeded DISPUTED payment already has its (backfilled) card-network case.
        String disputed = jdbc.queryForObject("select min(payment_ref) from payments where status = 'DISPUTED'",
                String.class);
        open(ALICE, openBody(disputed, "OTHER"), key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISPUTE_ALREADY_OPEN"));
        mvc.perform(get("/api/v1/payments/" + disputed + "/investigation-summary").with(ALICE.mvc()))
                .andExpect(jsonPath("$.disputeCase.status").value("OPEN"))
                .andExpect(jsonPath("$.findings[*].code").value(not(hasItem("DISPUTE_CASE_MISSING"))));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static String key() {
        return "it-" + UUID.randomUUID();
    }

    private static String openBody(String payment, String category) {
        return "{\"paymentRef\":\"" + payment + "\",\"reasonCategory\":\"" + category + "\"}";
    }

    private ResultActions open(TestUsers user, String body, String key) throws Exception {
        return mvc.perform(post("/api/v1/disputes").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Idempotency-Key", key).with(user.mvc()).with(RealCsrf.token(mvc)));
    }

    private ResultActions action(TestUsers user, String ref, String path, String body) throws Exception {
        return mvc.perform(post("/api/v1/disputes/" + ref + "/" + path).contentType(MediaType.APPLICATION_JSON)
                .content(body).with(user.mvc()).with(RealCsrf.token(mvc)));
    }

    private ResultActions note(TestUsers user, String ref, String text, String key) throws Exception {
        String body = "{\"body\":" + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(text) + "}";
        return mvc.perform(post("/api/v1/disputes/" + ref + "/notes").contentType(MediaType.APPLICATION_JSON)
                .content(body).header("Idempotency-Key", key).with(user.mvc()).with(RealCsrf.token(mvc)));
    }

    private ResultActions evidence(TestUsers user, String ref, String body, String key) throws Exception {
        return mvc.perform(post("/api/v1/disputes/" + ref + "/evidence").contentType(MediaType.APPLICATION_JSON)
                .content(body).header("Idempotency-Key", key).with(user.mvc()).with(RealCsrf.token(mvc)));
    }

    private ResultActions read(TestUsers user, String ref) throws Exception {
        return mvc.perform(get("/api/v1/disputes/" + ref).with(user.mvc()));
    }

    private ResultActions requestRefund(TestUsers user, String payment, String amount, String currency) throws Exception {
        return mvc.perform(post("/api/v1/refunds").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key())
                .content("{\"paymentRef\":\"" + payment + "\",\"amount\":\"" + amount + "\",\"currency\":\"" + currency
                        + "\",\"reason\":\"CUSTOMER_REQUEST\"}")
                .with(user.mvc()).with(RealCsrf.token(mvc)));
    }

    private ResultActions approveRefund(TestUsers user, String refund) throws Exception {
        return mvc.perform(post("/api/v1/refunds/" + refund + "/approve").with(user.mvc()).with(RealCsrf.token(mvc)));
    }

    private ResultActions rejectRefund(TestUsers user, String refund) throws Exception {
        return mvc.perform(post("/api/v1/refunds/" + refund + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"OUTSIDE_POLICY\"}").with(user.mvc()).with(RealCsrf.token(mvc)));
    }

    private ResultActions eligibility(TestUsers user, String payment) throws Exception {
        return mvc.perform(post("/api/v1/refunds/eligibility-check").contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentRef\":\"" + payment + "\"}").with(user.mvc()).with(RealCsrf.token(mvc)));
    }

    private static String caseRef(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.caseRef");
    }

    private static String refundRef(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.refundRef");
    }

    private static long version(ResultActions result) throws Exception {
        result.andExpect(status().isOk());
        Number v = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.version");
        return v.longValue();
    }

    private String paymentStatus(String payment) {
        return jdbc.queryForObject("select status from payments where payment_ref = ?", String.class, payment);
    }

    private BigDecimal refundTotal(String payment) {
        return jdbc.queryForObject("""
                select coalesce(sum(e.amount), 0) from payment_events e join payments p on p.id = e.payment_id
                where p.payment_ref = ? and e.event_type = 'REFUND_RECORDED'""", BigDecimal.class, payment);
    }

    private List<String> eventTypes(String payment) {
        return jdbc.queryForList("""
                select e.event_type from payment_events e join payments p on p.id = e.payment_id
                where p.payment_ref = ? order by e.sequence_no""", String.class, payment);
    }
}

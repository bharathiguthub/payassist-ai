package com.payassist;

import static com.payassist.support.TestUsers.ADA;
import static com.payassist.support.TestUsers.ALICE;
import static com.payassist.support.TestUsers.NORA;
import static com.payassist.support.TestUsers.SAM;
import static com.payassist.support.TestUsers.TARA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.payassist.payment.PaymentEventSource;
import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentLedger;
import com.payassist.payment.RecordPaymentEvent;
import com.payassist.shared.Actor;
import com.payassist.support.MutatingIntegrationTest;
import com.payassist.support.RealCsrf;
import com.payassist.support.RefundTestPayments;
import com.payassist.support.TestUsers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Refund request and supervisor approval workflow end to end: real HTTP, security filter chain, CSRF,
 * PostgreSQL, payment ledger and audit trail. Each test uses its own allocated seed payment.
 */
@MutatingIntegrationTest
class RefundWorkflowIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PaymentLedger ledger;

    @Test
    void partialRefundsUpToTheFullAmountAreRecordedExactlyOnce() throws Exception {
        String payment = RefundTestPayments.PARTIAL_THEN_FULL;
        BigDecimal amount = RefundTestPayments.fresh(jdbc, payment);
        String currency = RefundTestPayments.currency(jdbc, payment);
        BigDecimal first = amount.multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.DOWN);
        BigDecimal rest = amount.subtract(first);

        String r1 = refundRef(create(ALICE, payment, first, currency, "CUSTOMER_REQUEST", key())
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.eligibility.decision").value("ELIGIBLE"))
                .andExpect(jsonPath("$.requestedBy").value("agent.alice")));

        // The pending request reserves its amount: visible to the eligibility check.
        eligibility(ALICE, payment)
                .andExpect(jsonPath("$.reservedPendingAmount").value(first.doubleValue()))
                .andExpect(jsonPath("$.remainingRefundableAmount").value(rest.doubleValue()))
                .andExpect(jsonPath("$.reasons[*].code").value(contains("PENDING_REFUND_IN_PROGRESS")));

        approve(SAM, r1, "{\"expectedVersion\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decision.outcome").value("APPROVED"))
                .andExpect(jsonPath("$.decision.decidedBy").value("sam.supervisor"))
                .andExpect(jsonPath("$.decision.ledgerEventRecorded").value(true))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(paymentStatus(payment)).isEqualTo("PARTIALLY_REFUNDED");

        String r2 = refundRef(create(ALICE, payment, rest, currency, "CUSTOMER_REQUEST", key())
                .andExpect(status().isCreated()));
        approve(TARA, r2, null).andExpect(status().isOk());
        assertThat(paymentStatus(payment)).isEqualTo("REFUNDED");

        create(ALICE, payment, new BigDecimal("1.00"), currency, "CUSTOMER_REQUEST", key())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.reasons").value(contains("PAYMENT_FULLY_REFUNDED")))
                .andExpect(jsonPath("$.policyVersion").value("SYNTHETIC-REFUND-POLICY-2026.11-v1"));

        assertLedgerMatchesApprovedRequests(payment);
        assertThat(eventTypes(payment)).containsSubsequence("SETTLED", "REFUND_REQUESTED", "REFUND_RECORDED",
                "REFUND_REQUESTED", "REFUND_RECORDED");
    }

    @Test
    void idempotencyKeysReplayAndRejectDifferentPayloads() throws Exception {
        String payment = RefundTestPayments.IDEMPOTENCY;
        RefundTestPayments.fresh(jdbc, payment);
        String currency = RefundTestPayments.currency(jdbc, payment);
        String key = key();

        String ref = refundRef(create(ALICE, payment, new BigDecimal("10.00"), currency, "GOODWILL", key)
                .andExpect(status().isCreated()));
        create(ALICE, payment, new BigDecimal("10"), currency, "GOODWILL", key) // same amount at a different scale
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.refundRef").value(ref));
        create(ALICE, payment, new BigDecimal("11.00"), currency, "GOODWILL", key)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(requestCount(payment)).isEqualTo(1);

        // Keys are scoped per user: the same key from another user is a different request.
        create(SAM, payment, new BigDecimal("10.00"), currency, "GOODWILL", key).andExpect(status().isCreated());
        assertThat(requestCount(payment)).isEqualTo(2);
        assertThat(eventTypes(payment).stream().filter("REFUND_REQUESTED"::equals).count()).isEqualTo(2);
    }

    @Test
    void reservationsBlockOverRequestingAndRejectionReleasesThem() throws Exception {
        String payment = RefundTestPayments.RESERVATIONS; // 322.75 USD
        RefundTestPayments.fresh(jdbc, payment);

        String first = refundRef(create(ALICE, payment, new BigDecimal("200.00"), "USD", "NOT_AS_DESCRIBED", key())
                .andExpect(status().isCreated()));
        create(ALICE, payment, new BigDecimal("150.00"), "USD", "NOT_AS_DESCRIBED", key())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasons").value(contains("AMOUNT_EXCEEDS_REFUNDABLE", "PENDING_REFUND_IN_PROGRESS")));

        reject(TARA, first, "{\"reason\":\"INSUFFICIENT_EVIDENCE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decision.reasonCode").value("INSUFFICIENT_EVIDENCE"))
                .andExpect(jsonPath("$.decision.ledgerEventRecorded").value(false));

        create(ALICE, payment, new BigDecimal("150.00"), "USD", "NOT_AS_DESCRIBED", key())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eligibility.decision").value("ELIGIBLE"));
        assertThat(paymentStatus(payment)).isEqualTo("SETTLED");
        assertThat(eventTypes(payment)).contains("REFUND_REJECTED").doesNotContain("REFUND_RECORDED");
    }

    @Test
    void separationOfDutiesAndDoubleApprovalPrevention() throws Exception {
        String payment = RefundTestPayments.SEPARATION_OF_DUTIES;
        RefundTestPayments.fresh(jdbc, payment);
        String ref = refundRef(create(SAM, payment, new BigDecimal("20.00"), "GBP", "DUPLICATE_CHARGE", key())
                .andExpect(status().isCreated()));

        // allowedActions reflect role, status and separation of duties for the viewer.
        read(SAM, ref).andExpect(jsonPath("$.allowedActions").value(empty()));
        read(TARA, ref).andExpect(jsonPath("$.allowedActions").value(contains("APPROVE", "REJECT")));
        read(ALICE, ref).andExpect(jsonPath("$.allowedActions").value(empty()));
        read(ADA, ref).andExpect(status().isOk()).andExpect(jsonPath("$.allowedActions").value(empty()));

        approve(SAM, ref, null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"));
        reject(SAM, ref, "{\"reason\":\"OUTSIDE_POLICY\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_REJECTION_FORBIDDEN"));
        for (TestUsers user : List.of(ALICE, ADA, NORA)) {
            approve(user, ref, null).andExpect(status().isForbidden());
            reject(user, ref, "{\"reason\":\"OUTSIDE_POLICY\"}").andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/refunds/" + ref + "/approve").with(RealCsrf.token(mvc)))
                .andExpect(status().isUnauthorized());

        approve(TARA, ref, null).andExpect(status().isOk()).andExpect(jsonPath("$.decision.decidedBy").value("tara.supervisor"));
        approve(TARA, ref, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        reject(SAM, ref, "{\"reason\":\"OUTSIDE_POLICY\"}").andExpect(status().isForbidden()); // still his own
        assertThat(eventTypes(payment).stream().filter("REFUND_RECORDED"::equals).count()).isEqualTo(1);
        assertLedgerMatchesApprovedRequests(payment);

        // Audit: business denial (with code) and role denials (ACCESS_DENIED) are recorded.
        assertThat(jdbc.queryForList("""
                select actor_subject, outcome, details->>'code' as code from audit_events
                where action = 'REFUND_REQUEST_APPROVE' and resource_id = ? order by id""", ref))
                .extracting(m -> m.get("actor_subject") + "/" + m.get("outcome") + "/" + m.get("code"))
                .containsExactly(SAM.subject + "/DENIED/SELF_APPROVAL_FORBIDDEN", TARA.subject + "/SUCCESS/null",
                        TARA.subject + "/FAILED/INVALID_STATE_TRANSITION");
        assertThat(jdbc.queryForList("""
                select actor_subject from audit_events where action = 'ACCESS_DENIED' and outcome = 'DENIED'
                and resource_id = ?""", String.class, "POST /api/v1/refunds/" + ref + "/approve"))
                .contains(ALICE.subject, ADA.subject, NORA.subject);
    }

    @Test
    void aboveLimitRequestsRequireEscalationAndCannotBeApprovedByAnyone() throws Exception {
        String payment = RefundTestPayments.ESCALATION; // 2223.31 USD, limit 1250
        BigDecimal amount = RefundTestPayments.fresh(jdbc, payment);

        String ref = refundRef(create(ALICE, payment, amount, "USD", "PRODUCT_NOT_RECEIVED", key())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ESCALATION_REQUIRED"))
                .andExpect(jsonPath("$.eligibility.decision").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.eligibility.reasonCodes").value(contains("SUPERVISOR_LIMIT_EXCEEDED"))));

        read(SAM, ref).andExpect(jsonPath("$.allowedActions").value(contains("REJECT")));
        approve(SAM, ref, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ESCALATION_REQUIRED"));
        approve(TARA, ref, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ESCALATION_REQUIRED"));
        approve(ADA, ref, null).andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/refunds").param("status", "ESCALATION_REQUIRED").param("size", "100").with(ADA.mvc()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].refundRef").value(hasItem(ref)));

        reject(TARA, ref, "{\"reason\":\"ESCALATION_NOT_AVAILABLE\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(paymentStatus(payment)).isEqualTo("SETTLED");
        assertThat(eventTypes(payment)).contains("REFUND_ESCALATED", "REFUND_REJECTED").doesNotContain("REFUND_RECORDED");
    }

    @Test
    void approvalRevalidatesEligibilityAgainstCurrentPaymentState() throws Exception {
        String payment = RefundTestPayments.REVALIDATION;
        RefundTestPayments.fresh(jdbc, payment);
        String ref = refundRef(create(ALICE, payment, new BigDecimal("100.00"), "USD", "SERVICE_NOT_PROVIDED", key())
                .andExpect(status().isCreated()));

        // A chargeback arrives from the card network after the request was made.
        ledger.record(RecordPaymentEvent.of(payment, PaymentEventType.DISPUTE_OPENED,
                Actor.system("card-network-simulator"), PaymentEventSource.CARD_NETWORK).withReason("FRAUDULENT"));

        approve(SAM, ref, null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REFUND_NO_LONGER_ELIGIBLE"))
                .andExpect(jsonPath("$.reasons").value(contains("PAYMENT_DISPUTED")));
        read(SAM, ref).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        assertThat(eventTypes(payment)).doesNotContain("REFUND_RECORDED");
        assertThat(jdbc.queryForObject("""
                select outcome from audit_events where action = 'REFUND_REQUEST_APPROVE' and resource_id = ?
                order by id desc limit 1""", String.class, ref)).isEqualTo("FAILED");

        reject(SAM, ref, "{\"reason\":\"OUTSIDE_POLICY\"}").andExpect(status().isOk());
    }

    @Test
    void roleMatrixCsrfMethodRestrictionsStaleVersionsAndAudit() throws Exception {
        String payment = RefundTestPayments.ROLES; // bank transfer: full refunds only
        BigDecimal amount = RefundTestPayments.fresh(jdbc, payment);

        create(ADA, payment, amount, "EUR", "GOODWILL", key()).andExpect(status().isForbidden());
        create(NORA, payment, amount, "EUR", "GOODWILL", key()).andExpect(status().isForbidden());
        mvc.perform(createRequest(payment, amount, "EUR", "GOODWILL", key()).with(RealCsrf.token(mvc)))
                .andExpect(status().isUnauthorized());
        mvc.perform(createRequest(payment, amount, "EUR", "GOODWILL", key()).with(ALICE.mvc()))
                .andExpect(status().isForbidden()); // no CSRF token
        create(ALICE, payment, new BigDecimal("100.00"), "EUR", "GOODWILL", key())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasons").value(contains("PARTIAL_REFUND_NOT_ALLOWED_FOR_METHOD")));
        create(ALICE, payment, new BigDecimal("10.001"), "EUR", "GOODWILL", key())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT_SCALE"));

        String ref = refundRef(mvc.perform(createRequest(payment, amount, "EUR", "GOODWILL", key())
                        .header("X-Correlation-Id", "it-refund-create-0001").with(ALICE.mvc()).with(RealCsrf.token(mvc)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(amount.doubleValue()))
                .andExpect(jsonPath("$.correlationId").value("it-refund-create-0001")));

        mvc.perform(get("/api/v1/refunds").param("paymentRef", payment).with(ADA.mvc()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[*].refundRef").value(contains(ref)));
        mvc.perform(get("/api/v1/refunds").param("mine", "true").param("size", "100").with(ALICE.mvc()))
                .andExpect(jsonPath("$.content[*].refundRef").value(hasItem(ref)));
        mvc.perform(get("/api/v1/refunds").param("mine", "true").param("size", "100").with(TARA.mvc()))
                .andExpect(jsonPath("$.content[*].refundRef").value(not(hasItem(ref))));
        read(ADA, ref).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        approve(SAM, ref, "{\"expectedVersion\":5}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        reject(SAM, ref, "{\"reason\":\"CUSTOMER_WITHDREW\",\"expectedVersion\":0}").andExpect(status().isOk());

        Map<String, Object> created = jdbc.queryForMap("""
                select actor, actor_subject, outcome, correlation_id, details->>'paymentRef' as payment_ref,
                       details->>'amount' as amount from audit_events
                where action = 'REFUND_REQUEST_CREATE' and resource_id = ? order by id limit 1""", ref);
        assertThat(created).containsEntry("actor", ALICE.username).containsEntry("actor_subject", ALICE.subject)
                .containsEntry("outcome", "SUCCESS").containsEntry("correlation_id", "it-refund-create-0001")
                .containsEntry("payment_ref", payment).containsEntry("amount", amount.toPlainString());
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_events where action = 'REFUND_REQUEST_VIEW' and outcome = 'SUCCESS'
                and resource_id = ? and actor_subject = ?""", Long.class, ref, ADA.subject)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_events where action like 'REFUND_REQUEST_%'
                and (details::text like '%@%' or details::text ilike '%name%')""", Long.class)).isZero();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static String key() {
        return "it-" + UUID.randomUUID();
    }

    private MockHttpServletRequestBuilder createRequest(String payment, BigDecimal amount, String currency,
            String reason, String key) {
        return post("/api/v1/refunds").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key)
                .content("{\"paymentRef\":\"" + payment + "\",\"amount\":\"" + amount.toPlainString()
                        + "\",\"currency\":\"" + currency + "\",\"reason\":\"" + reason + "\"}");
    }

    private ResultActions create(TestUsers user, String payment, BigDecimal amount, String currency, String reason,
            String key) throws Exception {
        return mvc.perform(createRequest(payment, amount, currency, reason, key).with(user.mvc())
                .with(RealCsrf.token(mvc)));
    }

    private ResultActions approve(TestUsers user, String ref, String body) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/refunds/" + ref + "/approve");
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request.with(user.mvc()).with(RealCsrf.token(mvc)));
    }

    private ResultActions reject(TestUsers user, String ref, String body) throws Exception {
        return mvc.perform(post("/api/v1/refunds/" + ref + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content(body).with(user.mvc()).with(RealCsrf.token(mvc)));
    }

    private ResultActions read(TestUsers user, String ref) throws Exception {
        return mvc.perform(get("/api/v1/refunds/" + ref).with(user.mvc()));
    }

    private ResultActions eligibility(TestUsers user, String payment) throws Exception {
        return mvc.perform(post("/api/v1/refunds/eligibility-check").contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentRef\":\"" + payment + "\"}").with(user.mvc()).with(RealCsrf.token(mvc)));
    }

    private static String refundRef(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.refundRef");
    }

    private String paymentStatus(String payment) {
        return jdbc.queryForObject("select status from payments where payment_ref = ?", String.class, payment);
    }

    private long requestCount(String payment) {
        return jdbc.queryForObject("select count(*) from refund_requests where payment_ref = ?", Long.class, payment);
    }

    private List<String> eventTypes(String payment) {
        return jdbc.queryForList("""
                select e.event_type from payment_events e join payments p on p.id = e.payment_id
                where p.payment_ref = ? order by e.sequence_no""", String.class, payment);
    }

    /** Ledger consistency: recorded refunds are exactly the approved requests, each recorded once. */
    private void assertLedgerMatchesApprovedRequests(String payment) {
        BigDecimal ledgerTotal = jdbc.queryForObject("""
                select coalesce(sum(e.amount), 0) from payment_events e join payments p on p.id = e.payment_id
                where p.payment_ref = ? and e.event_type = 'REFUND_RECORDED'""", BigDecimal.class, payment);
        BigDecimal approvedTotal = jdbc.queryForObject("""
                select coalesce(sum(amount), 0) from refund_requests where payment_ref = ? and status = 'APPROVED'""",
                BigDecimal.class, payment);
        assertThat(ledgerTotal).isEqualByComparingTo(approvedTotal);
        assertThat(jdbc.queryForObject("""
                select count(*) from refund_decisions d join refund_requests r on r.id = d.refund_request_id
                join payment_events e on e.id = d.ledger_event_id
                where r.payment_ref = ? and d.decision = 'APPROVED' and e.event_type = 'REFUND_RECORDED'
                  and e.amount = r.amount""", Long.class, payment))
                .isEqualTo(jdbc.queryForObject("select count(*) from refund_requests where payment_ref = ? and "
                        + "status = 'APPROVED'", Long.class, payment));
        BigDecimal paymentAmount = jdbc.queryForObject("select amount from payments where payment_ref = ?",
                BigDecimal.class, payment);
        BigDecimal reserved = jdbc.queryForObject("""
                select coalesce(sum(amount), 0) from refund_requests where payment_ref = ?
                and status in ('PENDING_APPROVAL', 'ESCALATION_REQUIRED')""", BigDecimal.class, payment);
        assertThat(ledgerTotal.add(reserved)).isLessThanOrEqualTo(paymentAmount);
    }
}

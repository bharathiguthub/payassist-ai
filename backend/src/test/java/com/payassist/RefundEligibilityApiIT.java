package com.payassist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payassist.support.IntegrationTest;
import com.payassist.support.RealCsrf;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Refund eligibility end to end against the synthetic seed (authoritative PostgreSQL data), the real
 * application.yml policy, real security and auditing. "Now" is fixed at 2026-11-16T12:00Z.
 */
@IntegrationTest
class RefundEligibilityApiIT {

    private static final String URL = "/api/v1/refunds/eligibility-check";
    private static final String POLICY = "SYNTHETIC-REFUND-POLICY-2026.11-v1";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Test
    void settledCardPaymentInsideWindowAndLimitIsEligible() throws Exception {
        String ref = pick("CARD", true, false);

        check(ref, "10.00", currency(ref), "CARE_AGENT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ELIGIBLE"))
                .andExpect(jsonPath("$.reasons[*].code").value(contains("WITHIN_POLICY")))
                .andExpect(jsonPath("$.checks", hasSize(7)))
                .andExpect(jsonPath("$.requestedAmount").value(10.00))
                .andExpect(jsonPath("$.previouslyRefundedAmount").value(0.0))
                .andExpect(jsonPath("$.reservedPendingAmount").value(0.0))
                .andExpect(jsonPath("$.refundWindow.basis").value("CAPTURED_AT"))
                .andExpect(jsonPath("$.refundWindow.days").value(120))
                .andExpect(jsonPath("$.policyVersion").value(POLICY))
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.advisory").isNotEmpty());
    }

    @Test
    void responseCarriesTheRequestCorrelationId() throws Exception {
        String ref = pick("WALLET", true, false);
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content(body(ref, null, null)).header("X-Correlation-Id", "it-eligibility-0001")
                        .with(as("CARE_AGENT")).with(RealCsrf.token(mvc)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "it-eligibility-0001"))
                .andExpect(jsonPath("$.correlationId").value("it-eligibility-0001"));
    }

    @Test
    void pendingAndFailedPaymentsAreIneligible() throws Exception {
        check(seed("PENDING"), null, null, "CARE_AGENT")
                .andExpect(jsonPath("$.decision").value("INELIGIBLE"))
                .andExpect(jsonPath("$.reasons[*].code").value(contains("PAYMENT_NOT_CAPTURED")));
        check(seed("FAILED"), null, null, "CARE_AGENT")
                .andExpect(jsonPath("$.decision").value("INELIGIBLE"))
                .andExpect(jsonPath("$.reasons[*].code").value(contains("PAYMENT_FAILED")));
        check(seed("DISPUTED"), null, null, "CARE_AGENT")
                .andExpect(jsonPath("$.reasons[*].code").value(contains("PAYMENT_DISPUTED")));
    }

    @Test
    void fullyRefundedSeedPaymentShowsPreviousRefundAndNothingLeft() throws Exception {
        String ref = seed("REFUNDED");
        double amount = jdbc.queryForObject("select amount from payments where payment_ref = ?", BigDecimal.class, ref)
                .doubleValue();
        check(ref, null, null, "CARE_SUPERVISOR")
                .andExpect(jsonPath("$.decision").value("INELIGIBLE"))
                .andExpect(jsonPath("$.reasons[*].code").value(contains("PAYMENT_FULLY_REFUNDED")))
                .andExpect(jsonPath("$.previouslyRefundedAmount").value(amount))
                .andExpect(jsonPath("$.remainingRefundableAmount").value(0.0));
    }

    @Test
    void excessAmountIsIneligible() throws Exception {
        String ref = pick("CARD", true, false);
        BigDecimal tooMuch = paymentAmount(ref).add(new BigDecimal("0.01"));
        check(ref, tooMuch.toPlainString(), currency(ref), "CARE_AGENT")
                .andExpect(jsonPath("$.decision").value("INELIGIBLE"))
                .andExpect(jsonPath("$.reasons[*].code").value(contains("AMOUNT_EXCEEDS_REFUNDABLE")));
    }

    @Test
    void currencyMismatchIsIneligible() throws Exception {
        String ref = pick("CARD", true, false);
        String other = "GBP".equals(currency(ref)) ? "USD" : "GBP";
        check(ref, "10.00", other, "CARE_AGENT")
                .andExpect(jsonPath("$.decision").value("INELIGIBLE"))
                .andExpect(jsonPath("$.reasons[*].code").value(contains("CURRENCY_MISMATCH")));
    }

    @Test
    void expiredRefundWindowIsIneligible() throws Exception {
        String ref = pick("CARD", false, false);
        check(ref, "1.00", currency(ref), "CARE_AGENT")
                .andExpect(jsonPath("$.decision").value("INELIGIBLE"))
                .andExpect(jsonPath("$.reasons[*].code").value(contains("REFUND_WINDOW_EXPIRED")))
                .andExpect(jsonPath("$.refundWindow.open").value(false));
    }

    @Test
    void bankTransfersAreRestrictedToFullRefunds() throws Exception {
        String ref = pick("BANK_TRANSFER", true, false);
        check(ref, "1.00", currency(ref), "CARE_AGENT")
                .andExpect(jsonPath("$.decision").value("INELIGIBLE"))
                .andExpect(jsonPath("$.reasons[*].code").value(contains("PARTIAL_REFUND_NOT_ALLOWED_FOR_METHOD")));
        check(ref, paymentAmount(ref).toPlainString(), currency(ref), "CARE_AGENT")
                .andExpect(jsonPath("$.decision").value("ELIGIBLE"))
                .andExpect(jsonPath("$.refundWindow.days").value(60));
    }

    @Test
    void aboveSupervisorLimitNeedsManualReview() throws Exception {
        String ref = pick("CARD", true, true);
        check(ref, null, null, "CARE_AGENT")
                .andExpect(jsonPath("$.decision").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.reasons[*].code").value(contains("SUPERVISOR_LIMIT_EXCEEDED")));
    }

    @Test
    void invalidPrecisionAndUnsupportedCurrenciesAre422NeverRounded() throws Exception {
        String ref = pick("CARD", true, false);
        check(ref, "10.001", "GBP", "CARE_AGENT")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT_SCALE"));
        check(ref, "10.5", "JPY", "CARE_AGENT")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT_SCALE"));
        check(ref, "10.000", "KWD", "CARE_AGENT")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));
        check(ref, "10.00", "XYZ", "CARE_AGENT")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));
    }

    @Test
    void missingPaymentIs404AndNotAudited() throws Exception {
        check("PAY-999998", "1.00", "GBP", "CARE_AGENT").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from audit_events where resource_id = 'PAY-999998'",
                Long.class)).isZero();
    }

    @Test
    void roleMatrixAndCsrf() throws Exception {
        String ref = pick("CARD", true, false);
        String body = body(ref, "1.00", currency(ref));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body).with(RealCsrf.token(mvc)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body).with(oidcLogin()).with(RealCsrf.token(mvc)))
                .andExpect(status().isForbidden());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body).with(as("CARE_AGENT")))
                .andExpect(status().isForbidden()); // no CSRF token
        for (String role : new String[] {"CARE_AGENT", "CARE_SUPERVISOR", "ADMIN"}) {
            check(ref, "1.00", currency(ref), role).andExpect(status().isOk());
        }
    }

    @Test
    void eachCheckIsAuditedWithDecisionAndPolicyButNoCustomerData() throws Exception {
        String ref = pick("WALLET", true, false);
        long before = audits(ref);

        check(ref, "5.00", currency(ref), "CARE_SUPERVISOR").andExpect(status().isOk());

        assertThat(audits(ref)).isEqualTo(before + 1);
        Map<String, Object> row = jdbc.queryForMap("""
                select actor, actor_subject, action, resource_type, details::text as details from audit_events
                where resource_id = ? and action = 'REFUND_ELIGIBILITY_CHECK' order by id desc limit 1""", ref);
        assertThat(row).containsEntry("actor", "care_supervisor").containsEntry("actor_subject", "sub-care_supervisor")
                .containsEntry("resource_type", "PAYMENT");
        JsonNode details = json.readTree((String) row.get("details"));
        assertThat(details.get("decision").asText()).isEqualTo("ELIGIBLE");
        assertThat(details.get("policyVersion").asText()).isEqualTo(POLICY);
        assertThat(details.get("requestedAmount").asText()).isEqualTo("5.00");
        assertThat(details.get("reasonCodes").asText()).isEqualTo("WITHIN_POLICY");
        assertThat((String) row.get("details")).doesNotContain("@").doesNotContainIgnoringCase("name");

        mvc.perform(get("/api/v1/admin/audit-events").with(as("ADMIN")))
                .andExpect(jsonPath("$.content[0].action").value("REFUND_ELIGIBILITY_CHECK"))
                .andExpect(jsonPath("$.content[0].details.policyVersion").value(POLICY));
    }

    @Test
    void reservedSystemUsernameIsRefusedWith403NotAServerError() throws Exception {
        RequestPostProcessor impostor = oidcLogin()
                .idToken(t -> t.subject("sub-x").claim("preferred_username", "system:processor-simulator"))
                .authorities(new SimpleGrantedAuthority("ROLE_CARE_AGENT"));
        String ref = pick("CARD", true, false);

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(ref, null, null))
                        .with(impostor).with(RealCsrf.token(mvc)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/payments/" + ref + "/timeline").with(impostor)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from audit_events where actor like 'system:%'", Long.class))
                .isZero();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private ResultActions check(String ref, String amount, String currency, String role) throws Exception {
        return mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body(ref, amount, currency))
                .with(as(role)).with(RealCsrf.token(mvc)));
    }

    private static String body(String ref, String amount, String currency) {
        StringBuilder b = new StringBuilder("{\"paymentRef\":\"").append(ref).append('"');
        if (amount != null) {
            b.append(",\"amount\":").append(amount);
        }
        if (currency != null) {
            b.append(",\"currency\":\"").append(currency).append('"');
        }
        return b.append('}').toString();
    }

    /**
     * First SETTLED seed payment with this method whose refund window is (not) open at the fixed test time
     * and whose amount is (not) above the policy's supervisor limit for its currency.
     */
    private String pick(String method, boolean windowOpen, boolean overLimit) {
        return jdbc.queryForObject("""
                with c as (
                  select p.payment_ref, p.amount, p.currency,
                         (select min(e.occurred_at) from payment_events e
                          where e.payment_id = p.id and e.event_type = 'CAPTURED') as captured_at,
                         case p.payment_method when 'CARD' then 120 when 'WALLET' then 90 else 60 end as days,
                         case p.currency when 'GBP' then 1000 when 'USD' then 1250 when 'EUR' then 1150
                                         else 100000 end as lim
                  from payments p where p.status = 'SETTLED' and p.payment_method = ?)
                select min(payment_ref) from c
                where (captured_at + days * interval '1 day' >= timestamptz '2026-11-16 12:00:00+00') = ?
                  and (amount > lim) = ?""", String.class, method, windowOpen, overLimit);
    }

    private String seed(String status) {
        return jdbc.queryForObject("select min(payment_ref) from payments where status = ?", String.class, status);
    }

    private String currency(String ref) {
        return jdbc.queryForObject("select currency from payments where payment_ref = ?", String.class, ref);
    }

    private BigDecimal paymentAmount(String ref) {
        return jdbc.queryForObject("select amount from payments where payment_ref = ?", BigDecimal.class, ref);
    }

    private long audits(String ref) {
        return jdbc.queryForObject("select count(*) from audit_events where resource_id = ?", Long.class, ref);
    }

    private static RequestPostProcessor as(String role) {
        return oidcLogin()
                .idToken(t -> t.subject("sub-" + role.toLowerCase()).claim("preferred_username", role.toLowerCase()))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}

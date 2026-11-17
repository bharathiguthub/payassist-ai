package com.payassist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payassist.payment.PaymentEventSource;
import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentLedger;
import com.payassist.payment.RecordPaymentEvent;
import com.payassist.shared.Actor;
import com.payassist.support.MutatingIntegrationTest;
import com.payassist.support.RealCsrf;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Eligibility against payments whose refund history changes (writes via the ledger). */
@MutatingIntegrationTest
class RefundEligibilityHistoryIT {

    private static final Actor AGENT = Actor.user("sub-agent-alice", "agent.alice");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PaymentLedger ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void previouslyRefundedAmountsAreTakenFromRecordedHistory() throws Exception {
        String ref = openCardPayment(0);
        BigDecimal amount = amount(ref);
        BigDecimal first = amount.divide(new BigDecimal("4"), 2, RoundingMode.DOWN);
        refund(ref, first);
        BigDecimal remaining = amount.subtract(first);

        check(ref, remaining.toPlainString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value("PARTIALLY_REFUNDED"))
                .andExpect(jsonPath("$.decision").value("ELIGIBLE"))
                .andExpect(jsonPath("$.previouslyRefundedAmount").value(first.doubleValue()))
                .andExpect(jsonPath("$.remainingRefundableAmount").value(remaining.doubleValue()));

        check(ref, remaining.add(new BigDecimal("0.01")).toPlainString())
                .andExpect(jsonPath("$.decision").value("INELIGIBLE"))
                .andExpect(jsonPath("$.reasons[*].code").value(contains("AMOUNT_EXCEEDS_REFUNDABLE")));
    }

    @Test
    void afterTheLastPartialRefundThePaymentIsFullyRefunded() throws Exception {
        String ref = openCardPayment(1);
        BigDecimal amount = amount(ref);
        BigDecimal half = amount.divide(new BigDecimal("2"), 2, RoundingMode.DOWN);
        refund(ref, half);
        refund(ref, amount.subtract(half));

        check(ref, null)
                .andExpect(jsonPath("$.paymentStatus").value("REFUNDED"))
                .andExpect(jsonPath("$.decision").value("INELIGIBLE"))
                .andExpect(jsonPath("$.reasons[*].code").value(contains("PAYMENT_FULLY_REFUNDED")))
                .andExpect(jsonPath("$.remainingRefundableAmount").value(0.0));
    }

    @Test
    void checkingIsReadOnlyForPaymentsAndHistory() throws Exception {
        String ref = openCardPayment(2);
        long version = jdbc.queryForObject("select version from payments where payment_ref = ?", Long.class, ref);
        long events = jdbc.queryForObject("""
                select count(*) from payment_events e join payments p on p.id = e.payment_id
                where p.payment_ref = ?""", Long.class, ref);

        for (int i = 0; i < 3; i++) {
            check(ref, "1.00").andExpect(jsonPath("$.decision").value("ELIGIBLE"));
        }

        assertThat(jdbc.queryForObject("select version from payments where payment_ref = ?", Long.class, ref))
                .isEqualTo(version);
        assertThat(jdbc.queryForObject("""
                select count(*) from payment_events e join payments p on p.id = e.payment_id
                where p.payment_ref = ?""", Long.class, ref)).isEqualTo(events);
        assertThat(jdbc.queryForObject("select status from payments where payment_ref = ?", String.class, ref))
                .isEqualTo("SETTLED");
    }

    private void refund(String ref, BigDecimal amount) {
        ledger.record(RecordPaymentEvent.of(ref, PaymentEventType.REFUND_RECORDED, AGENT, PaymentEventSource.PAYASSIST)
                .withAmount(amount, currency(ref)).withReason("CUSTOMER_REQUEST"));
    }

    private ResultActions check(String ref, String amount) throws Exception {
        String body = "{\"paymentRef\":\"" + ref + "\""
                + (amount != null ? ",\"amount\":" + amount + ",\"currency\":\"" + currency(ref) + "\"" : "") + "}";
        return mvc.perform(post("/api/v1/refunds/eligibility-check").contentType(MediaType.APPLICATION_JSON)
                .content(body).with(RealCsrf.token(mvc))
                .with(oidcLogin().idToken(t -> t.subject("sub-agent-alice").claim("preferred_username", "agent.alice"))
                        .authorities(new SimpleGrantedAuthority("ROLE_CARE_AGENT"))));
    }

    /** n-th SETTLED card payment with an open window and under the supervisor limit (not used by other mutating ITs). */
    private String openCardPayment(int n) {
        return jdbc.queryForObject("""
                select p.payment_ref from payments p
                where p.status = 'SETTLED' and p.payment_method = 'CARD' and p.amount < 1000
                  and (select min(e.occurred_at) from payment_events e
                       where e.payment_id = p.id and e.event_type = 'CAPTURED') >= timestamptz '2026-07-20 00:00:00+00'
                order by p.payment_ref desc offset ? limit 1""", String.class, n);
    }

    private BigDecimal amount(String ref) {
        return jdbc.queryForObject("select amount from payments where payment_ref = ?", BigDecimal.class, ref);
    }

    private String currency(String ref) {
        return jdbc.queryForObject("select currency from payments where payment_ref = ?", String.class, ref);
    }
}

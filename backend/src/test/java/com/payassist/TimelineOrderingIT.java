package com.payassist;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payassist.payment.PaymentEventSource;
import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentLedger;
import com.payassist.payment.RecordPaymentEvent;
import com.payassist.shared.Actor;
import com.payassist.support.MutatingIntegrationTest;
import java.time.Instant;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Event ordering on the timeline when events arrive late or share a timestamp. Writes history. */
@MutatingIntegrationTest
class TimelineOrderingIT {

    private static final Actor NETWORK = Actor.system("card-network-simulator");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PaymentLedger ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void lateEventIsPlacedByOccurrenceTimeAndFlagged() throws Exception {
        // Seeded SETTLED history: CREATED t0, AUTHORIZED t0+3s, CAPTURED t0+10m, SETTLED t0+2h.
        String ref = payment("SETTLED", 10);
        Instant createdAt = createdAt(ref);
        ledger.record(RecordPaymentEvent.of(ref, PaymentEventType.DISPUTE_UPDATED, NETWORK,
                PaymentEventSource.CARD_NETWORK).occurredAt(createdAt.plusSeconds(1800)));

        mvc.perform(get("/api/v1/payments/" + ref + "/timeline").with(agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[*].eventType").value(Matchers.contains(
                        "CREATED", "AUTHORIZED", "CAPTURED", "DISPUTE_UPDATED", "SETTLED")))
                .andExpect(jsonPath("$.events[3].sequenceNo").value(5))
                .andExpect(jsonPath("$.events[3].recordedOutOfOrder").value(true))
                .andExpect(jsonPath("$.events[3].toStatus").doesNotExist())
                .andExpect(jsonPath("$.events[4].recordedOutOfOrder").value(false));

        mvc.perform(get("/api/v1/payments/" + ref + "/investigation-summary").with(agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("SETTLED"))
                .andExpect(jsonPath("$.findings[?(@.code == 'EVENTS_RECORDED_OUT_OF_ORDER')].evidence[0]").value(5));
    }

    @Test
    void eventsWithTheSameTimestampKeepRecordingOrder() throws Exception {
        String ref = payment("SETTLED", 11);
        Instant at = Instant.parse("2026-11-01T09:00:00Z");
        ledger.record(RecordPaymentEvent.of(ref, PaymentEventType.DISPUTE_UPDATED, NETWORK,
                PaymentEventSource.CARD_NETWORK).withReason("FIRST").occurredAt(at));
        ledger.record(RecordPaymentEvent.of(ref, PaymentEventType.DISPUTE_UPDATED, NETWORK,
                PaymentEventSource.CARD_NETWORK).withReason("SECOND").occurredAt(at));

        mvc.perform(get("/api/v1/payments/" + ref + "/timeline").with(agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[4].reasonCode").value("FIRST"))
                .andExpect(jsonPath("$.events[5].reasonCode").value("SECOND"))
                .andExpect(jsonPath("$.events[5].reasonDescription").value("Reason code SECOND (no description on file)."));
    }

    @Test
    void ledgerTransitionsAppearOnTheTimelineAndSummaryImmediately() throws Exception {
        String ref = payment("CAPTURED", 3);
        ledger.record(RecordPaymentEvent.of(ref, PaymentEventType.SETTLED, Actor.system("processor-simulator"),
                PaymentEventSource.PROCESSOR));

        mvc.perform(get("/api/v1/payments/" + ref + "/investigation-summary").with(agent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("SETTLED"))
                .andExpect(jsonPath("$.milestones.settledAt").isNotEmpty())
                .andExpect(jsonPath("$.findings[*].code").value(Matchers.not(Matchers.hasItem("SETTLEMENT_DELAYED"))))
                .andExpect(jsonPath("$.findings[*].code").value(Matchers.not(Matchers.hasItem("STATUS_HISTORY_MISMATCH"))));
    }

    private String payment(String status, int n) {
        return jdbc.queryForObject("select payment_ref from payments where status = ? order by payment_ref offset ? limit 1",
                String.class, status, n);
    }

    private Instant createdAt(String ref) {
        return jdbc.queryForObject("select created_at from payments where payment_ref = ?", java.sql.Timestamp.class, ref)
                .toInstant();
    }

    private static RequestPostProcessor agent() {
        return oidcLogin()
                .idToken(t -> t.subject("sub-agent-alice").claim("preferred_username", "agent.alice"))
                .authorities(new SimpleGrantedAuthority("ROLE_CARE_AGENT"));
    }
}

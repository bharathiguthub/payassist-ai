package com.payassist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payassist.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class DatabaseIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Test
    void flywayAppliedSchemaAndSyntheticSeed() {
        assertThat(jdbc.queryForObject("select count(*) from customers", Long.class)).isEqualTo(40);
        assertThat(jdbc.queryForObject("select count(*) from payments", Long.class)).isEqualTo(400);
        assertThat(jdbc.queryForObject(
                "select count(*) from customers where email not like '%@example.com'", Long.class)).isZero();
    }

    @Test
    void everySeedPaymentHasAGapFreeHistoryEndingInItsCurrentStatus() {
        assertThat(jdbc.queryForObject("""
                select count(*) from payments p
                where not exists (select 1 from payment_events e where e.payment_id = p.id)""", Long.class))
                .isZero();
        // Fresh database: V2 ran before the seed, so no legacy snapshots exist.
        assertThat(jdbc.queryForObject(
                "select count(*) from payment_events where event_type = 'LEGACY_SNAPSHOT'", Long.class)).isZero();
        // The latest status-changing event's target status equals the payment's status, for all 400.
        assertThat(jdbc.queryForObject("""
                select count(*) from payments p
                join lateral (select e.to_status from payment_events e
                              where e.payment_id = p.id and e.to_status is not null
                              order by e.occurred_at desc, e.sequence_no desc limit 1) last on true
                where last.to_status = p.status""", Long.class)).isEqualTo(400);
        // Sequence numbers run 1..n per payment.
        assertThat(jdbc.queryForObject("""
                select count(*) from (select payment_id from payment_events group by payment_id
                                      having min(sequence_no) <> 1 or max(sequence_no) <> count(*)) gaps""",
                Long.class)).isZero();
        // Every REFUNDED seed payment carries exactly its full amount as a recorded refund.
        assertThat(jdbc.queryForObject("""
                select count(*) from payments p where p.status = 'REFUNDED'
                and p.amount <> (select sum(e.amount) from payment_events e
                                 where e.payment_id = p.id and e.event_type = 'REFUND_RECORDED')""", Long.class))
                .isZero();
    }

    @Test
    void pgvectorExtensionIsAvailableForTheRagPhaseButNotYetInstalled() {
        assertThat(jdbc.queryForObject(
                "select count(*) from pg_available_extensions where name = 'vector'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from pg_extension where extname = 'vector'", Long.class)).isZero();
    }

    @Test
    void healthEndpointIsPublicAndRevealsNoDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}

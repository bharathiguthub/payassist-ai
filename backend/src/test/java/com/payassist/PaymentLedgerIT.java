package com.payassist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payassist.payment.PaymentEventSource;
import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentLedger;
import com.payassist.payment.PaymentStatus;
import com.payassist.payment.RecordPaymentEvent;
import com.payassist.shared.Actor;
import com.payassist.shared.DomainException;
import com.payassist.shared.InvalidStateTransitionException;
import com.payassist.support.MutatingIntegrationTest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The payment ledger against real PostgreSQL: row locks, the append-only trigger and committed state. */
@MutatingIntegrationTest
class PaymentLedgerIT {

    private static final Actor PROCESSOR = Actor.system("processor-simulator");
    private static final Actor CARD_NETWORK = Actor.system("card-network-simulator");
    private static final Actor AGENT = Actor.user("sub-agent-alice", "agent.alice");

    @Autowired
    private PaymentLedger ledger;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void fullLifecycleIsRecordedInOrderAndBumpsTheVersion() {
        String ref = payment("AUTHORIZED", "CARD", 0);
        long versionBefore = version(ref);
        int eventsBefore = eventCount(ref);

        record(RecordPaymentEvent.of(ref, PaymentEventType.CAPTURED, PROCESSOR, PaymentEventSource.PROCESSOR));
        record(RecordPaymentEvent.of(ref, PaymentEventType.SETTLED, PROCESSOR, PaymentEventSource.PROCESSOR));
        BigDecimal amount = paymentAmount(ref);
        BigDecimal part = amount.divide(BigDecimal.TEN, 2, RoundingMode.DOWN);
        record(RecordPaymentEvent.of(ref, PaymentEventType.REFUND_RECORDED, AGENT, PaymentEventSource.PAYASSIST)
                .withAmount(part, currency(ref)).withReason("CUSTOMER_REQUEST"));
        record(RecordPaymentEvent.of(ref, PaymentEventType.DISPUTE_OPENED, CARD_NETWORK,
                PaymentEventSource.CARD_NETWORK).withReason("FRAUDULENT"));
        var won = record(RecordPaymentEvent.of(ref, PaymentEventType.DISPUTE_WON, CARD_NETWORK,
                PaymentEventSource.CARD_NETWORK));

        assertThat(won.toStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(status(ref)).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(version(ref)).isEqualTo(versionBefore + 5);
        assertThat(won.paymentVersion()).isEqualTo(versionBefore + 5);
        assertThat(eventCount(ref)).isEqualTo(eventsBefore + 5);
        assertThat(jdbc.queryForList("""
                select e.event_type from payment_events e join payments p on p.id = e.payment_id
                where p.payment_ref = ? order by e.sequence_no desc limit 5""", String.class, ref))
                .containsExactly("DISPUTE_WON", "DISPUTE_OPENED", "REFUND_RECORDED", "SETTLED", "CAPTURED");
    }

    @Test
    void invalidTransitionChangesNothing() {
        String ref = payment("FAILED", null, 0);
        long version = version(ref);
        int events = eventCount(ref);

        assertThatThrownBy(() -> record(RecordPaymentEvent.of(ref, PaymentEventType.CAPTURED, PROCESSOR,
                PaymentEventSource.PROCESSOR))).isInstanceOf(InvalidStateTransitionException.class);

        assertThat(status(ref)).isEqualTo("FAILED");
        assertThat(version(ref)).isEqualTo(version);
        assertThat(eventCount(ref)).isEqualTo(events);
    }

    @Test
    void historyIsAppendOnlyInTheDatabase() {
        String ref = payment("SETTLED", null, 0);
        String eventId = jdbc.queryForObject("""
                select e.id::text from payment_events e join payments p on p.id = e.payment_id
                where p.payment_ref = ? order by e.sequence_no limit 1""", String.class, ref);

        assertThatThrownBy(() -> jdbc.update("update payment_events set reason_code = 'X' where id = ?::uuid", eventId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from payment_events where id = ?::uuid", eventId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        // Since V5, refund_decisions references payment_events, so PostgreSQL refuses a plain TRUNCATE even
        // before the trigger runs; with CASCADE the append-only trigger itself refuses it.
        long rows = jdbc.queryForObject("select count(*) from payment_events", Long.class);
        assertThatThrownBy(() -> jdbc.execute("truncate payment_events")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("truncate payment_events cascade"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("select count(*) from payment_events", Long.class)).isEqualTo(rows);
    }

    @Test
    void concurrentRefundsNeverExceedThePaymentAmount() throws Exception {
        String ref = payment("SETTLED", null, 1);
        BigDecimal amount = paymentAmount(ref);
        // Each refund is ~30% of the amount, so exactly 3 of 8 concurrent attempts can fit.
        BigDecimal each = amount.multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.DOWN);
        RecordPaymentEvent refund = RecordPaymentEvent.of(ref, PaymentEventType.REFUND_RECORDED, AGENT,
                PaymentEventSource.PAYASSIST).withAmount(each, currency(ref)).withReason("CUSTOMER_REQUEST");

        List<Object> outcomes = runConcurrently(8, () -> ledger.record(refund));

        assertThat(outcomes).filteredOn(o -> !(o instanceof Throwable)).hasSize(3);
        assertThat(outcomes).filteredOn(o -> o instanceof Throwable).hasSize(5)
                .allSatisfy(o -> assertThat(o).isInstanceOf(DomainException.RuleViolation.class)
                        .extracting(e -> ((DomainException) e).getCode()).isEqualTo("REFUND_EXCEEDS_PAYMENT_AMOUNT"));
        assertThat(jdbc.queryForObject("""
                select sum(e.amount) from payment_events e join payments p on p.id = e.payment_id
                where p.payment_ref = ? and e.event_type = 'REFUND_RECORDED'""", BigDecimal.class, ref))
                .isEqualByComparingTo(each.multiply(BigDecimal.valueOf(3)));
        assertThat(status(ref)).isEqualTo("PARTIALLY_REFUNDED");
        assertSequenceIsGapFree(ref);
    }

    @Test
    void concurrentWritersWithTheSameExpectedVersionOnlyOneWins() throws Exception {
        String ref = payment("SETTLED", null, 2);
        long version = version(ref);
        RecordPaymentEvent event = RecordPaymentEvent.of(ref, PaymentEventType.REFUND_REQUESTED, AGENT,
                PaymentEventSource.PAYASSIST).expectingVersion(version);

        List<Object> outcomes = runConcurrently(2, () -> ledger.record(event));

        assertThat(outcomes).filteredOn(o -> !(o instanceof Throwable)).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o instanceof Throwable).singleElement()
                .isInstanceOf(ConcurrencyFailureException.class);
        assertThat(version(ref)).isEqualTo(version + 1);
        assertSequenceIsGapFree(ref);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private com.payassist.payment.RecordedPaymentEvent record(RecordPaymentEvent command) {
        return ledger.record(command);
    }

    /** The n-th seeded payment with a status (and optional method). Each test uses its own payments. */
    private String payment(String status, String method, int n) {
        return jdbc.queryForObject("""
                select payment_ref from payments where status = ? and (?::text is null or payment_method = ?)
                order by payment_ref offset ? limit 1""", String.class, status, method, method, n);
    }

    private static List<Object> runConcurrently(int threads, Callable<?> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertSequenceIsGapFree(String ref) {
        List<Integer> sequence = jdbc.queryForList("""
                select e.sequence_no from payment_events e join payments p on p.id = e.payment_id
                where p.payment_ref = ? order by e.sequence_no""", Integer.class, ref);
        for (int i = 0; i < sequence.size(); i++) {
            assertThat(sequence.get(i)).isEqualTo(i + 1);
        }
    }

    private String status(String ref) {
        return jdbc.queryForObject("select status from payments where payment_ref = ?", String.class, ref);
    }

    private long version(String ref) {
        return jdbc.queryForObject("select version from payments where payment_ref = ?", Long.class, ref);
    }

    private BigDecimal paymentAmount(String ref) {
        return jdbc.queryForObject("select amount from payments where payment_ref = ?", BigDecimal.class, ref);
    }

    private String currency(String ref) {
        return jdbc.queryForObject("select currency from payments where payment_ref = ?", String.class, ref);
    }

    private int eventCount(String ref) {
        return jdbc.queryForObject("""
                select count(*) from payment_events e join payments p on p.id = e.payment_id
                where p.payment_ref = ?""", Integer.class, ref);
    }
}

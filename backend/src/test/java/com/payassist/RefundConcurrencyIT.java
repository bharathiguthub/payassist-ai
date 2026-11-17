package com.payassist;

import static com.payassist.support.TestUsers.ALICE;
import static com.payassist.support.TestUsers.SAM;
import static com.payassist.support.TestUsers.TARA;
import static org.assertj.core.api.Assertions.assertThat;

import com.payassist.refund.RefundApprovals;
import com.payassist.refund.RefundReason;
import com.payassist.refund.RefundRequests;
import com.payassist.refund.RefundRequests.CreatedRefundRequest;
import com.payassist.refund.RefundRequests.RequestRefundCommand;
import com.payassist.refund.RejectionReason;
import com.payassist.shared.DomainException;
import com.payassist.shared.InvalidStateTransitionException;
import com.payassist.support.MutatingIntegrationTest;
import com.payassist.support.RefundTestPayments;
import com.payassist.support.TestUsers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Concurrency of the refund workflow against real PostgreSQL row locks: many users hitting the same payment
 * or request at the same instant. Service calls run on worker threads, each with its own security context.
 */
@MutatingIntegrationTest
@SuppressWarnings("unchecked")
class RefundConcurrencyIT {

    @Autowired
    private RefundRequests refunds;

    @Autowired
    private RefundApprovals approvals;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void concurrentRequestsNeverReserveMoreThanIsRefundable() throws Exception {
        String payment = RefundTestPayments.CONCURRENT_REQUESTS;
        BigDecimal amount = RefundTestPayments.fresh(jdbc, payment);
        String currency = RefundTestPayments.currency(jdbc, payment);
        BigDecimal each = amount.multiply(new BigDecimal("0.30")).setScale(2, RoundingMode.DOWN); // 3 fit, 4 do not

        List<Object> outcomes = runConcurrently(8, i -> as(ALICE, () -> refunds.requestRefund(
                new RequestRefundCommand(payment, each, currency, RefundReason.CUSTOMER_REQUEST, "conc-" + UUID.randomUUID()))));

        assertThat(outcomes).filteredOn(CreatedRefundRequest.class::isInstance).hasSize(3);
        assertThat(outcomes).filteredOn(o -> !(o instanceof CreatedRefundRequest)).hasSize(5)
                .allSatisfy(o -> {
                    assertThat(o).isInstanceOf(DomainException.RuleViolation.class);
                    assertThat(((DomainException) o).getCode()).isEqualTo("REFUND_NOT_ELIGIBLE");
                    assertThat((List<Object>) ((DomainException) o).getProperties().get("reasons"))
                            .contains("AMOUNT_EXCEEDS_REFUNDABLE");
                });
        assertThat(jdbc.queryForObject("""
                select coalesce(sum(amount), 0) from refund_requests where payment_ref = ?
                and status in ('PENDING_APPROVAL', 'ESCALATION_REQUIRED')""", BigDecimal.class, payment))
                .isEqualByComparingTo(each.multiply(BigDecimal.valueOf(3)))
                .isLessThanOrEqualTo(amount);
        assertThat(jdbc.queryForObject("select count(*) from audit_events where action = 'REFUND_REQUEST_CREATE' "
                + "and outcome = 'FAILED' and details->>'paymentRef' = ?", Long.class, payment)).isEqualTo(5);
    }

    @Test
    void concurrentRetriesWithTheSameKeyCreateExactlyOneRequest() throws Exception {
        String payment = RefundTestPayments.CONCURRENT_SAME_KEY;
        RefundTestPayments.fresh(jdbc, payment);
        String currency = RefundTestPayments.currency(jdbc, payment);
        String key = "retry-" + UUID.randomUUID();
        var command = new RequestRefundCommand(payment, new BigDecimal("50.00"), currency, RefundReason.GOODWILL, key);

        List<Object> outcomes = runConcurrently(6, i -> as(ALICE, () -> refunds.requestRefund(command)));

        assertThat(outcomes).allSatisfy(o -> assertThat(o).isInstanceOf(CreatedRefundRequest.class));
        List<CreatedRefundRequest> created = outcomes.stream().map(CreatedRefundRequest.class::cast).toList();
        assertThat(created).extracting(c -> c.request().refundRef()).containsOnly(created.get(0).request().refundRef());
        assertThat(created).filteredOn(c -> !c.replayed()).hasSize(1);
        assertThat(created).filteredOn(CreatedRefundRequest::replayed).hasSize(5);
        assertThat(jdbc.queryForObject("select count(*) from refund_requests where payment_ref = ?", Long.class, payment))
                .isEqualTo(1);
        assertThat(eventCount(payment, "REFUND_REQUESTED")).isEqualTo(1);
    }

    @Test
    void concurrentApprovalsRecordTheRefundExactlyOnce() throws Exception {
        String payment = RefundTestPayments.CONCURRENT_APPROVALS;
        RefundTestPayments.fresh(jdbc, payment);
        String ref = createAsAlice(payment, new BigDecimal("100.00"));

        List<Object> outcomes = runConcurrently(4, i -> as(i % 2 == 0 ? SAM : TARA, () -> approvals.approve(ref, null)));

        assertThat(outcomes).filteredOn(o -> !(o instanceof Throwable)).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o instanceof Throwable).hasSize(3)
                .allSatisfy(o -> assertThat(o).isInstanceOf(InvalidStateTransitionException.class));
        assertThat(jdbc.queryForObject("""
                select count(*) from refund_decisions d join refund_requests r on r.id = d.refund_request_id
                where r.refund_ref = ?""", Long.class, ref)).isEqualTo(1);
        assertThat(eventCount(payment, "REFUND_RECORDED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from payments where payment_ref = ?", String.class, payment))
                .isEqualTo("PARTIALLY_REFUNDED");
    }

    @Test
    void approveAndRejectRaceLeavesExactlyOneConsistentDecision() throws Exception {
        String payment = RefundTestPayments.APPROVE_VS_REJECT;
        RefundTestPayments.fresh(jdbc, payment);
        String ref = createAsAlice(payment, new BigDecimal("20.00"));

        List<Object> outcomes = runConcurrently(2, i -> i == 0
                ? as(SAM, () -> approvals.approve(ref, null))
                : as(TARA, () -> approvals.reject(ref, RejectionReason.OUTSIDE_POLICY, null)));

        assertThat(outcomes).filteredOn(o -> !(o instanceof Throwable)).hasSize(1);
        String status = jdbc.queryForObject("select status from refund_requests where refund_ref = ?", String.class, ref);
        assertThat(status).isIn("APPROVED", "REJECTED");
        assertThat(eventCount(payment, "REFUND_RECORDED")).isEqualTo("APPROVED".equals(status) ? 1 : 0);
        assertThat(jdbc.queryForObject("""
                select count(*) from refund_decisions d join refund_requests r on r.id = d.refund_request_id
                where r.refund_ref = ?""", Long.class, ref)).isEqualTo(1);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private String createAsAlice(String payment, BigDecimal amount) throws Exception {
        CreatedRefundRequest created = as(ALICE, () -> refunds.requestRefund(new RequestRefundCommand(payment, amount,
                RefundTestPayments.currency(jdbc, payment), RefundReason.CUSTOMER_REQUEST, "setup-" + UUID.randomUUID())));
        return created.request().refundRef();
    }

    private static <T> T as(TestUsers user, Callable<T> call) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(user.authentication());
        try {
            return call.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    interface Task {
        Object run(int index) throws Exception;
    }

    private static List<Object> runConcurrently(int threads, Task task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.run(index);
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long eventCount(String payment, String type) {
        return jdbc.queryForObject("""
                select count(*) from payment_events e join payments p on p.id = e.payment_id
                where p.payment_ref = ? and e.event_type = ?""", Long.class, payment, type);
    }
}

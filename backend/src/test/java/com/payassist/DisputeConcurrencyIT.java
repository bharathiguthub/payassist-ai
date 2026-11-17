package com.payassist;

import static com.payassist.support.TestUsers.ALICE;
import static com.payassist.support.TestUsers.SAM;
import static com.payassist.support.TestUsers.TARA;
import static org.assertj.core.api.Assertions.assertThat;

import com.payassist.dispute.DisputeCaseWork;
import com.payassist.dispute.DisputeCaseWork.AddEvidenceCommand;
import com.payassist.dispute.DisputeCaseWork.CreatedDispute;
import com.payassist.dispute.DisputeCaseWork.OpenDisputeCommand;
import com.payassist.dispute.DisputeOutcome;
import com.payassist.dispute.DisputeReasonCategory;
import com.payassist.dispute.DisputeResolutions;
import com.payassist.dispute.DisputeView;
import com.payassist.dispute.EvidenceType;
import com.payassist.dispute.WithdrawalReason;
import com.payassist.refund.RefundApprovals;
import com.payassist.refund.RefundReason;
import com.payassist.refund.RefundRequests;
import com.payassist.refund.RefundRequests.RequestRefundCommand;
import com.payassist.shared.DomainException;
import com.payassist.support.DisputeTestPayments;
import com.payassist.support.MutatingIntegrationTest;
import com.payassist.support.RefundTestPayments;
import com.payassist.support.TestUsers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Concurrency of dispute management (and its interaction with refunds) against real PostgreSQL row locks. Service
 * calls run on worker threads, each with its own security context.
 */
@MutatingIntegrationTest
class DisputeConcurrencyIT {

    @Autowired
    private DisputeCaseWork work;
    @Autowired
    private DisputeResolutions resolutions;
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
    void concurrentOpeningCreatesExactlyOneActiveCase() throws Exception {
        String payment = DisputeTestPayments.CONCURRENT_OPEN;
        RefundTestPayments.fresh(jdbc, payment);

        List<Object> outcomes = runConcurrently(6, i -> as(i % 2 == 0 ? ALICE : SAM, () -> work.openDispute(
                new OpenDisputeCommand(payment, DisputeReasonCategory.FRAUDULENT, null, null, null, null,
                        "open-" + UUID.randomUUID()))));

        assertThat(outcomes).filteredOn(CreatedDispute.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !(o instanceof CreatedDispute)).hasSize(5)
                .allSatisfy(o -> assertThat(((DomainException) o).getCode()).isEqualTo("DISPUTE_ALREADY_OPEN"));
        assertThat(count("select count(*) from dispute_cases where payment_ref = ?", payment)).isEqualTo(1);
        assertThat(ledgerCount(payment, "DISPUTE_OPENED")).isEqualTo(1);
    }

    @Test
    void concurrentRetriesWithTheSameKeyCreateOneCase() throws Exception {
        String payment = DisputeTestPayments.CONCURRENT_SAME_KEY;
        RefundTestPayments.fresh(jdbc, payment);
        var command = new OpenDisputeCommand(payment, DisputeReasonCategory.DUPLICATE_CHARGE, "4834", null, null, null,
                "retry-" + UUID.randomUUID());

        List<Object> outcomes = runConcurrently(5, i -> as(ALICE, () -> work.openDispute(command)));

        assertThat(outcomes).allSatisfy(o -> assertThat(o).isInstanceOf(CreatedDispute.class));
        List<CreatedDispute> created = outcomes.stream().map(CreatedDispute.class::cast).toList();
        assertThat(created).extracting(c -> c.dispute().caseRef()).containsOnly(created.get(0).dispute().caseRef());
        assertThat(created).filteredOn(c -> !c.replayed()).hasSize(1);
        assertThat(count("select count(*) from dispute_cases where payment_ref = ?", payment)).isEqualTo(1);
        assertThat(ledgerCount(payment, "DISPUTE_OPENED")).isEqualTo(1);
    }

    @Test
    void concurrentResolutionsProduceExactlyOneOutcome() throws Exception {
        String payment = DisputeTestPayments.CONCURRENT_RESOLUTION;
        RefundTestPayments.fresh(jdbc, payment);
        String ref = as(ALICE, () -> work.openDispute(new OpenDisputeCommand(payment,
                DisputeReasonCategory.NOT_AS_DESCRIBED, null, null, null, null, "setup-" + UUID.randomUUID())))
                .dispute().caseRef();
        DisputeView v = as(ALICE, () -> work.startInvestigation(ref, 0));
        v = as(ALICE, () -> work.addEvidence(ref, new AddEvidenceCommand(EvidenceType.PROOF_OF_DELIVERY, "DOC-9",
                null, null, "ev-" + UUID.randomUUID())));
        long afterEvidence = v.version();
        v = as(ALICE, () -> work.requestReview(ref, afterEvidence));
        long afterReview = v.version();
        long ready = as(SAM, () -> resolutions.approveSubmission(ref, afterReview)).version();

        // Four supervisors act on the same reviewed version at the same instant.
        List<Object> outcomes = runConcurrently(4, i -> switch (i) {
            case 0 -> as(SAM, () -> resolutions.recordOutcome(ref, DisputeOutcome.WON, ready));
            case 1 -> as(TARA, () -> resolutions.recordOutcome(ref, DisputeOutcome.LOST, ready));
            case 2 -> as(SAM, () -> resolutions.withdraw(ref, WithdrawalReason.CUSTOMER_WITHDREW, ready));
            default -> as(TARA, () -> resolutions.recordOutcome(ref, DisputeOutcome.WON, ready));
        });

        assertThat(outcomes).filteredOn(DisputeView.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !(o instanceof DisputeView)).hasSize(3)
                .allSatisfy(o -> assertThat(o).isInstanceOf(ConcurrencyFailureException.class));
        String status = jdbc.queryForObject("select status from dispute_cases where case_ref = ?", String.class, ref);
        long finalLedgerEvents = ledgerCount(payment, "DISPUTE_WON") + ledgerCount(payment, "DISPUTE_LOST")
                + ledgerCount(payment, "DISPUTE_WITHDRAWN");
        assertThat(finalLedgerEvents).isEqualTo(1);
        String paymentStatus = jdbc.queryForObject("select status from payments where payment_ref = ?", String.class,
                payment);
        assertThat(Map.of("WON", "SETTLED", "LOST", "CHARGED_BACK", "WITHDRAWN", "SETTLED").get(status))
                .isEqualTo(paymentStatus);
    }

    @Test
    void refundApprovalRacingADisputeNeverPaysOutAfterTheDisputeOpened() throws Exception {
        String payment = DisputeTestPayments.REFUND_VS_DISPUTE_RACE; // 808.92 GBP
        BigDecimal amount = RefundTestPayments.fresh(jdbc, payment);
        String refundRef = as(ALICE, () -> refunds.requestRefund(new RequestRefundCommand(payment,
                new BigDecimal("100.00"), "GBP", RefundReason.CUSTOMER_REQUEST, "race-" + UUID.randomUUID())))
                .request().refundRef();

        List<Object> outcomes = runConcurrently(2, i -> i == 0
                ? as(SAM, () -> approvals.approve(refundRef, null))
                : as(ALICE, () -> work.openDispute(new OpenDisputeCommand(payment, DisputeReasonCategory.FRAUDULENT,
                        null, null, null, null, "race-open-" + UUID.randomUUID()))));

        assertThat(outcomes.get(1)).isInstanceOf(CreatedDispute.class); // a dispute can always be opened here
        BigDecimal disputed = ((CreatedDispute) outcomes.get(1)).dispute().disputedAmount();
        BigDecimal refunded = jdbc.queryForObject("""
                select coalesce(sum(e.amount), 0) from payment_events e join payments p on p.id = e.payment_id
                where p.payment_ref = ? and e.event_type = 'REFUND_RECORDED'""", BigDecimal.class, payment);
        if (outcomes.get(0) instanceof Exception refused) {
            // Dispute won the lock: approval re-validated against DISPUTED and refused; nothing refunded.
            assertThat(((DomainException) refused).getCode()).isEqualTo("REFUND_NO_LONGER_ELIGIBLE");
            assertThat(refunded).isEqualByComparingTo("0");
            assertThat(disputed).isEqualByComparingTo(amount);
        } else {
            // Refund won the lock: the dispute covers exactly what was left.
            assertThat(refunded).isEqualByComparingTo("100.00");
            assertThat(disputed).isEqualByComparingTo(amount.subtract(new BigDecimal("100.00")));
            Integer refundSeq = jdbc.queryForObject("""
                    select e.sequence_no from payment_events e join payments p on p.id = e.payment_id
                    where p.payment_ref = ? and e.event_type = 'REFUND_RECORDED'""", Integer.class, payment);
            Integer disputeSeq = jdbc.queryForObject("""
                    select e.sequence_no from payment_events e join payments p on p.id = e.payment_id
                    where p.payment_ref = ? and e.event_type = 'DISPUTE_OPENED'""", Integer.class, payment);
            assertThat(refundSeq).isLessThan(disputeSeq);
        }
        // In both orders: refunds + disputed amount never exceed the payment amount.
        assertThat(refunded.add(disputed)).isLessThanOrEqualTo(amount);
    }

    // --- helpers ---------------------------------------------------------------------------------

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

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long ledgerCount(String payment, String type) {
        return count("""
                select count(*) from payment_events e join payments p on p.id = e.payment_id
                where p.payment_ref = ? and e.event_type = ?""", payment, type);
    }
}

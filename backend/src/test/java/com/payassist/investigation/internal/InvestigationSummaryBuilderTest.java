package com.payassist.investigation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.payassist.customer.CustomerSummary;
import com.payassist.investigation.InvestigationSummary;
import com.payassist.investigation.InvestigationSummary.DisputeState;
import com.payassist.investigation.InvestigationSummary.Finding;
import com.payassist.investigation.InvestigationSummary.FindingCode;
import com.payassist.investigation.InvestigationSummary.Severity;
import com.payassist.payment.PaymentEventSource;
import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentHistory.PaymentSnapshot;
import com.payassist.payment.PaymentHistory.PaymentTimeline;
import com.payassist.payment.PaymentHistory.TimelineEntry;
import com.payassist.payment.PaymentMethod;
import com.payassist.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvestigationSummaryBuilderTest {

    private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

    private final InvestigationSummaryBuilder builder = new InvestigationSummaryBuilder(InvestigationProperties.defaults());

    @Test
    void settledPaymentHasMilestonesAndNoFindings() {
        var timeline = timeline(PaymentStatus.SETTLED, null,
                lifecycle(1, PaymentEventType.CREATED, null, PaymentStatus.PENDING, 0),
                lifecycle(2, PaymentEventType.AUTHORIZED, PaymentStatus.PENDING, PaymentStatus.AUTHORIZED, 1),
                lifecycle(3, PaymentEventType.CAPTURED, PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED, 10),
                lifecycle(4, PaymentEventType.SETTLED, PaymentStatus.CAPTURED, PaymentStatus.SETTLED, 120));

        InvestigationSummary summary = builder.build(timeline, NOW);

        assertThat(summary.schemaVersion()).isEqualTo("1.0");
        assertThat(summary.basis()).contains("No inference");
        assertThat(summary.findings()).isEmpty();
        assertThat(summary.milestones().authorizedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(summary.milestones().capturedAt()).isEqualTo(T0.plusSeconds(600));
        assertThat(summary.milestones().settledAt()).isEqualTo(T0.plusSeconds(7200));
        assertThat(summary.milestones().failedAt()).isNull();
        assertThat(summary.money().refundedAmount()).isEqualByComparingTo("0").hasToString("0.00");
        assertThat(summary.money().notRefundedAmount()).isEqualByComparingTo("100.00");
        assertThat(summary.dispute().state()).isEqualTo(DisputeState.NONE);
        assertThat(summary.history().complete()).isTrue();
        assertThat(summary.history().eventCount()).isEqualTo(4);
    }

    @Test
    void sameInputAlwaysProducesTheSameSummary() {
        var timeline = timeline(PaymentStatus.PENDING, null,
                lifecycle(1, PaymentEventType.CREATED, null, PaymentStatus.PENDING, 0));
        assertThat(builder.build(timeline, NOW)).isEqualTo(builder.build(timeline, NOW));
    }

    @Test
    void failedPaymentCitesReasonAndEvidence() {
        var timeline = timeline(PaymentStatus.FAILED, "ISSUER_DECLINED",
                lifecycle(1, PaymentEventType.CREATED, null, PaymentStatus.PENDING, 0),
                lifecycle(2, PaymentEventType.FAILED, PaymentStatus.PENDING, PaymentStatus.FAILED, 1));

        Finding finding = only(builder.build(timeline, NOW), FindingCode.PAYMENT_FAILED);

        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.message())
                .isEqualTo("Payment failed with reason ISSUER_DECLINED: The card issuer declined the payment.");
        assertThat(finding.evidence()).containsExactly(2);
    }

    @Test
    void pendingIsFlaggedOnlyAfterTheThreshold() {
        var created = lifecycle(1, PaymentEventType.CREATED, null, PaymentStatus.PENDING, 0);
        var timeline = timeline(PaymentStatus.PENDING, null, created);

        assertThat(builder.build(timeline, T0.plus(Duration.ofHours(24))).findings()).isEmpty();
        Finding finding = only(builder.build(timeline, T0.plus(Duration.ofHours(49))),
                FindingCode.PENDING_LONGER_THAN_EXPECTED);
        assertThat(finding.message()).isEqualTo("Payment has been PENDING for 2 days (since 2026-11-01T10:00:00Z), "
                + "longer than the 1 day threshold.");
    }

    @Test
    void uncapturedAuthorizationAndDelayedSettlementAreFlagged() {
        var authorized = timeline(PaymentStatus.AUTHORIZED, null,
                lifecycle(1, PaymentEventType.CREATED, null, PaymentStatus.PENDING, 0),
                lifecycle(2, PaymentEventType.AUTHORIZED, PaymentStatus.PENDING, PaymentStatus.AUTHORIZED, 1));
        assertThat(only(builder.build(authorized, NOW), FindingCode.AUTHORIZED_NOT_CAPTURED).evidence())
                .containsExactly(2);

        var captured = timeline(PaymentStatus.CAPTURED, null,
                lifecycle(1, PaymentEventType.CAPTURED, PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED, 0));
        assertThat(builder.build(captured, T0.plus(Duration.ofDays(2))).findings()).isEmpty();
        assertThat(only(builder.build(captured, NOW), FindingCode.SETTLEMENT_DELAYED).severity())
                .isEqualTo(Severity.WARNING);
    }

    @Test
    void refundsAreTotalledFromRecordedEvents() {
        var timeline = timeline(PaymentStatus.PARTIALLY_REFUNDED, null,
                lifecycle(1, PaymentEventType.SETTLED, PaymentStatus.CAPTURED, PaymentStatus.SETTLED, 0),
                refund(2, "30.00", PaymentStatus.PARTIALLY_REFUNDED, 60),
                refund(3, "20.50", PaymentStatus.PARTIALLY_REFUNDED, 120));

        InvestigationSummary summary = builder.build(timeline, NOW);

        assertThat(summary.money().refundedAmount()).isEqualByComparingTo("50.50");
        assertThat(summary.money().notRefundedAmount()).isEqualByComparingTo("49.50");
        assertThat(summary.money().refundCount()).isEqualTo(2);
        assertThat(summary.milestones().lastRefundAt()).isEqualTo(T0.plusSeconds(7200));
        Finding finding = only(summary, FindingCode.REFUNDS_RECORDED);
        assertThat(finding.message())
                .isEqualTo("2 refund(s) totalling 50.50 GBP recorded; 49.50 GBP of 100.00 GBP has not been refunded.");
        assertThat(finding.evidence()).containsExactly(2, 3);
    }

    @Test
    void openDisputeReportsReasonAndOpeningEvent() {
        var opened = new TimelineEntry(5, PaymentEventType.DISPUTE_OPENED, PaymentEventType.Kind.LIFECYCLE, "",
                PaymentStatus.SETTLED, PaymentStatus.DISPUTED, null, null, "FRAUDULENT", "desc",
                "system:card-network-simulator", PaymentEventSource.CARD_NETWORK, null, T0, T0, false);
        InvestigationSummary summary = builder.build(timeline(PaymentStatus.DISPUTED, null,
                lifecycle(4, PaymentEventType.SETTLED, PaymentStatus.CAPTURED, PaymentStatus.SETTLED, -60), opened), NOW);

        assertThat(summary.dispute().state()).isEqualTo(DisputeState.OPEN);
        assertThat(summary.dispute().reasonCode()).isEqualTo("FRAUDULENT");
        Finding finding = only(summary, FindingCode.DISPUTE_OPEN);
        assertThat(finding.message()).isEqualTo("A dispute is open since 2026-11-01T10:00:00Z with reason FRAUDULENT.");
        assertThat(finding.evidence()).containsExactly(5);
    }

    @Test
    void lostDisputeIsAChargebackFinding() {
        InvestigationSummary summary = builder.build(timeline(PaymentStatus.CHARGED_BACK, null,
                lifecycle(1, PaymentEventType.DISPUTE_OPENED, PaymentStatus.SETTLED, PaymentStatus.DISPUTED, 0),
                lifecycle(2, PaymentEventType.DISPUTE_LOST, PaymentStatus.DISPUTED, PaymentStatus.CHARGED_BACK, 60)), NOW);

        assertThat(summary.dispute().state()).isEqualTo(DisputeState.LOST);
        assertThat(summary.dispute().closedAt()).isEqualTo(T0.plusSeconds(3600));
        assertThat(only(summary, FindingCode.CHARGEBACK_RECORDED).severity()).isEqualTo(Severity.WARNING);
    }

    @Test
    void migratedHistoryAndOutOfOrderEventsAreDisclosed() {
        var snapshot = new TimelineEntry(1, PaymentEventType.LEGACY_SNAPSHOT, PaymentEventType.Kind.SYSTEM_ONLY, "",
                null, PaymentStatus.SETTLED, null, null, null, null, "system:migration", PaymentEventSource.MIGRATION,
                null, T0.plusSeconds(7200), T0, false);
        var lateSettled = new TimelineEntry(2, PaymentEventType.SETTLED, PaymentEventType.Kind.LIFECYCLE, "",
                PaymentStatus.CAPTURED, PaymentStatus.SETTLED, null, null, null, null, "system:processor-simulator",
                PaymentEventSource.PROCESSOR, null, T0, T0, true);

        InvestigationSummary summary = builder.build(timeline(PaymentStatus.SETTLED, null, lateSettled, snapshot), NOW);

        assertThat(summary.history().complete()).isFalse();
        assertThat(summary.history().recordedFrom()).isEqualTo(T0);
        assertThat(only(summary, FindingCode.HISTORY_INCOMPLETE).evidence()).containsExactly(1);
        assertThat(only(summary, FindingCode.EVENTS_RECORDED_OUT_OF_ORDER).evidence()).containsExactly(2);
        assertThat(summary.findings()).noneMatch(f -> f.code() == FindingCode.STATUS_HISTORY_MISMATCH);
    }

    @Test
    void statusThatHistoryDoesNotExplainIsADataIntegrityWarning() {
        InvestigationSummary summary = builder.build(timeline(PaymentStatus.REFUNDED, null,
                lifecycle(1, PaymentEventType.SETTLED, PaymentStatus.CAPTURED, PaymentStatus.SETTLED, 0)), NOW);

        Finding finding = only(summary, FindingCode.STATUS_HISTORY_MISMATCH);
        assertThat(finding.message()).isEqualTo(
                "Recorded history ends in SETTLED but the payment status is REFUNDED. Escalate as a data-integrity issue.");
        assertThat(finding.evidence()).containsExactly(1);
    }

    @Test
    void warningsAreListedBeforeInformation() {
        var snapshot = new TimelineEntry(1, PaymentEventType.LEGACY_SNAPSHOT, PaymentEventType.Kind.SYSTEM_ONLY, "",
                null, PaymentStatus.FAILED, null, null, null, null, "system:migration", PaymentEventSource.MIGRATION,
                null, T0, T0, false);
        InvestigationSummary summary = builder.build(timeline(PaymentStatus.FAILED, null, snapshot), NOW);

        assertThat(summary.findings()).extracting(Finding::code)
                .containsExactly(FindingCode.PAYMENT_FAILED, FindingCode.HISTORY_INCOMPLETE);
        assertThat(summary.findings().get(0).message()).isEqualTo("Payment failed; no reason code was recorded.");
    }

    @Test
    void humanDurations() {
        assertThat(InvestigationSummaryBuilder.human(Duration.ofMinutes(1))).isEqualTo("1 minute");
        assertThat(InvestigationSummaryBuilder.human(Duration.ofMinutes(59))).isEqualTo("59 minutes");
        assertThat(InvestigationSummaryBuilder.human(Duration.ofHours(23))).isEqualTo("23 hours");
        assertThat(InvestigationSummaryBuilder.human(Duration.ofDays(1))).isEqualTo("1 day");
    }

    // --- fixtures --------------------------------------------------------------------------------

    private static PaymentTimeline timeline(PaymentStatus status, String failureReason, TimelineEntry... entries) {
        List<TimelineEntry> chronological = new ArrayList<>(List.of(entries));
        chronological.sort((a, b) -> a.occurredAt().equals(b.occurredAt())
                ? Integer.compare(a.sequenceNo(), b.sequenceNo()) : a.occurredAt().compareTo(b.occurredAt()));
        var customer = new CustomerSummary(UUID.randomUUID(), "CUS-00001", "Riley Testwell", "c***@example.com", "GB");
        var snapshot = new PaymentSnapshot("PAY-000001", new BigDecimal("100.00"), "GBP", status, "status text",
                status.isTerminal(), Set.of(), PaymentMethod.CARD, "Demo Travel Co", failureReason,
                failureReason != null ? "The card issuer declined the payment." : null, T0, T0, 3, customer);
        return new PaymentTimeline(snapshot, chronological);
    }

    private static TimelineEntry lifecycle(int seq, PaymentEventType type, PaymentStatus from, PaymentStatus to,
            int minutesAfterT0) {
        return new TimelineEntry(seq, type, type.kind(), "", from, to, null, null, null, null,
                "system:processor-simulator", PaymentEventSource.PROCESSOR, null, T0.plusSeconds(60L * minutesAfterT0),
                T0, false);
    }

    private static TimelineEntry refund(int seq, String amount, PaymentStatus to, int minutesAfterT0) {
        return new TimelineEntry(seq, PaymentEventType.REFUND_RECORDED, PaymentEventType.Kind.LIFECYCLE, "",
                PaymentStatus.SETTLED, to, new BigDecimal(amount), "GBP", "CUSTOMER_REQUEST", null, "agent.alice",
                PaymentEventSource.PAYASSIST, null, T0.plusSeconds(60L * minutesAfterT0), T0, false);
    }

    private static Finding only(InvestigationSummary summary, FindingCode code) {
        return summary.findings().stream().filter(f -> f.code() == code).reduce((a, b) -> {
            throw new AssertionError("more than one " + code);
        }).orElseThrow(() -> new AssertionError("no " + code + " in " + summary.findings()));
    }
}

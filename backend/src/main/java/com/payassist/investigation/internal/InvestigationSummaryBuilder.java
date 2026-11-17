package com.payassist.investigation.internal;

import com.payassist.investigation.InvestigationSummary;
import com.payassist.investigation.InvestigationSummary.DisputeFacts;
import com.payassist.investigation.InvestigationSummary.DisputeState;
import com.payassist.investigation.InvestigationSummary.Finding;
import com.payassist.investigation.InvestigationSummary.FindingCode;
import com.payassist.investigation.InvestigationSummary.HistoryCoverage;
import com.payassist.investigation.InvestigationSummary.Milestones;
import com.payassist.investigation.InvestigationSummary.MoneyFacts;
import com.payassist.investigation.InvestigationSummary.PaymentFacts;
import com.payassist.investigation.InvestigationSummary.Severity;
import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentHistory.PaymentSnapshot;
import com.payassist.payment.PaymentHistory.PaymentTimeline;
import com.payassist.payment.PaymentHistory.TimelineEntry;
import com.payassist.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Turns a payment timeline into an {@link InvestigationSummary}. A pure function of its inputs (the
 * timeline and "now"): no I/O, no randomness, no generated text, so the same data always yields the
 * same summary and every rule can be unit-tested in isolation.
 */
public final class InvestigationSummaryBuilder {

    private final InvestigationProperties thresholds;

    public InvestigationSummaryBuilder(InvestigationProperties thresholds) {
        this.thresholds = Objects.requireNonNull(thresholds);
    }

    public InvestigationSummary build(PaymentTimeline timeline, Instant now) {
        return build(timeline, null, now);
    }

    /** @param disputeCase the active dispute case, or {@code null} if there is none */
    public InvestigationSummary build(PaymentTimeline timeline, InvestigationSummary.DisputeCaseFacts disputeCase,
            Instant now) {
        PaymentSnapshot p = timeline.payment();
        List<TimelineEntry> events = timeline.events(); // chronological

        List<TimelineEntry> refunds = all(events, PaymentEventType.REFUND_RECORDED);
        BigDecimal refunded = refunds.stream().map(TimelineEntry::amount).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO.setScale(p.amount().scale()), BigDecimal::add);
        MoneyFacts money = new MoneyFacts(p.currency(), p.amount(), refunded, p.amount().subtract(refunded),
                refunds.size());

        Optional<TimelineEntry> disputeOpened = last(events, PaymentEventType.DISPUTE_OPENED);
        Optional<TimelineEntry> disputeClosed = last(events,
                e -> (e.eventType() == PaymentEventType.DISPUTE_WON || e.eventType() == PaymentEventType.DISPUTE_LOST
                        || e.eventType() == PaymentEventType.DISPUTE_WITHDRAWN)
                        && disputeOpened.map(o -> !e.occurredAt().isBefore(o.occurredAt())).orElse(true));
        DisputeFacts dispute = disputeFacts(p, disputeOpened, disputeClosed);

        Milestones milestones = new Milestones(p.createdAt(),
                firstAt(events, PaymentEventType.AUTHORIZED), firstAt(events, PaymentEventType.CAPTURED),
                firstAt(events, PaymentEventType.SETTLED), firstAt(events, PaymentEventType.FAILED),
                firstAt(events, PaymentEventType.VOIDED),
                refunds.isEmpty() ? null : refunds.get(refunds.size() - 1).occurredAt(),
                dispute.openedAt(), dispute.closedAt());

        boolean complete = all(events, PaymentEventType.LEGACY_SNAPSHOT).isEmpty();
        HistoryCoverage history = new HistoryCoverage(events.size(), complete,
                events.isEmpty() ? null : events.get(0).occurredAt());

        PaymentFacts facts = new PaymentFacts(p.amount(), p.currency(), p.status(), p.statusDescription(),
                p.terminal(), p.paymentMethod(), p.merchantName(), p.failureReason(), p.failureDescription(),
                p.createdAt(), p.updatedAt(), p.customer());

        List<Finding> findings = findings(p, events, money, dispute, disputeOpened, now);
        findings.addAll(disputeCaseFindings(p, disputeCase));
        findings.sort(Comparator.comparing(Finding::severity).thenComparing(Finding::code));

        return new InvestigationSummary(InvestigationSummary.SCHEMA_VERSION, p.paymentRef(), now, p.version(),
                InvestigationSummary.BASIS, facts, milestones, money, dispute, history, List.copyOf(findings),
                p.nextLifecycleEvents(), disputeCase);
    }

    private List<Finding> findings(PaymentSnapshot p, List<TimelineEntry> events, MoneyFacts money,
            DisputeFacts dispute, Optional<TimelineEntry> disputeOpened, Instant now) {
        List<Finding> findings = new ArrayList<>();
        // The status-changing event applied last (highest sequence number) explains the current status.
        Optional<TimelineEntry> lastStatusEvent = events.stream().filter(e -> e.toStatus() != null)
                .max(Comparator.comparingInt(TimelineEntry::sequenceNo));
        List<Integer> statusEvidence = lastStatusEvent.map(e -> List.of(e.sequenceNo())).orElse(List.of());
        Instant inStatusSince = lastStatusEvent.map(TimelineEntry::occurredAt).orElse(p.updatedAt());

        switch (p.status()) {
            case FAILED -> findings.add(new Finding(FindingCode.PAYMENT_FAILED, Severity.WARNING,
                    p.failureReason() != null
                            ? "Payment failed with reason " + p.failureReason() + ": " + p.failureDescription()
                            : "Payment failed; no reason code was recorded.",
                    statusEvidence));
            case PENDING -> ageFinding(findings, FindingCode.PENDING_LONGER_THAN_EXPECTED, "PENDING", inStatusSince,
                    thresholds.pendingAlertAfter(), now, statusEvidence);
            case AUTHORIZED -> ageFinding(findings, FindingCode.AUTHORIZED_NOT_CAPTURED, "AUTHORIZED without capture",
                    inStatusSince, thresholds.authorizedAlertAfter(), now, statusEvidence);
            case CAPTURED -> ageFinding(findings, FindingCode.SETTLEMENT_DELAYED, "CAPTURED without settlement",
                    inStatusSince, thresholds.settlementDelayAfter(), now, statusEvidence);
            case VOIDED -> findings.add(new Finding(FindingCode.AUTHORIZATION_VOIDED, Severity.INFO,
                    "The authorization was voided; no funds were captured.", statusEvidence));
            default -> {
                // SETTLED, refund and dispute statuses are covered by the money and dispute findings below.
            }
        }

        if (money.refundCount() > 0) {
            findings.add(new Finding(FindingCode.REFUNDS_RECORDED, Severity.INFO,
                    money.refundCount() + " refund(s) totalling " + amount(money.refundedAmount(), p.currency())
                            + " recorded; " + amount(money.notRefundedAmount(), p.currency()) + " of "
                            + amount(p.amount(), p.currency()) + " has not been refunded.",
                    sequenceNos(all(events, PaymentEventType.REFUND_RECORDED))));
        }

        List<Integer> disputeEvidence = disputeOpened.map(e -> List.of(e.sequenceNo())).orElse(statusEvidence);
        switch (dispute.state()) {
            case OPEN -> findings.add(new Finding(FindingCode.DISPUTE_OPEN, Severity.WARNING,
                    "A dispute is under investigation"
                            + (dispute.openedAt() != null ? " since " + dispute.openedAt() : "")
                            + (dispute.reasonCode() != null ? " with reason " + dispute.reasonCode() : "")
                            + ". The payment has not been reversed or charged back; refunds are blocked meanwhile.",
                    disputeEvidence));
            case WITHDRAWN -> findings.add(new Finding(FindingCode.DISPUTE_WITHDRAWN, Severity.INFO,
                    "The dispute was withdrawn on " + dispute.closedAt() + "; the payment status was restored.",
                    statusEvidence));
            case WON -> findings.add(new Finding(FindingCode.DISPUTE_WON, Severity.INFO,
                    "The dispute was resolved in the merchant's favour on " + dispute.closedAt() + ".",
                    statusEvidence));
            case LOST -> findings.add(new Finding(FindingCode.CHARGEBACK_RECORDED, Severity.WARNING,
                    "The dispute was lost on " + dispute.closedAt() + "; the funds were returned to the cardholder.",
                    statusEvidence));
            case NONE -> {
                // nothing to report
            }
        }

        List<TimelineEntry> snapshots = all(events, PaymentEventType.LEGACY_SNAPSHOT);
        if (!snapshots.isEmpty()) {
            TimelineEntry snapshot = snapshots.get(0);
            findings.add(new Finding(FindingCode.HISTORY_INCOMPLETE, Severity.INFO,
                    "Events before event history was introduced (" + snapshot.occurredAt() + ") may be missing; "
                            + "the status at that time was " + snapshot.toStatus() + ". Totals cover recorded events only.",
                    List.of(snapshot.sequenceNo())));
        }

        List<TimelineEntry> late = events.stream().filter(TimelineEntry::recordedOutOfOrder).toList();
        if (!late.isEmpty()) {
            findings.add(new Finding(FindingCode.EVENTS_RECORDED_OUT_OF_ORDER, Severity.INFO,
                    late.size() + " event(s) were recorded after an event that occurred later; "
                            + "the timeline is ordered by when events occurred.",
                    sequenceNos(late)));
        }

        PaymentStatus historyStatus = lastStatusEvent.map(TimelineEntry::toStatus).orElse(null);
        if (historyStatus != p.status()) {
            findings.add(new Finding(FindingCode.STATUS_HISTORY_MISMATCH, Severity.WARNING,
                    (historyStatus != null
                            ? "Recorded history ends in " + historyStatus
                            : "No status-changing event is recorded")
                            + " but the payment status is " + p.status() + ". Escalate as a data-integrity issue.",
                    statusEvidence));
        }

        return findings;
    }

    private static List<Finding> disputeCaseFindings(PaymentSnapshot p, InvestigationSummary.DisputeCaseFacts c) {
        List<Finding> result = new ArrayList<>();
        if (c != null && c.overdue()) {
            result.add(new Finding(FindingCode.DISPUTE_RESPONSE_OVERDUE, Severity.WARNING, "Dispute case " + c.caseRef()
                    + " is past its response due date (" + c.responseDueAt() + ", as provided) and is still "
                    + c.status() + ".", List.of()));
        }
        if (c == null && p.status() == PaymentStatus.DISPUTED) {
            result.add(new Finding(FindingCode.DISPUTE_CASE_MISSING, Severity.INFO,
                    "The payment is DISPUTED but no dispute case is open in PayAssist; open one to track the investigation.",
                    List.of()));
        }
        return result;
    }

    private static void ageFinding(List<Finding> findings, FindingCode code, String state, Instant since,
            Duration threshold, Instant now, List<Integer> evidence) {
        Duration age = Duration.between(since, now);
        if (age.compareTo(threshold) > 0) {
            findings.add(new Finding(code, Severity.WARNING,
                    "Payment has been " + state + " for " + human(age) + " (since " + since
                            + "), longer than the " + human(threshold) + " threshold.",
                    evidence));
        }
    }

    private static DisputeFacts disputeFacts(PaymentSnapshot p, Optional<TimelineEntry> opened,
            Optional<TimelineEntry> closed) {
        String reason = opened.map(TimelineEntry::reasonCode).orElse(null);
        String reasonDescription = opened.map(TimelineEntry::reasonDescription).orElse(null);
        Instant openedAt = opened.map(TimelineEntry::occurredAt).orElse(null);
        if (p.status() == PaymentStatus.DISPUTED) {
            return new DisputeFacts(DisputeState.OPEN, reason, reasonDescription, openedAt, null);
        }
        if (closed.isPresent()) {
            DisputeState state = switch (closed.get().eventType()) {
                case DISPUTE_WON -> DisputeState.WON;
                case DISPUTE_WITHDRAWN -> DisputeState.WITHDRAWN;
                default -> DisputeState.LOST;
            };
            return new DisputeFacts(state, reason, reasonDescription, openedAt, closed.get().occurredAt());
        }
        if (p.status() == PaymentStatus.CHARGED_BACK) {
            // Only possible for migrated payments whose dispute events predate history.
            return new DisputeFacts(DisputeState.LOST, reason, reasonDescription, openedAt, null);
        }
        return new DisputeFacts(DisputeState.NONE, null, null, null, null);
    }

    static String human(Duration d) {
        if (d.toDays() >= 1) {
            return d.toDays() + (d.toDays() == 1 ? " day" : " days");
        }
        if (d.toHours() >= 1) {
            return d.toHours() + (d.toHours() == 1 ? " hour" : " hours");
        }
        return d.toMinutes() + (d.toMinutes() == 1 ? " minute" : " minutes");
    }

    private static String amount(BigDecimal value, String currency) {
        return value.toPlainString() + " " + currency;
    }

    private static List<TimelineEntry> all(List<TimelineEntry> events, PaymentEventType type) {
        return events.stream().filter(e -> e.eventType() == type).toList();
    }

    private static Optional<TimelineEntry> last(List<TimelineEntry> events, PaymentEventType type) {
        return last(events, e -> e.eventType() == type);
    }

    private static Optional<TimelineEntry> last(List<TimelineEntry> events, Predicate<TimelineEntry> filter) {
        TimelineEntry result = null;
        for (TimelineEntry e : events) {
            if (filter.test(e)) {
                result = e;
            }
        }
        return Optional.ofNullable(result);
    }

    private static Instant firstAt(List<TimelineEntry> events, PaymentEventType type) {
        return events.stream().filter(e -> e.eventType() == type).findFirst().map(TimelineEntry::occurredAt)
                .orElse(null);
    }

    private static List<Integer> sequenceNos(List<TimelineEntry> entries) {
        return entries.stream().map(TimelineEntry::sequenceNo).toList();
    }
}

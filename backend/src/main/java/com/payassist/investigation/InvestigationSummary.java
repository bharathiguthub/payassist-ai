package com.payassist.investigation;

import com.payassist.customer.CustomerSummary;
import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentMethod;
import com.payassist.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Deterministic investigation summary of one payment: verified facts from recorded payment data plus
 * rule-based findings. Contains no generated or speculative text: every message is a fixed template,
 * and every finding cites the events (by sequence number) it is based on.
 *
 * <p>This record is the stable, versioned contract a future AI tool will return to a model, so fields
 * are only ever added (never renamed or repurposed) within a {@link #schemaVersion}.
 */
public record InvestigationSummary(
        String schemaVersion,
        String paymentRef,
        Instant generatedAt,
        long paymentVersion,
        String basis,
        PaymentFacts payment,
        Milestones milestones,
        MoneyFacts money,
        DisputeFacts dispute,
        HistoryCoverage history,
        List<Finding> findings,
        Set<PaymentEventType> possibleNextEvents,
        DisputeCaseFacts disputeCase) {

    public static final String SCHEMA_VERSION = "1.0";
    public static final String BASIS =
            "Derived only from recorded payment data (payments and payment_events). No inference or prediction.";

    public record PaymentFacts(
            BigDecimal amount,
            String currency,
            PaymentStatus status,
            String statusDescription,
            boolean terminal,
            PaymentMethod paymentMethod,
            String merchantName,
            String failureReason,
            String failureDescription,
            Instant createdAt,
            Instant lastUpdatedAt,
            CustomerSummary customer) {
    }

    /** First time each milestone was recorded; {@code null} if it was never recorded. */
    public record Milestones(
            Instant createdAt,
            Instant authorizedAt,
            Instant capturedAt,
            Instant settledAt,
            Instant failedAt,
            Instant voidedAt,
            Instant lastRefundAt,
            Instant disputeOpenedAt,
            Instant disputeClosedAt) {
    }

    public record MoneyFacts(String currency, BigDecimal paymentAmount, BigDecimal refundedAmount,
            BigDecimal notRefundedAmount, int refundCount) {
    }

    /** OPEN is an investigation status: an open dispute does not mean the payment was reversed or charged back. */
    public enum DisputeState { NONE, OPEN, WON, LOST, WITHDRAWN }

    public record DisputeFacts(DisputeState state, String reasonCode, String reasonDescription, Instant openedAt,
            Instant closedAt) {
    }

    /**
     * @param complete     false when history before {@code recordedFrom} was not captured (migrated payments)
     * @param recordedFrom when the earliest recorded event occurred
     */
    public record HistoryCoverage(int eventCount, boolean complete, Instant recordedFrom) {
    }

    /**
     * The active PayAssist dispute case for the payment, if any (added in schema 1.0 as an optional field).
     *
     * @param responseDueAt as provided by the user from the bank/network notification; PayAssist computes none
     */
    public record DisputeCaseFacts(String caseRef, String status, String reasonCategory, Instant openedAt,
            Instant responseDueAt, boolean overdue) {
    }

    public enum Severity { WARNING, INFO }

    /**
     * A rule-based observation.
     *
     * @param code     stable machine-readable code (see {@link FindingCode})
     * @param evidence sequence numbers of the events the finding is based on
     */
    public record Finding(FindingCode code, Severity severity, String message, List<Integer> evidence) {
    }

    public enum FindingCode {
        PAYMENT_FAILED,
        PENDING_LONGER_THAN_EXPECTED,
        AUTHORIZED_NOT_CAPTURED,
        SETTLEMENT_DELAYED,
        AUTHORIZATION_VOIDED,
        REFUNDS_RECORDED,
        DISPUTE_OPEN,
        DISPUTE_WON,
        DISPUTE_WITHDRAWN,
        DISPUTE_RESPONSE_OVERDUE,
        DISPUTE_CASE_MISSING,
        CHARGEBACK_RECORDED,
        HISTORY_INCOMPLETE,
        EVENTS_RECORDED_OUT_OF_ORDER,
        STATUS_HISTORY_MISMATCH
    }
}

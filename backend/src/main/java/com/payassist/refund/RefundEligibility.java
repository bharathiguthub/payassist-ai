package com.payassist.refund;

import com.payassist.payment.PaymentMethod;
import com.payassist.payment.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Result of a refund eligibility check. Advisory and read-only: nothing is created, reserved or executed.
 * The same rules are re-applied atomically when a refund request is created (milestone 4), so a decision
 * here can never be used to bypass the policy later.
 *
 * <p>This record is the stable contract returned to the UI and, later, to AI tools. Fields are only added
 * within a {@link #schemaVersion}; reason codes are never renamed or reused with a new meaning.
 *
 * @param requestedAmount           the amount evaluated: the request's amount or, if none was given, the
 *                                  remaining refundable amount ("how much could be refunded?")
 * @param remainingRefundableAmount payment amount − previously refunded − reserved by pending requests
 * @param reasons                   the codes that determined the decision; {@code WITHIN_POLICY} if eligible
 * @param checks                    every rule evaluated, in order, including passes (for explainability)
 * @param paymentVersion            the payment version the facts were read at
 */
public record RefundEligibility(
        String schemaVersion,
        Decision decision,
        String paymentRef,
        BigDecimal requestedAmount,
        String currency,
        PaymentStatus paymentStatus,
        PaymentMethod paymentMethod,
        BigDecimal paymentAmount,
        String paymentCurrency,
        BigDecimal previouslyRefundedAmount,
        BigDecimal reservedPendingAmount,
        BigDecimal remainingRefundableAmount,
        RefundWindow refundWindow,
        List<Reason> reasons,
        List<Check> checks,
        String policyVersion,
        Instant evaluatedAt,
        long paymentVersion,
        String correlationId,
        String advisory) {

    public static final String SCHEMA_VERSION = "1.0";
    public static final String ADVISORY = "Advisory check only: no refund was created, reserved or executed. "
            + "Eligibility is re-validated when a refund request is submitted.";

    public enum Decision { ELIGIBLE, MANUAL_REVIEW, INELIGIBLE }

    public enum Outcome { PASS, MANUAL_REVIEW, INELIGIBLE, NOT_EVALUATED }

    /** The rules, in evaluation order. */
    public enum Rule { PAYMENT_STATUS, CURRENCY, PAYMENT_METHOD, REFUND_WINDOW, REFUNDABLE_AMOUNT, PENDING_REFUNDS,
        APPROVAL_LIMIT }

    /** Stable, machine-readable reason codes. Never renamed or repurposed. */
    public enum ReasonCode {
        WITHIN_POLICY,
        PAYMENT_NOT_CAPTURED,
        PAYMENT_FAILED,
        PAYMENT_VOIDED,
        PAYMENT_FULLY_REFUNDED,
        PAYMENT_DISPUTED,
        PAYMENT_CHARGED_BACK,
        CURRENCY_MISMATCH,
        CURRENCY_NOT_SUPPORTED,
        PAYMENT_METHOD_NOT_SUPPORTED,
        PARTIAL_REFUND_NOT_ALLOWED_FOR_METHOD,
        REFUND_WINDOW_EXPIRED,
        NOTHING_LEFT_TO_REFUND,
        AMOUNT_EXCEEDS_REFUNDABLE,
        PENDING_REFUND_IN_PROGRESS,
        SUPERVISOR_LIMIT_EXCEEDED,
        APPROVAL_LIMIT_NOT_CONFIGURED
    }

    public record Reason(ReasonCode code, Outcome outcome, String message) {
    }

    /** One evaluated rule. {@code code} is {@code null} when the rule passed or was not evaluated. */
    public record Check(Rule rule, Outcome outcome, ReasonCode code, String message) {
    }

    public enum WindowBasis {
        /** The window starts when funds were first captured. */
        CAPTURED_AT,
        /** No capture was recorded (migrated history); the earlier payment creation time is used instead. */
        PAYMENT_CREATED_AT
    }

    public record RefundWindow(WindowBasis basis, Instant startsAt, Instant endsAt, int days, boolean open) {
    }
}

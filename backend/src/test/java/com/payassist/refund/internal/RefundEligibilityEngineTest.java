package com.payassist.refund.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.payassist.payment.PaymentHistory.RefundPosition;
import com.payassist.payment.PaymentMethod;
import com.payassist.payment.PaymentStatus;
import com.payassist.refund.RefundEligibility;
import com.payassist.refund.RefundEligibility.Check;
import com.payassist.refund.RefundEligibility.Decision;
import com.payassist.refund.RefundEligibility.Outcome;
import com.payassist.refund.RefundEligibility.Reason;
import com.payassist.refund.RefundEligibility.ReasonCode;
import com.payassist.refund.RefundEligibility.Rule;
import com.payassist.refund.RefundEligibility.WindowBasis;
import com.payassist.refund.internal.RefundPolicy.MethodRule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RefundEligibilityEngineTest {

    static final java.util.UUID PAYMENT_ID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    static final Instant CAPTURED = NOW.minus(Duration.ofDays(30));

    private final RefundEligibilityEngine engine = new RefundEligibilityEngine(policy(true));

    @Test
    void settledCardPaymentWithinPolicyIsEligible() {
        RefundEligibility result = evaluate(settled(PaymentMethod.CARD, "100.00", "0.00"), "40.00", "GBP");

        assertThat(result.decision()).isEqualTo(Decision.ELIGIBLE);
        assertThat(result.reasons()).extracting(Reason::code).containsExactly(ReasonCode.WITHIN_POLICY);
        assertThat(result.checks()).extracting(Check::rule).containsExactly(Rule.values());
        assertThat(result.checks()).extracting(Check::outcome).containsOnly(Outcome.PASS);
        assertThat(result.requestedAmount()).hasToString("40.00");
        assertThat(result.remainingRefundableAmount()).hasToString("100.00");
        assertThat(result.policyVersion()).isEqualTo("TEST-POLICY-v7");
        assertThat(result.schemaVersion()).isEqualTo("1.0");
        assertThat(result.advisory()).contains("no refund was created");
        assertThat(result.refundWindow().basis()).isEqualTo(WindowBasis.CAPTURED_AT);
        assertThat(result.refundWindow().endsAt()).isEqualTo(CAPTURED.plus(Duration.ofDays(120)));
    }

    @Test
    void sameInputsAlwaysGiveTheSameDecision() {
        var payment = settled(PaymentMethod.WALLET, "250.00", "10.00");
        assertThat(evaluate(payment, "20.00", "GBP")).isEqualTo(evaluate(payment, "20.00", "GBP"));
    }

    @Test
    void pendingPaymentIsIneligibleWithOneClearReason() {
        RefundEligibility result = evaluate(position(PaymentStatus.PENDING, false, PaymentMethod.CARD, "100.00", "0.00",
                null), "10.00", "GBP");

        assertThat(result.decision()).isEqualTo(Decision.INELIGIBLE);
        assertThat(result.reasons()).extracting(Reason::code).containsExactly(ReasonCode.PAYMENT_NOT_CAPTURED);
        assertThat(outcome(result, Rule.REFUND_WINDOW)).isEqualTo(Outcome.NOT_EVALUATED);
        assertThat(outcome(result, Rule.REFUNDABLE_AMOUNT)).isEqualTo(Outcome.NOT_EVALUATED);
        assertThat(outcome(result, Rule.APPROVAL_LIMIT)).isEqualTo(Outcome.NOT_EVALUATED);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class,
            names = {"PENDING", "AUTHORIZED", "FAILED", "VOIDED", "REFUNDED", "DISPUTED", "CHARGED_BACK"})
    void everyNonRefundableStatusHasAStableReasonCode(PaymentStatus status) {
        RefundEligibility result = evaluate(position(status, false, PaymentMethod.CARD, "100.00", "0.00", CAPTURED),
                "10.00", "GBP");

        ReasonCode expected = switch (status) {
            case PENDING, AUTHORIZED -> ReasonCode.PAYMENT_NOT_CAPTURED;
            case FAILED -> ReasonCode.PAYMENT_FAILED;
            case VOIDED -> ReasonCode.PAYMENT_VOIDED;
            case REFUNDED -> ReasonCode.PAYMENT_FULLY_REFUNDED;
            case DISPUTED -> ReasonCode.PAYMENT_DISPUTED;
            case CHARGED_BACK -> ReasonCode.PAYMENT_CHARGED_BACK;
            default -> throw new IllegalArgumentException();
        };
        assertThat(result.decision()).isEqualTo(Decision.INELIGIBLE);
        assertThat(result.reasons()).extracting(Reason::code).containsExactly(expected);
    }

    @Test
    void failedPaymentIsIneligible() {
        RefundEligibility result = evaluate(position(PaymentStatus.FAILED, false, PaymentMethod.WALLET, "50.00", "0.00",
                null), null, null);
        assertThat(result.decision()).isEqualTo(Decision.INELIGIBLE);
        assertThat(result.reasons().get(0).message()).isEqualTo("The payment failed: no funds were captured.");
    }

    @Test
    void partialRefundsReduceTheRemainingAmount() {
        var payment = position(PaymentStatus.PARTIALLY_REFUNDED, true, PaymentMethod.CARD, "100.00", "40.00", CAPTURED);

        RefundEligibility exact = evaluate(payment, "60.00", "GBP");
        assertThat(exact.decision()).isEqualTo(Decision.ELIGIBLE);
        assertThat(exact.previouslyRefundedAmount()).hasToString("40.00");
        assertThat(exact.remainingRefundableAmount()).hasToString("60.00");

        RefundEligibility tooMuch = evaluate(payment, "60.01", "GBP");
        assertThat(tooMuch.decision()).isEqualTo(Decision.INELIGIBLE);
        assertThat(tooMuch.reasons()).extracting(Reason::code).containsExactly(ReasonCode.AMOUNT_EXCEEDS_REFUNDABLE);
        assertThat(tooMuch.reasons().get(0).message()).isEqualTo("Requested 60.01 GBP exceeds the remaining refundable "
                + "amount 60.00 GBP (payment 100.00 GBP, refunded 40.00 GBP, pending 0.00 GBP).");
    }

    @Test
    void excessAmountOnAnUnrefundedPaymentIsIneligible() {
        RefundEligibility result = evaluate(settled(PaymentMethod.CARD, "100.00", "0.00"), "100.01", "GBP");
        assertThat(result.decision()).isEqualTo(Decision.INELIGIBLE);
        assertThat(result.reasons()).extracting(Reason::code).containsExactly(ReasonCode.AMOUNT_EXCEEDS_REFUNDABLE);
    }

    @Test
    void reservedPendingAmountCountsAgainstRemainingAndNeedsReview() {
        var payment = settled(PaymentMethod.CARD, "100.00", "20.00");

        RefundEligibility result = engine.evaluate(payment, new BigDecimal("30.00"), "GBP", new BigDecimal("50.00"),
                NOW, "corr");

        assertThat(result.reservedPendingAmount()).hasToString("50.00");
        assertThat(result.remainingRefundableAmount()).hasToString("30.00");
        assertThat(result.decision()).isEqualTo(Decision.MANUAL_REVIEW);
        assertThat(result.reasons()).extracting(Reason::code).containsExactly(ReasonCode.PENDING_REFUND_IN_PROGRESS);

        RefundEligibility over = engine.evaluate(payment, new BigDecimal("30.01"), "GBP", new BigDecimal("50.00"),
                NOW, "corr");
        assertThat(over.decision()).isEqualTo(Decision.INELIGIBLE);
        assertThat(over.reasons()).extracting(Reason::code)
                .containsExactly(ReasonCode.AMOUNT_EXCEEDS_REFUNDABLE, ReasonCode.PENDING_REFUND_IN_PROGRESS);
    }

    @Test
    void currencyMismatchIsIneligibleAndAmountRulesAreNotEvaluated() {
        RefundEligibility result = evaluate(settled(PaymentMethod.CARD, "100.00", "0.00"), "10.00", "USD");

        assertThat(result.decision()).isEqualTo(Decision.INELIGIBLE);
        assertThat(result.reasons()).extracting(Reason::code).containsExactly(ReasonCode.CURRENCY_MISMATCH);
        assertThat(result.reasons().get(0).message())
                .isEqualTo("Refunds must be made in the payment currency GBP; the request was in USD.");
        assertThat(outcome(result, Rule.REFUNDABLE_AMOUNT)).isEqualTo(Outcome.NOT_EVALUATED);
        assertThat(outcome(result, Rule.APPROVAL_LIMIT)).isEqualTo(Outcome.NOT_EVALUATED);
    }

    @Test
    void paymentCurrencyOutsideThePolicyIsNotSupported() {
        var payment = new RefundPosition(PAYMENT_ID, "PAY-000001", new BigDecimal("100"), "JPY", PaymentStatus.SETTLED,
                PaymentMethod.CARD, CAPTURED, CAPTURED, new BigDecimal("0"), 0, true, true, 1);
        RefundEligibility result = engine.evaluate(payment, new BigDecimal("10"), "JPY", BigDecimal.ZERO, NOW, null);
        assertThat(result.reasons()).extracting(Reason::code).contains(ReasonCode.CURRENCY_NOT_SUPPORTED);
        assertThat(result.decision()).isEqualTo(Decision.INELIGIBLE);
    }

    @Test
    void refundWindowIsInclusiveOfItsLastInstant() {
        Instant capturedExactlyAtLimit = NOW.minus(Duration.ofDays(120));
        var atLimit = position(PaymentStatus.SETTLED, true, PaymentMethod.CARD, "100.00", "0.00", capturedExactlyAtLimit);
        assertThat(evaluate(atLimit, "10.00", "GBP").decision()).isEqualTo(Decision.ELIGIBLE);

        var oneSecondLate = position(PaymentStatus.SETTLED, true, PaymentMethod.CARD, "100.00", "0.00",
                capturedExactlyAtLimit.minusSeconds(1));
        RefundEligibility expired = evaluate(oneSecondLate, "10.00", "GBP");
        assertThat(expired.decision()).isEqualTo(Decision.INELIGIBLE);
        assertThat(expired.reasons()).extracting(Reason::code).containsExactly(ReasonCode.REFUND_WINDOW_EXPIRED);
        assertThat(expired.refundWindow().open()).isFalse();
    }

    @Test
    void windowLengthDependsOnPaymentMethod() {
        Instant capturedAt = NOW.minus(Duration.ofDays(75));
        assertThat(evaluate(position(PaymentStatus.SETTLED, true, PaymentMethod.CARD, "10.00", "0.00", capturedAt),
                "10.00", "GBP").decision()).isEqualTo(Decision.ELIGIBLE);                        // 120 days
        assertThat(evaluate(position(PaymentStatus.SETTLED, true, PaymentMethod.WALLET, "10.00", "0.00", capturedAt),
                "10.00", "GBP").decision()).isEqualTo(Decision.ELIGIBLE);                        // 90 days
        assertThat(evaluate(position(PaymentStatus.SETTLED, true, PaymentMethod.BANK_TRANSFER, "10.00", "0.00",
                capturedAt), "10.00", "GBP").decision()).isEqualTo(Decision.INELIGIBLE);         // 60 days
    }

    @Test
    void migratedPaymentWithoutCaptureEventUsesTheEarlierCreationTime() {
        var payment = new RefundPosition(PAYMENT_ID, "PAY-000001", new BigDecimal("100.00"), "GBP", PaymentStatus.SETTLED,
                PaymentMethod.CARD, NOW.minus(Duration.ofDays(130)), null, new BigDecimal("0.00"), 0, true, false, 0);
        RefundEligibility result = evaluate(payment, "10.00", "GBP");
        assertThat(result.refundWindow().basis()).isEqualTo(WindowBasis.PAYMENT_CREATED_AT);
        assertThat(result.decision()).isEqualTo(Decision.INELIGIBLE);
    }

    @Test
    void disabledPaymentMethodIsNotSupported() {
        var disabledCards = new RefundEligibilityEngine(policy(false));
        RefundEligibility result = disabledCards.evaluate(settled(PaymentMethod.CARD, "100.00", "0.00"),
                new BigDecimal("10.00"), "GBP", BigDecimal.ZERO, NOW, null);
        assertThat(result.decision()).isEqualTo(Decision.INELIGIBLE);
        assertThat(result.reasons()).extracting(Reason::code).containsExactly(ReasonCode.PAYMENT_METHOD_NOT_SUPPORTED);
    }

    @Test
    void bankTransfersCanOnlyBeRefundedInFull() {
        var payment = settled(PaymentMethod.BANK_TRANSFER, "100.00", "0.00");
        RefundEligibility partial = evaluate(payment, "99.99", "GBP");
        assertThat(partial.decision()).isEqualTo(Decision.INELIGIBLE);
        assertThat(partial.reasons()).extracting(Reason::code)
                .containsExactly(ReasonCode.PARTIAL_REFUND_NOT_ALLOWED_FOR_METHOD);

        assertThat(evaluate(payment, "100.00", "GBP").decision()).isEqualTo(Decision.ELIGIBLE);
        assertThat(evaluate(payment, null, null).decision()).isEqualTo(Decision.ELIGIBLE);
    }

    @Test
    void aboveTheSupervisorLimitNeedsManualReviewAndIneligibilityWins() {
        var large = settled(PaymentMethod.CARD, "5000.00", "0.00");
        RefundEligibility review = evaluate(large, "1000.01", "GBP");
        assertThat(review.decision()).isEqualTo(Decision.MANUAL_REVIEW);
        assertThat(review.reasons()).extracting(Reason::code).containsExactly(ReasonCode.SUPERVISOR_LIMIT_EXCEEDED);
        assertThat(evaluate(large, "1000.00", "GBP").decision()).isEqualTo(Decision.ELIGIBLE);

        var expiredAndLarge = position(PaymentStatus.SETTLED, true, PaymentMethod.CARD, "5000.00", "0.00",
                NOW.minus(Duration.ofDays(200)));
        RefundEligibility both = evaluate(expiredAndLarge, "2000.00", "GBP");
        assertThat(both.decision()).isEqualTo(Decision.INELIGIBLE);
        assertThat(both.reasons()).extracting(Reason::code)
                .containsExactly(ReasonCode.REFUND_WINDOW_EXPIRED, ReasonCode.SUPERVISOR_LIMIT_EXCEEDED);
    }

    @Test
    void missingApprovalLimitFailsSafeToManualReview() {
        var payment = new RefundPosition(PAYMENT_ID, "PAY-000001", new BigDecimal("100.00"), "EUR", PaymentStatus.SETTLED,
                PaymentMethod.CARD, CAPTURED, CAPTURED, new BigDecimal("0.00"), 0, true, true, 1);
        RefundEligibility result = engine.evaluate(payment, new BigDecimal("1.00"), "EUR", BigDecimal.ZERO, NOW, null);
        assertThat(result.decision()).isEqualTo(Decision.MANUAL_REVIEW);
        assertThat(result.reasons()).extracting(Reason::code).containsExactly(ReasonCode.APPROVAL_LIMIT_NOT_CONFIGURED);
    }

    @Test
    void withoutAnAmountTheRemainingRefundableAmountIsEvaluated() {
        RefundEligibility result = evaluate(settled(PaymentMethod.CARD, "100.00", "25.00"), null, null);
        assertThat(result.requestedAmount()).hasToString("75.00");
        assertThat(result.currency()).isEqualTo("GBP");
        assertThat(result.decision()).isEqualTo(Decision.ELIGIBLE);
    }

    @Test
    void nothingLeftToRefund() {
        var payment = position(PaymentStatus.PARTIALLY_REFUNDED, true, PaymentMethod.CARD, "100.00", "60.00", CAPTURED);
        RefundEligibility result = engine.evaluate(payment, null, null, new BigDecimal("40.00"), NOW, null);
        assertThat(result.remainingRefundableAmount()).hasToString("0.00");
        assertThat(result.reasons()).extracting(Reason::code).contains(ReasonCode.NOTHING_LEFT_TO_REFUND);
    }

    // --- fixtures --------------------------------------------------------------------------------

    private RefundEligibility evaluate(RefundPosition payment, String amount, String currency) {
        return engine.evaluate(payment, amount != null ? new BigDecimal(amount) : null, currency, BigDecimal.ZERO,
                NOW, "corr-1");
    }

    private static RefundPosition settled(PaymentMethod method, String amount, String refunded) {
        PaymentStatus status = new BigDecimal(refunded).signum() > 0 ? PaymentStatus.PARTIALLY_REFUNDED
                : PaymentStatus.SETTLED;
        return position(status, true, method, amount, refunded, CAPTURED);
    }

    private static RefundPosition position(PaymentStatus status, boolean refundable, PaymentMethod method,
            String amount, String refunded, Instant capturedAt) {
        return new RefundPosition(PAYMENT_ID, "PAY-000001", new BigDecimal(amount), "GBP", status, method,
                capturedAt != null ? capturedAt.minusSeconds(600) : NOW.minus(Duration.ofDays(1)), capturedAt,
                new BigDecimal(refunded), new BigDecimal(refunded).signum() > 0 ? 1 : 0, refundable, true, 3);
    }

    private static Outcome outcome(RefundEligibility result, Rule rule) {
        return result.checks().stream().filter(c -> c.rule() == rule).findFirst().orElseThrow().outcome();
    }

    /** GBP and USD limits only (EUR deliberately missing), JPY unsupported. */
    static RefundPolicy policy(boolean cardRefundsEnabled) {
        Map<PaymentMethod, MethodRule> methods = new EnumMap<>(PaymentMethod.class);
        methods.put(PaymentMethod.CARD, new MethodRule(cardRefundsEnabled, 120, true));
        methods.put(PaymentMethod.WALLET, new MethodRule(true, 90, true));
        methods.put(PaymentMethod.BANK_TRANSFER, new MethodRule(true, 60, false));
        return new RefundPolicy("TEST-POLICY-v7", Set.of("GBP", "USD", "EUR"), methods,
                Map.of("GBP", new BigDecimal("1000"), "USD", new BigDecimal("1250.00")));
    }
}

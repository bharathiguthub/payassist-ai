package com.payassist.refund.internal;

import com.payassist.payment.PaymentHistory.RefundPosition;
import com.payassist.refund.RefundEligibility;
import com.payassist.refund.RefundEligibility.Check;
import com.payassist.refund.RefundEligibility.Decision;
import com.payassist.refund.RefundEligibility.Outcome;
import com.payassist.refund.RefundEligibility.Reason;
import com.payassist.refund.RefundEligibility.ReasonCode;
import com.payassist.refund.RefundEligibility.RefundWindow;
import com.payassist.refund.RefundEligibility.WindowBasis;
import com.payassist.refund.internal.EligibilityRules.EligibilityRule;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic refund eligibility engine: a pure function of (payment facts, request, reserved amount,
 * now, policy). No I/O, no randomness. The decision is the most severe rule outcome:
 * any INELIGIBLE → INELIGIBLE; otherwise any MANUAL_REVIEW → MANUAL_REVIEW; otherwise ELIGIBLE.
 */
public final class RefundEligibilityEngine {

    private final RefundPolicy policy;
    private final List<EligibilityRule> rules;

    public RefundEligibilityEngine(RefundPolicy policy) {
        this(policy, EligibilityRules.ALL);
    }

    RefundEligibilityEngine(RefundPolicy policy, List<EligibilityRule> rules) {
        this.policy = Objects.requireNonNull(policy);
        this.rules = List.copyOf(rules);
    }

    public RefundPolicy policy() {
        return policy;
    }

    /**
     * @param requestedAmount amount at the request currency's scale (already validated), or {@code null}
     *                        to evaluate refunding everything that is left
     * @param requestCurrency ISO code of the request, or {@code null} for the payment currency
     * @param reserved        amount reserved by pending refund requests, in the payment currency
     */
    public RefundEligibility evaluate(RefundPosition payment, BigDecimal requestedAmount, String requestCurrency,
            BigDecimal reserved, Instant now, String correlationId) {
        String currency = requestCurrency != null ? requestCurrency : payment.currency();
        int scale = payment.amount().scale();
        BigDecimal reservedAmount = reserved.setScale(scale);
        BigDecimal remaining = payment.amount().subtract(payment.refundedAmount()).subtract(reservedAmount)
                .max(BigDecimal.ZERO.setScale(scale));
        BigDecimal requested = requestedAmount != null ? requestedAmount : remaining;

        RefundWindow window = window(payment, now);
        var ctx = new EligibilityContext(payment, currency, requested, reservedAmount, remaining, window, now, policy);

        List<Check> checks = new ArrayList<>(rules.size());
        for (EligibilityRule rule : rules) {
            checks.add(Objects.requireNonNull(rule.evaluate(ctx), rule.id() + " returned null"));
        }

        Decision decision = decide(checks);
        List<Reason> reasons = checks.stream()
                .filter(c -> c.outcome() == Outcome.INELIGIBLE || c.outcome() == Outcome.MANUAL_REVIEW)
                .map(c -> new Reason(c.code(), c.outcome(), c.message()))
                .toList();
        if (reasons.isEmpty()) {
            reasons = List.of(new Reason(ReasonCode.WITHIN_POLICY, Outcome.PASS,
                    "The refund meets every rule of the refund policy and is within the supervisor approval limit."));
        }

        return new RefundEligibility(RefundEligibility.SCHEMA_VERSION, decision, payment.paymentRef(), requested,
                currency, payment.status(), payment.paymentMethod(), payment.amount(), payment.currency(),
                payment.refundedAmount(), reservedAmount, remaining, window, reasons, List.copyOf(checks),
                policy.version(), now, payment.version(), correlationId, RefundEligibility.ADVISORY);
    }

    static Decision decide(List<Check> checks) {
        if (checks.stream().anyMatch(c -> c.outcome() == Outcome.INELIGIBLE)) {
            return Decision.INELIGIBLE;
        }
        if (checks.stream().anyMatch(c -> c.outcome() == Outcome.MANUAL_REVIEW)) {
            return Decision.MANUAL_REVIEW;
        }
        return Decision.ELIGIBLE;
    }

    /**
     * The window starts at the first recorded capture. Migrated payments may have no capture event; then
     * the payment creation time is used, which is earlier and therefore never extends the window.
     */
    private RefundWindow window(RefundPosition payment, Instant now) {
        int days = policy.rule(payment.paymentMethod()).refundWindowDays();
        WindowBasis basis = payment.capturedAt() != null ? WindowBasis.CAPTURED_AT : WindowBasis.PAYMENT_CREATED_AT;
        Instant start = payment.capturedAt() != null ? payment.capturedAt() : payment.createdAt();
        Instant end = start.plus(Duration.ofDays(days));
        return new RefundWindow(basis, start, end, days, !now.isAfter(end));
    }
}

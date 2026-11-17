package com.payassist.refund.internal;

import com.payassist.payment.PaymentHistory.RefundPosition;
import com.payassist.refund.RefundEligibility.RefundWindow;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Everything a rule may look at, computed once by the engine. Rules are pure functions of this context.
 *
 * @param requestCurrency the currency the refund was requested in (defaults to the payment currency)
 * @param requested       the amount being evaluated, at the request currency's scale
 * @param reserved        amount held by pending refund requests (milestone 4); zero until then
 * @param remaining       payment amount − refunded − reserved, never negative
 */
public record EligibilityContext(
        RefundPosition payment,
        String requestCurrency,
        BigDecimal requested,
        BigDecimal reserved,
        BigDecimal remaining,
        RefundWindow window,
        Instant now,
        RefundPolicy policy) {

    /** Amounts can only be compared when the request is in the payment's currency. */
    public boolean currencyComparable() {
        return payment.currency().equals(requestCurrency);
    }

    /** Whether the payment state machine allows a refund to be recorded now. */
    public boolean statusRefundable() {
        return payment.refundTransitionAllowed();
    }

    String money(BigDecimal amount) {
        return amount.toPlainString() + " " + payment.currency();
    }
}

package com.payassist.refund.internal;

import com.payassist.refund.RefundEligibility.Check;
import com.payassist.refund.RefundEligibility.Outcome;
import com.payassist.refund.RefundEligibility.ReasonCode;
import com.payassist.refund.RefundEligibility.Rule;
import java.math.BigDecimal;
import java.util.List;

/**
 * The refund eligibility rules, one small class each, evaluated in the order of {@link #ALL}.
 *
 * <p>Rules do not duplicate rules owned elsewhere: whether a payment's status allows a refund comes from
 * the payment state machine ({@code RefundPosition.refundTransitionAllowed}), and amount precision is
 * validated by {@code MonetaryAmounts} before the engine runs. These rules add the refund <em>policy</em>.
 *
 * <p>Dependencies: when the payment's status does not allow refunds, later rules are {@code NOT_EVALUATED}
 * (their answer would be meaningless), so the decision reports one clear reason. Rules that compare amounts
 * are {@code NOT_EVALUATED} when the requested currency differs from the payment's.
 */
public final class EligibilityRules {

    /** A single policy rule. Implementations must be pure: same context, same result. */
    public interface EligibilityRule {
        Rule id();

        Check evaluate(EligibilityContext ctx);
    }

    public static final List<EligibilityRule> ALL = List.of(new PaymentStatusRule(), new CurrencyRule(),
            new PaymentMethodRule(), new RefundWindowRule(), new RefundableAmountRule(), new PendingRefundsRule(),
            new ApprovalLimitRule());

    private EligibilityRules() {
    }

    static Check pass(Rule rule, String message) {
        return new Check(rule, Outcome.PASS, null, message);
    }

    static Check ineligible(Rule rule, ReasonCode code, String message) {
        return new Check(rule, Outcome.INELIGIBLE, code, message);
    }

    static Check manualReview(Rule rule, ReasonCode code, String message) {
        return new Check(rule, Outcome.MANUAL_REVIEW, code, message);
    }

    static Check notEvaluated(Rule rule, String why) {
        return new Check(rule, Outcome.NOT_EVALUATED, null, why);
    }

    private static final String STATUS_BLOCKS = "Not evaluated: the payment status does not allow refunds.";
    private static final String CURRENCY_BLOCKS = "Not evaluated: the requested currency differs from the payment currency.";

    /** The payment state machine must allow REFUND_RECORDED from the current status. */
    static final class PaymentStatusRule implements EligibilityRule {
        @Override
        public Rule id() {
            return Rule.PAYMENT_STATUS;
        }

        @Override
        public Check evaluate(EligibilityContext ctx) {
            var status = ctx.payment().status();
            if (ctx.statusRefundable()) {
                return pass(id(), "Status " + status + " allows refunds.");
            }
            return switch (status) {
                case PENDING -> ineligible(id(), ReasonCode.PAYMENT_NOT_CAPTURED,
                        "The payment is PENDING: no funds have been captured, so there is nothing to refund.");
                case AUTHORIZED -> ineligible(id(), ReasonCode.PAYMENT_NOT_CAPTURED,
                        "The payment is AUTHORIZED but not captured: release (void) the authorization instead of refunding.");
                case FAILED -> ineligible(id(), ReasonCode.PAYMENT_FAILED,
                        "The payment failed: no funds were captured.");
                case VOIDED -> ineligible(id(), ReasonCode.PAYMENT_VOIDED,
                        "The authorization was voided: no funds were captured.");
                case REFUNDED -> ineligible(id(), ReasonCode.PAYMENT_FULLY_REFUNDED,
                        "The payment has already been refunded in full.");
                case DISPUTED -> ineligible(id(), ReasonCode.PAYMENT_DISPUTED,
                        "A dispute is open: refunds are blocked until it is resolved, to avoid paying the customer twice.");
                case CHARGED_BACK -> ineligible(id(), ReasonCode.PAYMENT_CHARGED_BACK,
                        "The payment was charged back: the customer already received the funds through the card network.");
                case CAPTURED, SETTLED, PARTIALLY_REFUNDED -> throw new IllegalStateException(
                        "Payment state machine and refund rules disagree about status " + status);
            };
        }
    }

    /** Refunds go back in the payment currency, and only in currencies the policy supports. */
    static final class CurrencyRule implements EligibilityRule {
        @Override
        public Rule id() {
            return Rule.CURRENCY;
        }

        @Override
        public Check evaluate(EligibilityContext ctx) {
            String paymentCurrency = ctx.payment().currency();
            if (!ctx.currencyComparable()) {
                return ineligible(id(), ReasonCode.CURRENCY_MISMATCH, "Refunds must be made in the payment currency "
                        + paymentCurrency + "; the request was in " + ctx.requestCurrency() + ".");
            }
            if (!ctx.policy().supportedCurrencies().contains(paymentCurrency)) {
                return ineligible(id(), ReasonCode.CURRENCY_NOT_SUPPORTED,
                        "Refunds in " + paymentCurrency + " are not supported by the refund policy.");
            }
            return pass(id(), "Request is in the payment currency " + paymentCurrency + ".");
        }
    }

    /** Per-method restrictions: refunds enabled, and whether partial refunds are allowed. */
    static final class PaymentMethodRule implements EligibilityRule {
        @Override
        public Rule id() {
            return Rule.PAYMENT_METHOD;
        }

        @Override
        public Check evaluate(EligibilityContext ctx) {
            if (!ctx.statusRefundable()) {
                return notEvaluated(id(), STATUS_BLOCKS);
            }
            var method = ctx.payment().paymentMethod();
            var rule = ctx.policy().rule(method);
            if (!rule.refundsEnabled()) {
                return ineligible(id(), ReasonCode.PAYMENT_METHOD_NOT_SUPPORTED,
                        "Refunds to " + method + " payments are not supported by the refund policy.");
            }
            if (!rule.partialRefundsAllowed() && ctx.currencyComparable() && ctx.requested().signum() > 0) {
                boolean partial = ctx.requested().compareTo(ctx.payment().amount()) < 0
                        || ctx.payment().refundedAmount().signum() > 0;
                if (partial) {
                    return ineligible(id(), ReasonCode.PARTIAL_REFUND_NOT_ALLOWED_FOR_METHOD,
                            method + " payments can only be refunded in full (" + ctx.money(ctx.payment().amount())
                                    + "); partial refunds are not supported.");
                }
            }
            return pass(id(), "Refunds to " + method + " are supported.");
        }
    }

    /** A refund must be requested within the method's window, counted from capture. */
    static final class RefundWindowRule implements EligibilityRule {
        @Override
        public Rule id() {
            return Rule.REFUND_WINDOW;
        }

        @Override
        public Check evaluate(EligibilityContext ctx) {
            if (!ctx.statusRefundable()) {
                return notEvaluated(id(), STATUS_BLOCKS);
            }
            var w = ctx.window();
            if (!w.open()) {
                return ineligible(id(), ReasonCode.REFUND_WINDOW_EXPIRED, "The " + w.days() + "-day refund window for "
                        + ctx.payment().paymentMethod() + " payments ended on " + w.endsAt() + ".");
            }
            return pass(id(), "Within the " + w.days() + "-day refund window (ends " + w.endsAt() + ").");
        }
    }

    /** The requested amount must fit in what is left: amount − refunded − reserved by pending requests. */
    static final class RefundableAmountRule implements EligibilityRule {
        @Override
        public Rule id() {
            return Rule.REFUNDABLE_AMOUNT;
        }

        @Override
        public Check evaluate(EligibilityContext ctx) {
            if (!ctx.statusRefundable()) {
                return notEvaluated(id(), STATUS_BLOCKS);
            }
            if (!ctx.currencyComparable()) {
                return notEvaluated(id(), CURRENCY_BLOCKS);
            }
            String breakdown = " (payment " + ctx.money(ctx.payment().amount()) + ", refunded "
                    + ctx.money(ctx.payment().refundedAmount()) + ", pending " + ctx.money(ctx.reserved()) + ")";
            if (ctx.remaining().signum() == 0) {
                return ineligible(id(), ReasonCode.NOTHING_LEFT_TO_REFUND,
                        "Nothing is left to refund" + breakdown + ".");
            }
            if (ctx.requested().compareTo(ctx.remaining()) > 0) {
                return ineligible(id(), ReasonCode.AMOUNT_EXCEEDS_REFUNDABLE, "Requested " + ctx.money(ctx.requested())
                        + " exceeds the remaining refundable amount " + ctx.money(ctx.remaining()) + breakdown + ".");
            }
            return pass(id(), "Requested " + ctx.money(ctx.requested()) + " is within the remaining refundable amount "
                    + ctx.money(ctx.remaining()) + ".");
        }
    }

    /** Another refund awaiting approval must be resolved by a person before more is promised. */
    static final class PendingRefundsRule implements EligibilityRule {
        @Override
        public Rule id() {
            return Rule.PENDING_REFUNDS;
        }

        @Override
        public Check evaluate(EligibilityContext ctx) {
            if (!ctx.statusRefundable()) {
                return notEvaluated(id(), STATUS_BLOCKS);
            }
            if (ctx.reserved().signum() > 0) {
                return manualReview(id(), ReasonCode.PENDING_REFUND_IN_PROGRESS, ctx.money(ctx.reserved())
                        + " is already reserved by refund requests awaiting approval.");
            }
            return pass(id(), "No other refund requests are pending.");
        }
    }

    /** Amounts above the supervisor approval limit cannot be approved by a supervisor (escalation needed). */
    static final class ApprovalLimitRule implements EligibilityRule {
        @Override
        public Rule id() {
            return Rule.APPROVAL_LIMIT;
        }

        @Override
        public Check evaluate(EligibilityContext ctx) {
            if (!ctx.statusRefundable()) {
                return notEvaluated(id(), STATUS_BLOCKS);
            }
            if (!ctx.currencyComparable()) {
                return notEvaluated(id(), CURRENCY_BLOCKS);
            }
            BigDecimal limit = ctx.policy().supervisorLimit(ctx.payment().currency());
            if (limit == null) {
                return manualReview(id(), ReasonCode.APPROVAL_LIMIT_NOT_CONFIGURED,
                        "No supervisor approval limit is configured for " + ctx.payment().currency() + ".");
            }
            if (ctx.requested().compareTo(limit) > 0) {
                return manualReview(id(), ReasonCode.SUPERVISOR_LIMIT_EXCEEDED, "Requested " + ctx.money(ctx.requested())
                        + " exceeds the supervisor approval limit of " + ctx.money(limit)
                        + "; the request will need escalation and cannot be approved by a supervisor.");
            }
            return pass(id(), "Within the supervisor approval limit of " + ctx.money(limit) + ".");
        }
    }
}

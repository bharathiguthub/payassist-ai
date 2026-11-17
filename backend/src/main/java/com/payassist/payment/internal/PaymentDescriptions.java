package com.payassist.payment.internal;

import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentStatus;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Fixed, human-readable descriptions of statuses, events and reason codes. Every sentence is a template
 * filled only with recorded values, so the same data always produces the same text and nothing is
 * inferred. Unknown reason codes are reported as such rather than guessed.
 */
public final class PaymentDescriptions {

    private static final Map<PaymentStatus, String> STATUS = Map.of(
            PaymentStatus.PENDING, "Payment created; awaiting authorization.",
            PaymentStatus.AUTHORIZED, "Authorized; funds are reserved but not yet captured.",
            PaymentStatus.CAPTURED, "Funds captured; awaiting settlement to the merchant.",
            PaymentStatus.SETTLED, "Funds settled to the merchant.",
            PaymentStatus.FAILED, "Payment failed; no funds were captured.",
            PaymentStatus.VOIDED, "Authorization released before capture; no funds were captured.",
            PaymentStatus.PARTIALLY_REFUNDED, "Part of the payment amount has been refunded.",
            PaymentStatus.REFUNDED, "The full payment amount has been refunded.",
            PaymentStatus.DISPUTED, "A dispute is under investigation; the payment has not been reversed or charged back.",
            PaymentStatus.CHARGED_BACK, "Dispute lost; the funds were returned to the cardholder.");

    private static final Map<String, String> REASONS = Map.ofEntries(
            Map.entry("INSUFFICIENT_FUNDS", "The customer's account had insufficient funds."),
            Map.entry("ISSUER_DECLINED", "The card issuer declined the payment."),
            Map.entry("AUTHENTICATION_FAILED", "Customer authentication (e.g. 3-D Secure) failed."),
            Map.entry("CUSTOMER_REQUEST", "Requested by the customer."),
            Map.entry("PRODUCT_NOT_RECEIVED", "The customer reports the goods or service were not received."),
            Map.entry("NOT_AS_DESCRIBED", "The customer reports the goods or service were not as described."),
            Map.entry("FRAUDULENT", "The cardholder reports the payment was not authorised by them."),
            Map.entry("DUPLICATE_CHARGE", "The customer reports being charged more than once."),
            Map.entry("CREDIT_NOT_PROCESSED", "The customer reports an expected refund was not processed."),
            Map.entry("SUBSCRIPTION_CANCELLED", "The customer reports the subscription had been cancelled."));

    private PaymentDescriptions() {
    }

    public static String status(PaymentStatus status) {
        return STATUS.get(status);
    }

    /** Description of a reason code; {@code null} for {@code null}; explicit "no description" for unknown codes. */
    public static String reason(String reasonCode) {
        if (reasonCode == null) {
            return null;
        }
        return REASONS.getOrDefault(reasonCode, "Reason code " + reasonCode + " (no description on file).");
    }

    public static String event(PaymentEventType type, PaymentStatus toStatus, BigDecimal amount, String currency) {
        String money = amount != null ? amount.toPlainString() + " " + currency : null;
        return switch (type) {
            case LEGACY_SNAPSHOT -> "Status was " + toStatus + " when event history was introduced; "
                    + "earlier events were not recorded.";
            case CREATED -> "Payment created at the processor" + (money != null ? " for " + money : "") + ".";
            case AUTHORIZED -> "Payment authorized.";
            case CAPTURED -> "Funds captured.";
            case SETTLED -> "Funds settled to the merchant.";
            case FAILED -> "Payment failed.";
            case VOIDED -> "Authorization voided before capture.";
            case REFUND_RECORDED -> "Refund of " + money + " recorded; payment is now " + toStatus + ".";
            case DISPUTE_OPENED -> "Dispute opened" + (money != null ? " for " + money : "") + ".";
            case DISPUTE_WON -> "Dispute resolved in the merchant's favour; status restored to " + toStatus + ".";
            case DISPUTE_LOST -> "Dispute lost; funds returned to the cardholder.";
            case DISPUTE_WITHDRAWN -> "Dispute withdrawn by the cardholder; status restored to " + toStatus + ".";
            case REFUND_REQUESTED -> "Refund" + (money != null ? " of " + money : "") + " requested; awaiting approval.";
            case REFUND_REJECTED -> "Refund request rejected.";
            case REFUND_CANCELLED -> "Refund request cancelled.";
            case REFUND_ESCALATED -> "Refund request exceeds the approval limit and needs escalation.";
            case DISPUTE_UPDATED -> "Dispute case updated.";
        };
    }
}

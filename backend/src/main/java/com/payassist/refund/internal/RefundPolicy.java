package com.payassist.refund.internal;

import com.payassist.payment.PaymentMethod;
import com.payassist.shared.MonetaryAmounts;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The synthetic refund policy, bound from {@code payassist.refund.policy}. Validated when the application
 * starts: an incomplete or inconsistent policy stops startup instead of producing wrong decisions.
 *
 * <p>Every decision carries {@link #version()}, so a decision can always be traced back to the exact rules
 * that produced it. Change the version whenever any value here changes.
 *
 * @param version                   policy identifier, e.g. {@code SYNTHETIC-REFUND-POLICY-2026.11-v1}
 * @param supportedCurrencies       ISO 4217 codes refunds may be made in
 * @param methods                   rules per payment method; every {@link PaymentMethod} must be configured
 * @param supervisorApprovalLimits  per currency: the largest refund a CARE_SUPERVISOR may approve. Larger
 *                                  refunds need escalation (a future REFUND_MANAGER role).
 */
@ConfigurationProperties("payassist.refund.policy")
public record RefundPolicy(
        String version,
        Set<String> supportedCurrencies,
        Map<PaymentMethod, MethodRule> methods,
        Map<String, BigDecimal> supervisorApprovalLimits) {

    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    /**
     * @param refundsEnabled        refunds back to this method are supported at all
     * @param refundWindowDays      days after capture during which a refund may be requested
     * @param partialRefundsAllowed whether less than the full payment amount may be refunded
     */
    public record MethodRule(boolean refundsEnabled, int refundWindowDays, boolean partialRefundsAllowed) {

        public MethodRule {
            if (refundWindowDays < 1 || refundWindowDays > 3650) {
                throw new IllegalArgumentException("refund-window-days must be between 1 and 3650");
            }
        }
    }

    public RefundPolicy {
        if (version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("payassist.refund.policy.version must match " + VERSION);
        }
        if (supportedCurrencies == null || supportedCurrencies.isEmpty()) {
            throw new IllegalArgumentException("payassist.refund.policy.supported-currencies must not be empty");
        }
        supportedCurrencies.forEach(MonetaryAmounts::currency); // valid ISO code with supported precision
        supportedCurrencies = Set.copyOf(new TreeSet<>(supportedCurrencies));

        if (methods == null || !methods.keySet().containsAll(Set.of(PaymentMethod.values()))) {
            throw new IllegalArgumentException("payassist.refund.policy.methods must configure every payment method");
        }
        methods = Map.copyOf(new EnumMap<>(methods));

        if (supervisorApprovalLimits == null) {
            supervisorApprovalLimits = Map.of();
        }
        Map<String, BigDecimal> limits = new TreeMap<>();
        supervisorApprovalLimits.forEach((currency, limit) -> {
            int digits = MonetaryAmounts.currency(currency).getDefaultFractionDigits();
            if (limit == null || limit.signum() <= 0) {
                throw new IllegalArgumentException("supervisor approval limit for " + currency + " must be positive");
            }
            try {
                limits.put(currency, limit.setScale(digits, RoundingMode.UNNECESSARY));
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("supervisor approval limit for " + currency
                        + " has more than " + digits + " decimal places", e);
            }
        });
        supervisorApprovalLimits = Map.copyOf(limits);
    }

    public MethodRule rule(PaymentMethod method) {
        return methods.get(method);
    }

    /** The supervisor approval limit for a currency, or {@code null} if none is configured. */
    public BigDecimal supervisorLimit(String currency) {
        return supervisorApprovalLimits.get(currency);
    }
}

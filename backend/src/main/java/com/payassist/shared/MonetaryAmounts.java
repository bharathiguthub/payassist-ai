package com.payassist.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.regex.Pattern;

/**
 * Validation for monetary values. Amounts are always {@link BigDecimal} with an ISO 4217 currency;
 * nothing is ever rounded silently: an amount with more decimal places than its currency allows is
 * rejected, not rounded.
 */
public final class MonetaryAmounts {

    /** Database columns are NUMERIC(19, 2), so only currencies with at most 2 minor digits fit. */
    public static final int MAX_FRACTION_DIGITS = 2;

    private static final Pattern ISO_CODE = Pattern.compile("^[A-Z]{3}$");

    private MonetaryAmounts() {
    }

    /** Returns the ISO 4217 currency for {@code code}, or throws {@code UNSUPPORTED_CURRENCY}. */
    public static Currency currency(String code) {
        if (code == null || !ISO_CODE.matcher(code).matches()) {
            throw new DomainException.RuleViolation("UNSUPPORTED_CURRENCY", "Currency must be an ISO 4217 code");
        }
        Currency currency;
        try {
            currency = Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new DomainException.RuleViolation("UNSUPPORTED_CURRENCY", "Unknown currency " + code);
        }
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0 || digits > MAX_FRACTION_DIGITS) {
            throw new DomainException.RuleViolation("UNSUPPORTED_CURRENCY", "Currency " + code + " is not supported");
        }
        return currency;
    }

    /**
     * Validates a strictly positive amount for {@code currencyCode} and returns it at the currency's
     * scale (e.g. {@code 10} GBP becomes {@code 10.00}).
     */
    public static BigDecimal requirePositive(BigDecimal amount, String currencyCode) {
        if (amount == null || amount.signum() <= 0) {
            throw new DomainException.RuleViolation("INVALID_AMOUNT", "Amount must be greater than zero");
        }
        return atCurrencyScale(amount, currencyCode);
    }

    static BigDecimal atCurrencyScale(BigDecimal amount, String currencyCode) {
        int digits = currency(currencyCode).getDefaultFractionDigits();
        try {
            return amount.setScale(digits, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new DomainException.RuleViolation("INVALID_AMOUNT_SCALE",
                    currencyCode + " amounts allow at most " + digits + " decimal places");
        }
    }
}

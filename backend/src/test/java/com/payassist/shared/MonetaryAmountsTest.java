package com.payassist.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MonetaryAmountsTest {

    @Test
    void normalisesToCurrencyScaleWithoutRounding() {
        assertThat(MonetaryAmounts.requirePositive(new BigDecimal("10"), "GBP")).isEqualByComparingTo("10.00")
                .hasToString("10.00");
        assertThat(MonetaryAmounts.requirePositive(new BigDecimal("10.5"), "INR")).hasToString("10.50");
        assertThat(MonetaryAmounts.requirePositive(new BigDecimal("1500"), "JPY")).hasToString("1500");
    }

    @Test
    void rejectsMoreDecimalsThanTheCurrencyAllows() {
        assertCode(() -> MonetaryAmounts.requirePositive(new BigDecimal("10.001"), "USD"), "INVALID_AMOUNT_SCALE");
        assertCode(() -> MonetaryAmounts.requirePositive(new BigDecimal("1500.5"), "JPY"), "INVALID_AMOUNT_SCALE");
    }

    @Test
    void rejectsZeroNegativeAndMissingAmounts() {
        assertCode(() -> MonetaryAmounts.requirePositive(BigDecimal.ZERO, "EUR"), "INVALID_AMOUNT");
        assertCode(() -> MonetaryAmounts.requirePositive(new BigDecimal("-0.01"), "EUR"), "INVALID_AMOUNT");
        assertCode(() -> MonetaryAmounts.requirePositive(null, "EUR"), "INVALID_AMOUNT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"gbp", "XXXX", "ZZZ", "", "KWD"}) // KWD has 3 minor digits: does not fit NUMERIC(19,2)
    void rejectsInvalidOrUnsupportedCurrencies(String code) {
        assertCode(() -> MonetaryAmounts.currency(code), "UNSUPPORTED_CURRENCY");
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(DomainException.RuleViolation.class)
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo(code);
    }
}

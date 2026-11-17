package com.payassist.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextGuardTest {

    @Test
    void acceptsOrdinaryInvestigationText() {
        String note = "Customer says parcel PAY-000072 never arrived.\nCourier ref DOC-2026-11-0042; called on 16/11.\t"
                + "Order total 706.68 GBP. Phone line ext 4421.";
        assertThat(TextGuard.requireSafe("  " + note + "  ", 2000)).isEqualTo(note);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "card 4111111111111111 was used",          // test Visa number, passes Luhn
        "card 4111 1111 1111 1111 was used",       // with spaces
        "card 4111-1111-1111-1111 was used",       // with hyphens
        "5500000000000004"})
    void rejectsCardNumbers(String text) {
        assertCode(() -> TextGuard.requireSafe(text, 2000), "CARD_NUMBER_DETECTED");
    }

    /** Long numbers that fail the Luhn check (verified) are not card numbers: references, timestamps, phone-like. */
    @ParameterizedTest
    @ValueSource(strings = {"1234567890123", "4111111111111112", "20261116120000", "12345678901234567"})
    void longNumbersThatFailLuhnAreAllowed(String digits) {
        assertThat(TextGuard.luhn(digits)).isFalse();
        assertThat(TextGuard.requireSafe("ref " + digits, 2000)).contains(digits);
    }

    @Test
    void anyLuhnValidDigitRunIsTreatedAsACardNumberErringOnTheSafeSide() {
        assertThat(TextGuard.luhn("9876543210987")).isTrue();
        assertCode(() -> TextGuard.requireSafe("ref 9876543210987", 2000), "CARD_NUMBER_DETECTED");
    }

    @Test
    void rejectsEmailAddressesControlCharactersAndBadLengths() {
        assertCode(() -> TextGuard.requireSafe("reply to jane.doe@example.com", 2000), "EMAIL_ADDRESS_DETECTED");
        assertCode(() -> TextGuard.requireSafe("bell\u0007", 2000), "INVALID_CHARACTERS");
        assertCode(() -> TextGuard.requireSafe("   ", 2000), "TEXT_REQUIRED");
        assertCode(() -> TextGuard.requireSafe(null, 2000), "TEXT_REQUIRED");
        assertCode(() -> TextGuard.requireSafe("x".repeat(2001), 2000), "TEXT_TOO_LONG");
        assertThat(TextGuard.optionalSafe("  ", 500)).isNull();
    }

    @Test
    void markupIsStoredAsPlainTextNotInterpreted() {
        // Output safety is the renderer's job (React escapes text); the guard keeps the text verbatim.
        assertThat(TextGuard.requireSafe("<script>alert(1)</script>", 2000)).isEqualTo("<script>alert(1)</script>");
    }

    @Test
    void errorMessagesNeverEchoTheText() {
        assertThatThrownBy(() -> TextGuard.requireSafe("4111111111111111", 2000))
                .hasMessageNotContaining("4111");
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.RuleViolation.class)
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo(code);
    }
}

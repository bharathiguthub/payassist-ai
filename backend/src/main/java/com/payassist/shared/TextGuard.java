package com.payassist.shared;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validation for free text that staff type into case files (dispute notes, evidence descriptions).
 *
 * <p>Rejects rather than redacts: the author must remove the data, so nothing sensitive is ever stored.
 * <ul>
 *   <li>{@code TEXT_REQUIRED} / {@code TEXT_TOO_LONG}: length limits;</li>
 *   <li>{@code INVALID_CHARACTERS}: control characters other than line breaks and tabs;</li>
 *   <li>{@code CARD_NUMBER_DETECTED}: 13-19 digits (spaces or hyphens allowed between them) that pass the Luhn check,
 *       i.e. a plausible card number (PAN). Payment and case references do not match this pattern;</li>
 *   <li>{@code EMAIL_ADDRESS_DETECTED}: an email address (customer contact data stays in the customer module,
 *       masked).</li>
 * </ul>
 * Messages never echo the offending text.
 */
public final class TextGuard {

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");
    private static final Pattern DIGIT_RUN = Pattern.compile("(?<![0-9])[0-9](?:[ -]?[0-9]){12,18}(?![0-9])");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private TextGuard() {
    }

    /** Validates and returns the text with surrounding whitespace removed. */
    public static String requireSafe(String text, int maxLength) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty()) {
            throw new DomainException.RuleViolation("TEXT_REQUIRED", "Text must not be empty");
        }
        if (value.length() > maxLength) {
            throw new DomainException.RuleViolation("TEXT_TOO_LONG", "Text must be at most " + maxLength + " characters");
        }
        if (CONTROL.matcher(value).find()) {
            throw new DomainException.RuleViolation("INVALID_CHARACTERS", "Text contains control characters");
        }
        if (containsCardNumber(value)) {
            throw new DomainException.RuleViolation("CARD_NUMBER_DETECTED",
                    "Text appears to contain a card number; remove it before saving");
        }
        if (EMAIL.matcher(value).find()) {
            throw new DomainException.RuleViolation("EMAIL_ADDRESS_DETECTED",
                    "Text contains an email address; refer to the customer by reference instead");
        }
        return value;
    }

    /** Optional text: {@code null} or blank stays {@code null}; otherwise as {@link #requireSafe}. */
    public static String optionalSafe(String text, int maxLength) {
        return text == null || text.isBlank() ? null : requireSafe(text, maxLength);
    }

    /** True if the text contains a plausible card number or an e-mail address (no exception, no echo). */
    public static boolean containsPersonalData(String text) {
        return text != null && (containsCardNumber(text) || EMAIL.matcher(text).find());
    }

    static boolean containsCardNumber(String text) {
        Matcher m = DIGIT_RUN.matcher(text);
        while (m.find()) {
            String digits = m.group().replaceAll("[ -]", "");
            if (digits.length() >= 13 && digits.length() <= 19 && luhn(digits)) {
                return true;
            }
        }
        return false;
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}

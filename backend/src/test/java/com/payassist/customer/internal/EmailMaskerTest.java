package com.payassist.customer.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EmailMaskerTest {

    @ParameterizedTest
    @CsvSource({
        "customer00001@example.com, c***@example.com",
        "a@example.com, a***@example.com",
        "no-at-sign, ***",
        "@example.com, ***"
    })
    void masksLocalPart(String input, String expected) {
        assertThat(EmailMasker.mask(input)).isEqualTo(expected);
    }

    @Test
    void handlesNullAndBlank() {
        assertThat(EmailMasker.mask(null)).isEmpty();
        assertThat(EmailMasker.mask("  ")).isEmpty();
    }
}

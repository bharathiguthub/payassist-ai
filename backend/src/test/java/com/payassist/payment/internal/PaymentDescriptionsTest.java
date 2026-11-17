package com.payassist.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentStatus;
import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class PaymentDescriptionsTest {

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void everyStatusHasADescription(PaymentStatus status) {
        assertThat(PaymentDescriptions.status(status)).isNotBlank().endsWith(".");
    }

    @ParameterizedTest
    @EnumSource(PaymentEventType.class)
    void everyEventTypeHasADescription(PaymentEventType type) {
        assertThat(PaymentDescriptions.event(type, PaymentStatus.SETTLED, new BigDecimal("1.00"), "GBP"))
                .isNotBlank().endsWith(".");
    }

    @Test
    void unknownReasonCodesAreReportedNotGuessed() {
        assertThat(PaymentDescriptions.reason("SOMETHING_NEW")).isEqualTo("Reason code SOMETHING_NEW (no description on file).");
        assertThat(PaymentDescriptions.reason(null)).isNull();
        assertThat(PaymentDescriptions.reason("INSUFFICIENT_FUNDS")).isEqualTo("The customer's account had insufficient funds.");
    }
}

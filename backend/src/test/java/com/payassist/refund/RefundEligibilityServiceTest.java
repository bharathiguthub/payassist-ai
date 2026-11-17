package com.payassist.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.payassist.payment.PaymentHistory;
import com.payassist.payment.PaymentHistory.RefundPosition;
import com.payassist.payment.PaymentMethod;
import com.payassist.payment.PaymentStatus;
import com.payassist.refund.RefundEligibility.Decision;
import com.payassist.refund.internal.RefundEligibilityEngine;
import com.payassist.refund.internal.RefundPolicy;
import com.payassist.refund.internal.RefundPolicy.MethodRule;
import com.payassist.refund.internal.RefundReservations;
import com.payassist.shared.AuditRecord;
import com.payassist.shared.DomainException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class RefundEligibilityServiceTest {

    static final java.util.UUID PAYMENT_ID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

    @Mock
    private PaymentHistory payments;
    @Mock
    private RefundReservations reservations;
    @Mock
    private ApplicationEventPublisher publisher;

    private RefundEligibilityService service;

    @BeforeEach
    void setUp() {
        Map<PaymentMethod, MethodRule> methods = new EnumMap<>(PaymentMethod.class);
        for (PaymentMethod m : PaymentMethod.values()) {
            methods.put(m, new MethodRule(true, 120, true));
        }
        var policy = new RefundPolicy("UNIT-v1", Set.of("GBP"), methods, Map.of("GBP", new BigDecimal("1000")));
        service = new RefundEligibilityService(payments, reservations, new RefundEligibilityEngine(policy), publisher,
                Clock.fixed(NOW, ZoneOffset.UTC));
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("agent.alice", "n/a", "ROLE_CARE_AGENT"));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void evaluatesAuthoritativeFactsAndAuditsOnlyNonSensitiveDetails() {
        when(payments.refundPosition("PAY-000001")).thenReturn(position());
        when(reservations.reservedAmount(PAYMENT_ID)).thenReturn(BigDecimal.ZERO);

        RefundEligibility result = service.check("PAY-000001", new BigDecimal("25"), "GBP");

        assertThat(result.decision()).isEqualTo(Decision.ELIGIBLE);
        assertThat(result.requestedAmount()).hasToString("25.00");
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(event.capture());
        AuditRecord audit = (AuditRecord) event.getValue();
        assertThat(audit.action()).isEqualTo("REFUND_ELIGIBILITY_CHECK");
        assertThat(audit.resourceType()).isEqualTo("PAYMENT");
        assertThat(audit.resourceId()).isEqualTo("PAY-000001");
        assertThat(audit.actor().username()).isEqualTo("agent.alice");
        assertThat(audit.details()).containsExactly(
                Map.entry("decision", "ELIGIBLE"),
                Map.entry("reasonCodes", "WITHIN_POLICY"),
                Map.entry("requestedAmount", "25.00"),
                Map.entry("currency", "GBP"),
                Map.entry("remainingRefundableAmount", "100.00"),
                Map.entry("policyVersion", "UNIT-v1"),
                Map.entry("paymentVersion", "4"));
    }

    @Test
    void rejectsPrecisionBeyondTheCurrencyWithoutRounding() {
        assertCode(() -> service.check("PAY-000001", new BigDecimal("10.001"), "GBP"), "INVALID_AMOUNT_SCALE");
        assertCode(() -> service.check("PAY-000001", new BigDecimal("10.5"), "JPY"), "INVALID_AMOUNT_SCALE");
        assertCode(() -> service.check("PAY-000001", new BigDecimal("10.000"), "KWD"), "UNSUPPORTED_CURRENCY");
        assertCode(() -> service.check("PAY-000001", new BigDecimal("0"), "GBP"), "INVALID_AMOUNT");
        assertCode(() -> service.check("PAY-000001", new BigDecimal("10"), null), "CURRENCY_REQUIRED");
        verifyNoInteractions(payments, reservations, publisher);
    }

    @Test
    void trailingZerosAreNotAPrecisionProblem() {
        when(payments.refundPosition("PAY-000001")).thenReturn(position());
        when(reservations.reservedAmount(PAYMENT_ID)).thenReturn(BigDecimal.ZERO);
        assertThat(service.check("PAY-000001", new BigDecimal("10.5000"), "GBP").requestedAmount()).hasToString("10.50");
    }

    @Test
    void unauthenticatedCallerIsRejectedBeforeAnyRead() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.check("PAY-000001", null, null))
                .isInstanceOf(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
        verify(payments, never()).refundPosition(any());
    }

    private static RefundPosition position() {
        Instant captured = NOW.minus(Duration.ofDays(10));
        return new RefundPosition(PAYMENT_ID, "PAY-000001", new BigDecimal("100.00"), "GBP", PaymentStatus.SETTLED,
                PaymentMethod.CARD, captured, captured, new BigDecimal("0.00"), 0, true, true, 4);
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.RuleViolation.class)
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo(code);
    }
}

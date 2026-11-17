package com.payassist.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payassist.customer.CustomerDirectory;
import com.payassist.payment.PaymentEventSource;
import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentHistory.PaymentTimeline;
import com.payassist.payment.PaymentHistory.TimelineEntry;
import com.payassist.payment.PaymentMethod;
import com.payassist.payment.PaymentStatus;
import com.payassist.shared.AuditRecord;
import com.payassist.shared.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

@ExtendWith(MockitoExtension.class)
class PaymentHistoryServiceTest {

    private static final Instant T0 = Instant.parse("2026-05-01T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

    @Mock
    private PaymentRepository payments;
    @Mock
    private PaymentEventRepository events;
    @Mock
    private CustomerDirectory customers;
    @Mock
    private ApplicationEventPublisher publisher;

    private PaymentHistoryService service;
    private Payment payment;

    @BeforeEach
    void setUp() {
        service = new PaymentHistoryService(payments, events, customers, publisher, Clock.fixed(NOW, ZoneOffset.UTC));
        payment = new Payment(UUID.randomUUID(), "PAY-000001", UUID.randomUUID(), new BigDecimal("100.00"), "GBP",
                PaymentStatus.REFUNDED, PaymentMethod.CARD, "Demo Travel Co", null, T0, T0);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void mapsEventsWithDeterministicDescriptions() {
        givenHistory(
                event(1, PaymentEventType.SETTLED, PaymentStatus.CAPTURED, PaymentStatus.SETTLED, null, null, T0),
                event(2, PaymentEventType.REFUND_RECORDED, PaymentStatus.SETTLED, PaymentStatus.REFUNDED,
                        new BigDecimal("100.00"), "CUSTOMER_REQUEST", T0.plusSeconds(60)));

        PaymentTimeline timeline = service.timeline("pay-000001");

        assertThat(timeline.payment().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(timeline.payment().statusDescription()).isEqualTo("The full payment amount has been refunded.");
        assertThat(timeline.payment().terminal()).isTrue();
        assertThat(timeline.payment().nextLifecycleEvents()).isEmpty();
        TimelineEntry refund = timeline.events().get(1);
        assertThat(refund.description()).isEqualTo("Refund of 100.00 GBP recorded; payment is now REFUNDED.");
        assertThat(refund.reasonDescription()).isEqualTo("Requested by the customer.");
        assertThat(refund.correlationId()).isEqualTo("corr-2");
        assertThat(refund.recordedOutOfOrder()).isFalse();
    }

    @Test
    void flagsEventsRecordedAfterLaterEvents() {
        // Recorded in order 1, 2, 3 but event 3 occurred before event 2: a late-arriving message.
        PaymentEvent first = event(1, PaymentEventType.CAPTURED, null, PaymentStatus.CAPTURED, null, null, T0);
        PaymentEvent second = event(2, PaymentEventType.SETTLED, null, PaymentStatus.SETTLED, null, null,
                T0.plusSeconds(7200));
        PaymentEvent third = event(3, PaymentEventType.DISPUTE_UPDATED, null, null, null, null, T0.plusSeconds(60));

        assertThat(PaymentHistoryService.recordedOutOfOrder(List.of(first, third, second))).containsExactly(3);
        assertThat(PaymentHistoryService.recordedOutOfOrder(List.of(first, second))).isEmpty();
    }

    @Test
    void unknownPaymentIsNotFound() {
        when(payments.findByPaymentRef("PAY-999999")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.viewTimeline("PAY-999999")).isInstanceOf(ResourceNotFoundException.class);
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void viewingTheTimelineIsAuditedAsTheAuthenticatedUser() {
        givenHistory();
        var oidcUser = new DefaultOidcUser(AuthorityUtils.NO_AUTHORITIES, new OidcIdToken("token", T0, NOW,
                Map.of("sub", "sub-123", "preferred_username", "agent.alice")));
        SecurityContextHolder.getContext().setAuthentication(
                new OAuth2AuthenticationToken(oidcUser, AuthorityUtils.createAuthorityList("ROLE_CARE_AGENT"), "keycloak"));

        service.viewTimeline("PAY-000001");

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(published.capture());
        AuditRecord audit = (AuditRecord) published.getValue();
        assertThat(audit.action()).isEqualTo("VIEW_TIMELINE");
        assertThat(audit.resourceId()).isEqualTo("PAY-000001");
        assertThat(audit.actor().subject()).isEqualTo("sub-123");
        assertThat(audit.actor().username()).isEqualTo("agent.alice");
        assertThat(audit.occurredAt()).isEqualTo(NOW);
    }

    private void givenHistory(PaymentEvent... history) {
        when(payments.findByPaymentRef("PAY-000001")).thenReturn(Optional.of(payment));
        when(customers.findSummary(payment.getCustomerId())).thenReturn(Optional.empty());
        when(events.findByPaymentIdOrderByOccurredAtAscSequenceNoAsc(payment.getId())).thenReturn(List.of(history));
    }

    private PaymentEvent event(int seq, PaymentEventType type, PaymentStatus from, PaymentStatus to,
            BigDecimal amount, String reason, Instant occurredAt) {
        return new PaymentEvent(UUID.randomUUID(), payment.getId(), seq, type, from, to, amount,
                amount != null ? "GBP" : null, reason, null, "system:processor-simulator", "corr-" + seq,
                PaymentEventSource.PROCESSOR, occurredAt, occurredAt);
    }
}

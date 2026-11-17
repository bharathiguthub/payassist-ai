package com.payassist.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payassist.customer.CustomerDirectory;
import com.payassist.customer.CustomerSummary;
import com.payassist.payment.PaymentMethod;
import com.payassist.payment.PaymentStatus;
import com.payassist.payment.PaymentViewedEvent;
import com.payassist.shared.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class PaymentQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final CustomerSummary CUSTOMER =
            new CustomerSummary(CUSTOMER_ID, "CUS-00001", "Avery Testwell", "c***@example.com", "GB");

    @Mock
    private PaymentRepository payments;
    @Mock
    private CustomerDirectory customers;
    @Mock
    private ApplicationEventPublisher events;

    private PaymentQueryService service() {
        return new PaymentQueryService(payments, customers, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchEnrichesResultsWithCustomerSummaries() {
        when(customers.findIdsByRefPrefix("PAY-0000", PaymentQueryService.MAX_CUSTOMER_MATCHES)).thenReturn(List.of());
        when(payments.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(payment("PAY-000001")), inv.getArgument(1), 1));
        when(customers.findSummaries(anyCollection())).thenReturn(Map.of(CUSTOMER_ID, CUSTOMER));

        var page = service().search(" pay-0000 ", null, 0, 20);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).singleElement().satisfies(item -> {
            assertThat(item.paymentRef()).isEqualTo("PAY-000001");
            assertThat(item.customerRef()).isEqualTo("CUS-00001");
            assertThat(item.customerName()).isEqualTo("Avery Testwell");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchWithoutQuerySkipsCustomerLookup() {
        when(payments.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(), inv.getArgument(1), 0));
        when(customers.findSummaries(anyCollection())).thenReturn(Map.of());

        var page = service().search(null, PaymentStatus.FAILED, 0, 20);

        assertThat(page.content()).isEmpty();
        verify(customers, never()).findIdsByRefPrefix(any(), anyInt());
    }

    @Test
    void viewDetailsPublishesAuditEvent() {
        when(payments.findByPaymentRef("PAY-000001")).thenReturn(Optional.of(payment("PAY-000001")));
        when(customers.findSummary(CUSTOMER_ID)).thenReturn(Optional.of(CUSTOMER));

        var details = service().viewDetails("pay-000001", "agent.alice");

        assertThat(details.paymentRef()).isEqualTo("PAY-000001");
        assertThat(details.customer()).isEqualTo(CUSTOMER);
        verify(events).publishEvent(eq(new PaymentViewedEvent("PAY-000001", "agent.alice", NOW)));
    }

    @Test
    void viewDetailsOfUnknownPaymentThrowsNotFoundAndDoesNotAudit() {
        when(payments.findByPaymentRef("PAY-999999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().viewDetails("PAY-999999", "agent.alice"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void statsSumsStatusCounts() {
        when(payments.countByStatus()).thenReturn(List.of(
                new StatusCount(PaymentStatus.SETTLED, 10), new StatusCount(PaymentStatus.FAILED, 3)));

        var stats = service().stats();

        assertThat(stats.totalPayments()).isEqualTo(13);
        assertThat(stats.byStatus()).hasSize(2);
    }

    @Test
    void escapesLikeWildcards() {
        assertThat(PaymentSpecifications.escapeLike("PAY_%\\")).isEqualTo("PAY\\_\\%\\\\");
    }

    private static Payment payment(String ref) {
        return new Payment(UUID.randomUUID(), ref, CUSTOMER_ID, new BigDecimal("42.50"), "GBP",
                PaymentStatus.SETTLED, PaymentMethod.CARD, "Synthetic Electronics Ltd", null, NOW, NOW);
    }
}

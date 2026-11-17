package com.payassist.payment.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payassist.payment.PaymentEventSource;
import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentMethod;
import com.payassist.payment.PaymentStatus;
import com.payassist.payment.RecordPaymentEvent;
import com.payassist.payment.RecordedPaymentEvent;
import com.payassist.shared.Actor;
import com.payassist.shared.DomainException;
import com.payassist.shared.InvalidStateTransitionException;
import com.payassist.shared.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.ConcurrencyFailureException;

@ExtendWith(MockitoExtension.class)
class PaymentLedgerServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final Actor AGENT = Actor.user("sub-alice", "agent.alice");
    private static final Actor PROCESSOR = Actor.system("processor-simulator");

    @Mock
    private PaymentRepository payments;

    @Mock
    private PaymentEventRepository events;

    private PaymentLedgerService ledger;

    @BeforeEach
    void setUp() {
        ledger = new PaymentLedgerService(payments, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void appendsEventAndMovesStatus() {
        Payment payment = givenPayment(PaymentStatus.AUTHORIZED, PaymentMethod.CARD, "100.00");
        when(events.findMaxSequenceNo(payment.getId())).thenReturn(2);
        when(events.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RecordedPaymentEvent result = ledger.record(
                RecordPaymentEvent.of("pay-000001", PaymentEventType.CAPTURED, PROCESSOR, PaymentEventSource.PROCESSOR));

        assertThat(result.paymentRef()).isEqualTo("PAY-000001");
        assertThat(result.sequenceNo()).isEqualTo(3);
        assertThat(result.fromStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(result.toStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getUpdatedAt()).isEqualTo(NOW);

        ArgumentCaptor<PaymentEvent> saved = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(events).save(saved.capture());
        assertThat(saved.getValue().getActorUsername()).isEqualTo("system:processor-simulator");
        assertThat(saved.getValue().getActorSubject()).isNull();
        assertThat(saved.getValue().getOccurredAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getRecordedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsInvalidTransitionWithoutWritingAnything() {
        givenPayment(PaymentStatus.FAILED, PaymentMethod.CARD, "100.00");

        assertThatThrownBy(() -> ledger.record(RecordPaymentEvent.of("PAY-000001", PaymentEventType.CAPTURED,
                PROCESSOR, PaymentEventSource.PROCESSOR)))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Payment PAY-000001 cannot apply CAPTURED in state FAILED")
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("INVALID_STATE_TRANSITION");

        verify(events, never()).save(any());
    }

    @Test
    void systemOnlyEventsCannotBeRecorded() {
        assertThatThrownBy(() -> ledger.record(RecordPaymentEvent.of("PAY-000001", PaymentEventType.CREATED,
                PROCESSOR, PaymentEventSource.PROCESSOR)))
                .isInstanceOf(DomainException.RuleViolation.class)
                .hasMessageContaining("CREATED");
        verify(payments, never()).findByPaymentRefForUpdate(any());
    }

    @Test
    void unknownPaymentIsNotFound() {
        when(payments.findByPaymentRefForUpdate("PAY-999999")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> ledger.record(RecordPaymentEvent.of("PAY-999999", PaymentEventType.SETTLED,
                PROCESSOR, PaymentEventSource.PROCESSOR))).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void staleExpectedVersionIsAConcurrencyFailure() {
        givenPayment(PaymentStatus.CAPTURED, PaymentMethod.CARD, "100.00"); // version 0

        assertThatThrownBy(() -> ledger.record(RecordPaymentEvent.of("PAY-000001", PaymentEventType.SETTLED,
                PROCESSOR, PaymentEventSource.PROCESSOR).expectingVersion(7)))
                .isInstanceOf(ConcurrencyFailureException.class);
        verify(events, never()).save(any());
    }

    @Test
    void partialThenFullRefund() {
        Payment payment = givenPayment(PaymentStatus.SETTLED, PaymentMethod.CARD, "100.00");
        when(events.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(events.sumAmountByType(payment.getId(), PaymentEventType.REFUND_RECORDED))
                .thenReturn(BigDecimal.ZERO, new BigDecimal("40.00"));

        RecordPaymentEvent refund = RecordPaymentEvent.of("PAY-000001", PaymentEventType.REFUND_RECORDED, AGENT,
                PaymentEventSource.PAYASSIST).withReason("CUSTOMER_REQUEST");

        assertThat(ledger.record(refund.withAmount(new BigDecimal("40"), "GBP")).toStatus())
                .isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(ledger.record(refund.withAmount(new BigDecimal("60.00"), "GBP")).toStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void cumulativeRefundsCannotExceedPaymentAmount() {
        Payment payment = givenPayment(PaymentStatus.PARTIALLY_REFUNDED, PaymentMethod.CARD, "100.00");
        when(events.sumAmountByType(payment.getId(), PaymentEventType.REFUND_RECORDED))
                .thenReturn(new BigDecimal("80.00"));

        assertThatThrownBy(() -> ledger.record(RecordPaymentEvent.of("PAY-000001", PaymentEventType.REFUND_RECORDED,
                AGENT, PaymentEventSource.PAYASSIST).withAmount(new BigDecimal("20.01"), "GBP")))
                .isInstanceOf(DomainException.RuleViolation.class)
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo("REFUND_EXCEEDS_PAYMENT_AMOUNT");
        verify(events, never()).save(any());
    }

    @Test
    void refundAmountRules() {
        givenPayment(PaymentStatus.SETTLED, PaymentMethod.CARD, "100.00");
        RecordPaymentEvent refund = RecordPaymentEvent.of("PAY-000001", PaymentEventType.REFUND_RECORDED, AGENT,
                PaymentEventSource.PAYASSIST);

        assertCode(() -> ledger.record(refund), "AMOUNT_REQUIRED");
        assertCode(() -> ledger.record(refund.withAmount(new BigDecimal("10.00"), "USD")), "CURRENCY_MISMATCH");
        assertCode(() -> ledger.record(refund.withAmount(new BigDecimal("10.001"), "GBP")), "INVALID_AMOUNT_SCALE");
        assertCode(() -> ledger.record(refund.withAmount(new BigDecimal("-1.00"), "GBP")), "INVALID_AMOUNT");
        assertCode(() -> ledger.record(refund.withAmount(new BigDecimal("100.01"), "GBP")), "AMOUNT_EXCEEDS_PAYMENT");
        verify(events, never()).save(any());
    }

    @Test
    void failedEventNeedsReasonCodeAndStoresIt() {
        Payment payment = givenPayment(PaymentStatus.PENDING, PaymentMethod.CARD, "100.00");
        when(events.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RecordPaymentEvent failed = RecordPaymentEvent.of("PAY-000001", PaymentEventType.FAILED, PROCESSOR,
                PaymentEventSource.PROCESSOR);

        assertCode(() -> ledger.record(failed), "REASON_CODE_REQUIRED");
        assertCode(() -> ledger.record(failed.withReason("not-a-code")), "INVALID_REASON_CODE");

        ledger.record(failed.withReason("ISSUER_DECLINED"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("ISSUER_DECLINED");
    }

    @Test
    void wonDisputeRestoresStatusFromDisputeOpenedEvent() {
        Payment payment = givenPayment(PaymentStatus.DISPUTED, PaymentMethod.CARD, "100.00");
        when(events.save(any())).thenAnswer(inv -> inv.getArgument(0));
        PaymentEvent opened = new PaymentEvent(UUID.randomUUID(), payment.getId(), 5, PaymentEventType.DISPUTE_OPENED,
                PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.DISPUTED, null, null, "FRAUDULENT", null,
                "system:card-network-simulator", null, PaymentEventSource.CARD_NETWORK, NOW, NOW);
        when(events.findFirstByPaymentIdAndEventTypeOrderBySequenceNoDesc(payment.getId(),
                PaymentEventType.DISPUTE_OPENED)).thenReturn(Optional.of(opened));

        RecordedPaymentEvent result = ledger.record(RecordPaymentEvent.of("PAY-000001", PaymentEventType.DISPUTE_WON,
                Actor.system("card-network-simulator"), PaymentEventSource.CARD_NETWORK));

        assertThat(result.toStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    void wonDisputeOnMigratedPaymentWithoutHistoryFallsBackToSettled() {
        Payment payment = givenPayment(PaymentStatus.DISPUTED, PaymentMethod.CARD, "100.00");
        when(events.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(events.findFirstByPaymentIdAndEventTypeOrderBySequenceNoDesc(payment.getId(),
                PaymentEventType.DISPUTE_OPENED)).thenReturn(Optional.empty());
        when(events.sumAmountByType(payment.getId(), PaymentEventType.REFUND_RECORDED)).thenReturn(BigDecimal.ZERO);

        assertThat(ledger.record(RecordPaymentEvent.of("PAY-000001", PaymentEventType.DISPUTE_WON,
                Actor.system("card-network-simulator"), PaymentEventSource.CARD_NETWORK)).toStatus())
                .isEqualTo(PaymentStatus.SETTLED);
    }

    @Test
    void informationalEventKeepsStatusAndStoresNoTargetStatus() {
        Payment payment = givenPayment(PaymentStatus.SETTLED, PaymentMethod.CARD, "100.00");
        when(events.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RecordedPaymentEvent result = ledger.record(RecordPaymentEvent.of("PAY-000001",
                PaymentEventType.REFUND_REQUESTED, AGENT, PaymentEventSource.PAYASSIST)
                .withAmount(new BigDecimal("25.00"), "GBP"));

        assertThat(result.toStatus()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SETTLED);
        ArgumentCaptor<PaymentEvent> saved = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(events).save(saved.capture());
        assertThat(saved.getValue().getToStatus()).isNull();
        assertThat(saved.getValue().getActorSubject()).isEqualTo("sub-alice");
    }

    @Test
    void rejectsEventTimesInTheFuture() {
        assertCode(() -> ledger.record(RecordPaymentEvent.of("PAY-000001", PaymentEventType.SETTLED, PROCESSOR,
                PaymentEventSource.PROCESSOR).occurredAt(NOW.plusSeconds(3600))), "OCCURRED_AT_IN_FUTURE");
    }

    private Payment givenPayment(PaymentStatus status, PaymentMethod method, String amount) {
        Payment payment = new Payment(UUID.randomUUID(), "PAY-000001", UUID.randomUUID(), new BigDecimal(amount), "GBP",
                status, method, "Demo Travel Co", null, NOW.minusSeconds(86_400), NOW.minusSeconds(86_400));
        when(payments.findByPaymentRefForUpdate("PAY-000001")).thenReturn(Optional.of(payment));
        return payment;
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getCode()).isEqualTo(code);
    }
}

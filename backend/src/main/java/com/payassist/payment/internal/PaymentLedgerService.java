package com.payassist.payment.internal;

import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentLedger;
import com.payassist.payment.PaymentStatus;
import com.payassist.payment.RecordPaymentEvent;
import com.payassist.payment.RecordedPaymentEvent;
import com.payassist.shared.DomainException;
import com.payassist.shared.InvalidStateTransitionException;
import com.payassist.shared.MonetaryAmounts;
import com.payassist.shared.ResourceNotFoundException;
import com.payassist.shared.web.CorrelationIdFilter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link PaymentLedger}. Order of work inside one transaction:
 * validate the command → lock the payment row → check version → check the transition and amount
 * invariants against committed history → append the event → update the payment.
 */
@Service
class PaymentLedgerService implements PaymentLedger {

    static final String RESOURCE = "Payment";
    /** Tolerated clock skew for processor timestamps. */
    static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private static final Pattern REASON_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    private final PaymentRepository payments;
    private final PaymentEventRepository events;
    private final Clock clock;

    PaymentLedgerService(PaymentRepository payments, PaymentEventRepository events, Clock clock) {
        this.payments = payments;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RecordedPaymentEvent record(RecordPaymentEvent command) {
        PaymentEventType type = command.type();
        if (type.kind() == PaymentEventType.Kind.SYSTEM_ONLY) {
            throw new DomainException.RuleViolation("EVENT_TYPE_NOT_RECORDABLE",
                    type + " events are written only by the processor feed or migrations");
        }
        validateReasonCode(command);
        Instant now = Instant.now(clock);
        Instant occurredAt = command.occurredAt() != null ? command.occurredAt() : now;
        if (occurredAt.isAfter(now.plus(MAX_FUTURE_SKEW))) {
            throw new DomainException.RuleViolation("OCCURRED_AT_IN_FUTURE", "Event time is in the future");
        }

        String paymentRef = command.paymentRef().toUpperCase();
        Payment payment = payments.findByPaymentRefForUpdate(paymentRef)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, paymentRef));

        if (command.expectedVersion() != null && command.expectedVersion() != payment.getVersion()) {
            throw new ObjectOptimisticLockingFailureException(Payment.class, payment.getId());
        }

        PaymentStatus from = payment.getStatus();
        if (!PaymentTransitions.isAllowed(from, type, payment.getPaymentMethod())) {
            throw new InvalidStateTransitionException(RESOURCE, paymentRef, from, type);
        }

        BigDecimal amount = validatedAmount(command, payment);
        PaymentTransitions.Context context = transitionContext(type, payment, amount);
        PaymentStatus to = PaymentTransitions.target(from, type, payment.getPaymentMethod(), context);

        int sequenceNo = events.findMaxSequenceNo(payment.getId()) + 1;
        PaymentEvent event = events.save(new PaymentEvent(UUID.randomUUID(), payment.getId(), sequenceNo, type,
                from, type.changesStatus() ? to : null, amount, amount != null ? payment.getCurrency() : null,
                command.reasonCode(), command.actor().subject(), command.actor().username(),
                MDC.get(CorrelationIdFilter.MDC_KEY), command.source(), occurredAt, now));

        payment.applyEvent(to, type == PaymentEventType.FAILED ? command.reasonCode() : null, now);
        payments.flush(); // increments the version now, so the result reports the committed value

        return new RecordedPaymentEvent(event.getId(), paymentRef, sequenceNo, type, from, to, payment.getVersion());
    }

    private static void validateReasonCode(RecordPaymentEvent command) {
        String reason = command.reasonCode();
        if (reason != null && !REASON_CODE.matcher(reason).matches()) {
            throw new DomainException.RuleViolation("INVALID_REASON_CODE", "Reason code must be UPPER_SNAKE_CASE");
        }
        if (reason == null && command.type() == PaymentEventType.FAILED) {
            throw new DomainException.RuleViolation("REASON_CODE_REQUIRED", "A FAILED event needs a reason code");
        }
    }

    private static BigDecimal validatedAmount(RecordPaymentEvent command, Payment payment) {
        if (command.amount() == null) {
            if (command.type() == PaymentEventType.REFUND_RECORDED) {
                throw new DomainException.RuleViolation("AMOUNT_REQUIRED", "A refund needs an amount");
            }
            return null;
        }
        if (!payment.getCurrency().equals(command.currency())) {
            throw new DomainException.RuleViolation("CURRENCY_MISMATCH",
                    "Amount currency must match the payment currency " + payment.getCurrency());
        }
        BigDecimal amount = MonetaryAmounts.requirePositive(command.amount(), command.currency());
        if (amount.compareTo(payment.getAmount()) > 0) {
            throw new DomainException.RuleViolation("AMOUNT_EXCEEDS_PAYMENT", "Amount exceeds the payment amount");
        }
        return amount;
    }

    private PaymentTransitions.Context transitionContext(PaymentEventType type, Payment payment, BigDecimal amount) {
        return switch (type) {
            case REFUND_RECORDED -> {
                // Read under the payment row lock, so no concurrent refund can be appended in between.
                BigDecimal refundedAfter = refundedSoFar(payment).add(amount);
                int comparison = refundedAfter.compareTo(payment.getAmount());
                if (comparison > 0) {
                    throw new DomainException.RuleViolation("REFUND_EXCEEDS_PAYMENT_AMOUNT",
                            "Total refunds would exceed the payment amount");
                }
                yield new PaymentTransitions.Context(comparison == 0, null);
            }
            case DISPUTE_WON -> new PaymentTransitions.Context(false, statusBeforeDispute(payment));
            default -> PaymentTransitions.Context.NONE;
        };
    }

    private BigDecimal refundedSoFar(Payment payment) {
        return events.sumAmountByType(payment.getId(), PaymentEventType.REFUND_RECORDED);
    }

    /**
     * The status to restore when a dispute is won: the status the payment had when the dispute opened.
     * Payments migrated from Phase 1 may lack a DISPUTE_OPENED event; then it is derived from the
     * refund history (disputes can only open on SETTLED or PARTIALLY_REFUNDED payments).
     */
    private PaymentStatus statusBeforeDispute(Payment payment) {
        return events.findFirstByPaymentIdAndEventTypeOrderBySequenceNoDesc(payment.getId(),
                        PaymentEventType.DISPUTE_OPENED)
                .map(PaymentEvent::getFromStatus)
                .orElseGet(() -> refundedSoFar(payment).signum() > 0
                        ? PaymentStatus.PARTIALLY_REFUNDED
                        : PaymentStatus.SETTLED);
    }
}

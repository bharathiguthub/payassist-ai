package com.payassist.payment.internal;

import com.payassist.payment.PaymentEventType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Append-only access to payment history: there is deliberately no delete or update method
 * (a plain {@link Repository}, not {@code JpaRepository}).
 */
public interface PaymentEventRepository extends Repository<PaymentEvent, UUID> {

    PaymentEvent save(PaymentEvent event);

    /** Chronological history; {@code sequenceNo} breaks ties between events with the same timestamp. */
    List<PaymentEvent> findByPaymentIdOrderByOccurredAtAscSequenceNoAsc(UUID paymentId);

    @Query("select coalesce(max(e.sequenceNo), 0) from PaymentEvent e where e.paymentId = :paymentId")
    int findMaxSequenceNo(UUID paymentId);

    @Query("select coalesce(sum(e.amount), 0) from PaymentEvent e "
            + "where e.paymentId = :paymentId and e.eventType = :eventType")
    BigDecimal sumAmountByType(UUID paymentId, PaymentEventType eventType);

    Optional<PaymentEvent> findFirstByPaymentIdAndEventTypeOrderBySequenceNoDesc(UUID paymentId,
            PaymentEventType eventType);
}

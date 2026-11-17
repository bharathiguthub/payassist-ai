package com.payassist.payment.internal;

import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentHistory.RefundPosition;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Builds a {@link RefundPosition} from a payment and its history. The single implementation used by both
 * the read API (eligibility checks) and the ledger's locked read (refund writes), so they cannot disagree.
 */
final class RefundPositions {

    private RefundPositions() {
    }

    static RefundPosition of(Payment p, PaymentEventRepository events) {
        UUID id = p.getId();
        Instant capturedAt = events.findFirstByPaymentIdAndEventTypeOrderByOccurredAtAscSequenceNoAsc(id,
                PaymentEventType.CAPTURED).map(PaymentEvent::getOccurredAt).orElse(null);
        BigDecimal refunded = events.sumAmountByType(id, PaymentEventType.REFUND_RECORDED)
                .setScale(p.getAmount().scale());
        int refundCount = (int) events.countByPaymentIdAndEventType(id, PaymentEventType.REFUND_RECORDED);
        boolean historyComplete = events.countByPaymentIdAndEventType(id, PaymentEventType.LEGACY_SNAPSHOT) == 0;
        return new RefundPosition(id, p.getPaymentRef(), p.getAmount(), p.getCurrency(), p.getStatus(),
                p.getPaymentMethod(), p.getCreatedAt(), capturedAt, refunded, refundCount,
                PaymentTransitions.isAllowed(p.getStatus(), PaymentEventType.REFUND_RECORDED, p.getPaymentMethod()),
                historyComplete, p.getVersion());
    }
}

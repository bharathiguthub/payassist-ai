package com.payassist.refund.internal;

import com.payassist.refund.RefundRequestStatus;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Reservations = sum of refund requests that are still awaiting a decision. */
@Component
class PendingRefundReservations implements RefundReservations {

    static final Set<RefundRequestStatus> ACTIVE = EnumSet.of(RefundRequestStatus.PENDING_APPROVAL,
            RefundRequestStatus.ESCALATION_REQUIRED);

    private final RefundRequestRepository requests;

    PendingRefundReservations(RefundRequestRepository requests) {
        this.requests = requests;
    }

    @Override
    public BigDecimal reservedAmount(UUID paymentId) {
        return requests.sumAmount(paymentId, ACTIVE);
    }
}

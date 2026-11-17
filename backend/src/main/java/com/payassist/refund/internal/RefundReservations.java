package com.payassist.refund.internal;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Amount of a payment reserved by refund requests that are awaiting a decision (PENDING_APPROVAL or
 * ESCALATION_REQUIRED). When a refund request is created this is read under the payment row lock, so two
 * pending requests can never together exceed the refundable amount.
 */
public interface RefundReservations {

    /** Reserved amount in the payment's currency, at the currency's scale; never negative. */
    BigDecimal reservedAmount(UUID paymentId);
}

package com.payassist.refund.internal;

import java.math.BigDecimal;

/**
 * Amount of a payment already reserved by refund requests that are awaiting approval.
 *
 * <p>Milestone 3 has no refund requests yet, so the only implementation returns zero. Milestone 4 replaces
 * it with a query over pending {@code refund_requests}, read under the payment row lock when a request is
 * created, so that two pending requests can never together exceed the refundable amount.
 */
public interface RefundReservations {

    /** Reserved amount in the payment's currency, at the currency's scale; never negative. */
    BigDecimal reservedAmount(String paymentRef);
}

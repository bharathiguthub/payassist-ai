package com.payassist.payment;

/**
 * Public write API of the payment module: the only way to change a payment's status or append to its
 * history. Other modules (refund, dispute) call it inside their own transaction, so their own state
 * change and the payment event commit or roll back together.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>only transitions allowed by the payment state machine are applied, otherwise
 *       {@code InvalidStateTransitionException} (HTTP 409) and nothing is written;
 *   <li>the payment row is locked while the event is validated and appended, so concurrent writers are
 *       serialised and invariants hold (e.g. total recorded refunds never exceed the payment amount);
 *   <li>history is append-only; the actor, source, reason and correlation ID are recorded with each event;
 *   <li>no money moves: refunds and disputes are recorded facts (simulated in PayAssist).
 * </ul>
 *
 * <p>This is a module-internal service API, not an endpoint. Callers authorise the user first.
 */
public interface PaymentLedger {

    RecordedPaymentEvent record(RecordPaymentEvent command);
}

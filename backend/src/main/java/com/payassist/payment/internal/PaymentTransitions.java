package com.payassist.payment.internal;

import static com.payassist.payment.PaymentStatus.AUTHORIZED;
import static com.payassist.payment.PaymentStatus.CAPTURED;
import static com.payassist.payment.PaymentStatus.CHARGED_BACK;
import static com.payassist.payment.PaymentStatus.DISPUTED;
import static com.payassist.payment.PaymentStatus.FAILED;
import static com.payassist.payment.PaymentStatus.PARTIALLY_REFUNDED;
import static com.payassist.payment.PaymentStatus.PENDING;
import static com.payassist.payment.PaymentStatus.REFUNDED;
import static com.payassist.payment.PaymentStatus.SETTLED;
import static com.payassist.payment.PaymentStatus.VOIDED;

import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentMethod;
import com.payassist.payment.PaymentStatus;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The payment state machine, as data. A lifecycle event is allowed only from the statuses listed for
 * it; the target status is fixed except where noted:
 *
 * <pre>
 * PENDING ──AUTHORIZED──► AUTHORIZED ──CAPTURED──► CAPTURED ──SETTLED──► SETTLED
 *    │                       │  │                     │                   │  ▲
 *    └──FAILED──► FAILED ◄───┘  └──VOIDED──► VOIDED   │ REFUND_RECORDED   │  │ DISPUTE_WON (restores
 *                              (not BANK_TRANSFER)    ▼                   ▼  │  the pre-dispute status)
 *                                  PARTIALLY_REFUNDED / REFUNDED   DISPUTE_OPENED ──► DISPUTED
 *                                  (by cumulative refunded amount)                       │ DISPUTE_LOST
 *                                                                                        ▼
 *                                                                                  CHARGED_BACK
 * </pre>
 *
 * FAILED, VOIDED, REFUNDED and CHARGED_BACK are terminal. Informational events never change status
 * and are allowed in every status (they record workflow facts such as a rejected refund request).
 * System-only events ({@code CREATED}, {@code LEGACY_SNAPSHOT}) are never allowed here.
 */
public final class PaymentTransitions {

    private static final Map<PaymentEventType, Set<PaymentStatus>> ALLOWED_FROM = new EnumMap<>(PaymentEventType.class);

    static {
        ALLOWED_FROM.put(PaymentEventType.AUTHORIZED, EnumSet.of(PENDING));
        ALLOWED_FROM.put(PaymentEventType.CAPTURED, EnumSet.of(AUTHORIZED));
        ALLOWED_FROM.put(PaymentEventType.SETTLED, EnumSet.of(CAPTURED));
        ALLOWED_FROM.put(PaymentEventType.FAILED, EnumSet.of(PENDING, AUTHORIZED));
        ALLOWED_FROM.put(PaymentEventType.VOIDED, EnumSet.of(AUTHORIZED));
        ALLOWED_FROM.put(PaymentEventType.REFUND_RECORDED, EnumSet.of(CAPTURED, SETTLED, PARTIALLY_REFUNDED));
        ALLOWED_FROM.put(PaymentEventType.DISPUTE_OPENED, EnumSet.of(SETTLED, PARTIALLY_REFUNDED));
        ALLOWED_FROM.put(PaymentEventType.DISPUTE_WON, EnumSet.of(DISPUTED));
        ALLOWED_FROM.put(PaymentEventType.DISPUTE_LOST, EnumSet.of(DISPUTED));
    }

    private PaymentTransitions() {
    }

    /** Facts needed by the two transitions whose target depends on payment history. */
    public record Context(boolean fullyRefundedAfterEvent, PaymentStatus statusBeforeDispute) {

        public static final Context NONE = new Context(false, null);
    }

    public static boolean isAllowed(PaymentStatus current, PaymentEventType event, PaymentMethod method) {
        return switch (event.kind()) {
            case SYSTEM_ONLY -> false;
            case INFORMATIONAL -> true;
            case LIFECYCLE -> ALLOWED_FROM.get(event).contains(current)
                    // Bank transfers have no card-style authorization hold that could be released.
                    && !(event == PaymentEventType.VOIDED && method == PaymentMethod.BANK_TRANSFER);
        };
    }

    /**
     * The status after {@code event}. Callers must check {@link #isAllowed} first.
     *
     * @throws IllegalStateException if the transition is not allowed (a programming error)
     */
    public static PaymentStatus target(PaymentStatus current, PaymentEventType event, PaymentMethod method,
            Context context) {
        if (!isAllowed(current, event, method)) {
            throw new IllegalStateException(event + " is not allowed from " + current);
        }
        return switch (event) {
            case AUTHORIZED -> AUTHORIZED;
            case CAPTURED -> CAPTURED;
            case SETTLED -> SETTLED;
            case FAILED -> FAILED;
            case VOIDED -> VOIDED;
            case REFUND_RECORDED -> context.fullyRefundedAfterEvent() ? REFUNDED : PARTIALLY_REFUNDED;
            case DISPUTE_OPENED -> DISPUTED;
            case DISPUTE_WON -> context.statusBeforeDispute() != null ? context.statusBeforeDispute() : SETTLED;
            case DISPUTE_LOST -> CHARGED_BACK;
            default -> current; // informational
        };
    }

    /** Lifecycle events that may happen next. Useful for UIs and, later, AI tools ("what can happen now?"). */
    public static Set<PaymentEventType> nextLifecycleEvents(PaymentStatus current, PaymentMethod method) {
        Set<PaymentEventType> result = EnumSet.noneOf(PaymentEventType.class);
        for (PaymentEventType event : ALLOWED_FROM.keySet()) {
            if (isAllowed(current, event, method)) {
                result.add(event);
            }
        }
        return Collections.unmodifiableSet(result);
    }
}

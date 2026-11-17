package com.payassist.payment.internal;

import static com.payassist.payment.PaymentEventType.AUTHORIZED;
import static com.payassist.payment.PaymentEventType.CAPTURED;
import static com.payassist.payment.PaymentEventType.CREATED;
import static com.payassist.payment.PaymentEventType.DISPUTE_LOST;
import static com.payassist.payment.PaymentEventType.DISPUTE_OPENED;
import static com.payassist.payment.PaymentEventType.DISPUTE_WON;
import static com.payassist.payment.PaymentEventType.FAILED;
import static com.payassist.payment.PaymentEventType.LEGACY_SNAPSHOT;
import static com.payassist.payment.PaymentEventType.REFUND_RECORDED;
import static com.payassist.payment.PaymentEventType.SETTLED;
import static com.payassist.payment.PaymentEventType.VOIDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentMethod;
import com.payassist.payment.PaymentStatus;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentTransitionsTest {

    /** The complete specification: every allowed (status, lifecycle event) pair and its target. */
    private static final Map<PaymentStatus, Map<PaymentEventType, PaymentStatus>> ALLOWED = Map.of(
            PaymentStatus.PENDING, Map.of(AUTHORIZED, PaymentStatus.AUTHORIZED, FAILED, PaymentStatus.FAILED),
            PaymentStatus.AUTHORIZED, Map.of(CAPTURED, PaymentStatus.CAPTURED, FAILED, PaymentStatus.FAILED,
                    VOIDED, PaymentStatus.VOIDED),
            PaymentStatus.CAPTURED, Map.of(SETTLED, PaymentStatus.SETTLED,
                    REFUND_RECORDED, PaymentStatus.PARTIALLY_REFUNDED),
            PaymentStatus.SETTLED, Map.of(REFUND_RECORDED, PaymentStatus.PARTIALLY_REFUNDED,
                    DISPUTE_OPENED, PaymentStatus.DISPUTED),
            PaymentStatus.PARTIALLY_REFUNDED, Map.of(REFUND_RECORDED, PaymentStatus.PARTIALLY_REFUNDED,
                    DISPUTE_OPENED, PaymentStatus.DISPUTED),
            PaymentStatus.DISPUTED, Map.of(DISPUTE_WON, PaymentStatus.SETTLED, DISPUTE_LOST, PaymentStatus.CHARGED_BACK));

    static Stream<Arguments> everyLifecycleCombination() {
        List<Arguments> all = new ArrayList<>();
        for (PaymentStatus status : PaymentStatus.values()) {
            for (PaymentEventType event : PaymentEventType.values()) {
                if (event.changesStatus()) {
                    all.add(Arguments.of(status, event));
                }
            }
        }
        return all.stream();
    }

    @ParameterizedTest(name = "{0} + {1}")
    @MethodSource("everyLifecycleCombination")
    void matchesSpecificationForEveryStatusAndLifecycleEvent(PaymentStatus status, PaymentEventType event) {
        PaymentStatus expected = ALLOWED.getOrDefault(status, Map.of()).get(event);

        assertThat(PaymentTransitions.isAllowed(status, event, PaymentMethod.CARD)).isEqualTo(expected != null);
        if (expected != null) {
            assertThat(PaymentTransitions.target(status, event, PaymentMethod.CARD, PaymentTransitions.Context.NONE))
                    .isEqualTo(expected);
        } else {
            assertThatThrownBy(() -> PaymentTransitions.target(status, event, PaymentMethod.CARD,
                    PaymentTransitions.Context.NONE)).isInstanceOf(IllegalStateException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"FAILED", "VOIDED", "REFUNDED", "CHARGED_BACK"})
    void terminalStatusesAllowNoLifecycleEvent(PaymentStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(PaymentTransitions.nextLifecycleEvents(terminal, PaymentMethod.CARD)).isEmpty();
    }

    @Test
    void fullRefundMovesToRefunded() {
        var fully = new PaymentTransitions.Context(true, null);
        assertThat(PaymentTransitions.target(PaymentStatus.SETTLED, REFUND_RECORDED, PaymentMethod.CARD, fully))
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(PaymentTransitions.target(PaymentStatus.PARTIALLY_REFUNDED, REFUND_RECORDED, PaymentMethod.WALLET,
                fully)).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void wonDisputeRestoresThePreDisputeStatus() {
        var context = new PaymentTransitions.Context(false, PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(PaymentTransitions.target(PaymentStatus.DISPUTED, DISPUTE_WON, PaymentMethod.CARD, context))
                .isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    void bankTransfersCannotBeVoided() {
        assertThat(PaymentTransitions.isAllowed(PaymentStatus.AUTHORIZED, VOIDED, PaymentMethod.BANK_TRANSFER)).isFalse();
        assertThat(PaymentTransitions.isAllowed(PaymentStatus.AUTHORIZED, VOIDED, PaymentMethod.WALLET)).isTrue();
        assertThat(PaymentTransitions.nextLifecycleEvents(PaymentStatus.AUTHORIZED, PaymentMethod.BANK_TRANSFER))
                .containsExactlyInAnyOrder(CAPTURED, FAILED);
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void systemOnlyEventsAreNeverAllowedAndInformationalEventsNeverChangeStatus(PaymentStatus status) {
        assertThat(PaymentTransitions.isAllowed(status, CREATED, PaymentMethod.CARD)).isFalse();
        assertThat(PaymentTransitions.isAllowed(status, LEGACY_SNAPSHOT, PaymentMethod.CARD)).isFalse();

        Set<PaymentEventType> informational = EnumSet.noneOf(PaymentEventType.class);
        for (PaymentEventType type : PaymentEventType.values()) {
            if (type.kind() == PaymentEventType.Kind.INFORMATIONAL) {
                informational.add(type);
            }
        }
        assertThat(informational).isNotEmpty();
        for (PaymentEventType type : informational) {
            assertThat(PaymentTransitions.isAllowed(status, type, PaymentMethod.CARD)).isTrue();
            assertThat(PaymentTransitions.target(status, type, PaymentMethod.CARD, PaymentTransitions.Context.NONE))
                    .isEqualTo(status);
        }
    }
}

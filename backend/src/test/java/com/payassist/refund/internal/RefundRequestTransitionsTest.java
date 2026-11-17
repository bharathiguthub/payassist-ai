package com.payassist.refund.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.payassist.refund.RefundRequestStatus;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RefundRequestTransitionsTest {

    @ParameterizedTest
    @EnumSource(RefundRequestStatus.class)
    void matchesTheSpecification(RefundRequestStatus from) {
        Set<RefundRequestStatus> expected = switch (from) {
            case PENDING_APPROVAL -> Set.of(RefundRequestStatus.APPROVED, RefundRequestStatus.REJECTED);
            // Approval of escalated requests needs a future REFUND_MANAGER role: not allowed today.
            case ESCALATION_REQUIRED -> Set.of(RefundRequestStatus.REJECTED);
            case APPROVED, REJECTED -> Set.of();
        };
        for (RefundRequestStatus to : RefundRequestStatus.values()) {
            assertThat(RefundRequestTransitions.isAllowed(from, to)).as(from + " -> " + to)
                    .isEqualTo(expected.contains(to));
        }
        assertThat(from.isFinal()).isEqualTo(expected.isEmpty());
        assertThat(from.reservesAmount()).isEqualTo(!from.isFinal());
    }
}

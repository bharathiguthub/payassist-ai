package com.payassist.refund.internal;

import static com.payassist.refund.RefundRequestStatus.APPROVED;
import static com.payassist.refund.RefundRequestStatus.ESCALATION_REQUIRED;
import static com.payassist.refund.RefundRequestStatus.PENDING_APPROVAL;
import static com.payassist.refund.RefundRequestStatus.REJECTED;

import com.payassist.refund.RefundRequestStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Allowed refund request transitions, as data (see {@link RefundRequestStatus} for the diagram).
 * ESCALATION_REQUIRED → APPROVED is deliberately absent: it needs a REFUND_MANAGER role that does not exist yet.
 */
public final class RefundRequestTransitions {

    private static final Map<RefundRequestStatus, Set<RefundRequestStatus>> ALLOWED = new EnumMap<>(Map.of(
            PENDING_APPROVAL, EnumSet.of(APPROVED, REJECTED),
            ESCALATION_REQUIRED, EnumSet.of(REJECTED),
            APPROVED, EnumSet.noneOf(RefundRequestStatus.class),
            REJECTED, EnumSet.noneOf(RefundRequestStatus.class)));

    private RefundRequestTransitions() {
    }

    public static boolean isAllowed(RefundRequestStatus from, RefundRequestStatus to) {
        return ALLOWED.get(from).contains(to);
    }
}

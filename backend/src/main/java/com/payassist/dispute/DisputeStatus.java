package com.payassist.dispute;

/**
 * Lifecycle of a dispute case. This is the <em>case</em> status (investigation workflow), deliberately separate from
 * the payment's financial status: while a case is active the payment is DISPUTED (an investigation status; nothing
 * has been reversed). Only a recorded LOST outcome makes the payment CHARGED_BACK.
 *
 * <pre>
 * OPEN ─start─► UNDER_INVESTIGATION ─request review─► PENDING_REVIEW ─approve submission─► EVIDENCE_SUBMITTED
 *                      ▲                                    │                              │ record outcome
 *                      └──────── return for investigation ──┘                              ▼
 *                                                                                      WON | LOST
 * OPEN | UNDER_INVESTIGATION | PENDING_REVIEW ─accept liability─► LOST
 * any non-final ─withdraw─► WITHDRAWN
 * </pre>
 * WON, LOST and WITHDRAWN are final.
 */
public enum DisputeStatus {
    OPEN,
    UNDER_INVESTIGATION,
    PENDING_REVIEW,
    EVIDENCE_SUBMITTED,
    WON,
    LOST,
    WITHDRAWN;

    public boolean isFinal() {
        return this == WON || this == LOST || this == WITHDRAWN;
    }
}

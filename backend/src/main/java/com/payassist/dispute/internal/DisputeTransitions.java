package com.payassist.dispute.internal;

import static com.payassist.dispute.DisputeStatus.EVIDENCE_SUBMITTED;
import static com.payassist.dispute.DisputeStatus.LOST;
import static com.payassist.dispute.DisputeStatus.OPEN;
import static com.payassist.dispute.DisputeStatus.PENDING_REVIEW;
import static com.payassist.dispute.DisputeStatus.UNDER_INVESTIGATION;
import static com.payassist.dispute.DisputeStatus.WITHDRAWN;
import static com.payassist.dispute.DisputeStatus.WON;

import com.payassist.dispute.DisputeAction;
import com.payassist.dispute.DisputeStatus;
import com.payassist.payment.PaymentEventType;
import java.util.EnumSet;
import java.util.Set;

/**
 * The dispute case state machine as data. Each transition names the statuses it is allowed from, its target,
 * who may perform it, its separation-of-duties rule, and the payment ledger event it causes (lifecycle events
 * change the payment's financial status; the others add an informational DISPUTE_UPDATED to the timeline).
 */
public final class DisputeTransitions {

    /** Who may perform a transition (role rule; also enforced by URL rules and method security). */
    public enum Performer { CARE_STAFF, SUPERVISOR }

    /** Separation-of-duties rule, evaluated on immutable user IDs. */
    public enum Duty {
        NONE,
        /** Must not be the person who requested review (four-eyes on the evidence package). */
        NOT_REVIEW_REQUESTER,
        /** Must not be the person who opened the case (no one concedes their own case alone). */
        NOT_OPENER
    }

    public enum Transition {
        START_INVESTIGATION(DisputeAction.START_INVESTIGATION, EnumSet.of(OPEN), UNDER_INVESTIGATION,
                Performer.CARE_STAFF, Duty.NONE, null, "INVESTIGATION_STARTED"),
        REQUEST_REVIEW(DisputeAction.REQUEST_REVIEW, EnumSet.of(UNDER_INVESTIGATION), PENDING_REVIEW,
                Performer.CARE_STAFF, Duty.NONE, null, "REVIEW_REQUESTED"),
        RETURN_FOR_INVESTIGATION(DisputeAction.RETURN_FOR_INVESTIGATION, EnumSet.of(PENDING_REVIEW),
                UNDER_INVESTIGATION, Performer.SUPERVISOR, Duty.NONE, null, "RETURNED_FOR_INVESTIGATION"),
        APPROVE_SUBMISSION(DisputeAction.APPROVE_SUBMISSION, EnumSet.of(PENDING_REVIEW), EVIDENCE_SUBMITTED,
                Performer.SUPERVISOR, Duty.NOT_REVIEW_REQUESTER, null, "SUBMISSION_APPROVED"),
        RECORD_WON(DisputeAction.RECORD_OUTCOME, EnumSet.of(EVIDENCE_SUBMITTED), WON,
                Performer.SUPERVISOR, Duty.NONE, PaymentEventType.DISPUTE_WON, "OUTCOME_WON"),
        RECORD_LOST(DisputeAction.RECORD_OUTCOME, EnumSet.of(EVIDENCE_SUBMITTED), LOST,
                Performer.SUPERVISOR, Duty.NONE, PaymentEventType.DISPUTE_LOST, "OUTCOME_LOST"),
        ACCEPT_LIABILITY(DisputeAction.ACCEPT_LIABILITY, EnumSet.of(OPEN, UNDER_INVESTIGATION, PENDING_REVIEW), LOST,
                Performer.SUPERVISOR, Duty.NOT_OPENER, PaymentEventType.DISPUTE_LOST, "LIABILITY_ACCEPTED"),
        WITHDRAW(DisputeAction.WITHDRAW, EnumSet.of(OPEN, UNDER_INVESTIGATION, PENDING_REVIEW, EVIDENCE_SUBMITTED),
                WITHDRAWN, Performer.SUPERVISOR, Duty.NONE, PaymentEventType.DISPUTE_WITHDRAWN, "WITHDRAWN");

        public final DisputeAction action;
        public final Set<DisputeStatus> from;
        public final DisputeStatus to;
        public final Performer performer;
        public final Duty duty;
        /** Lifecycle event recorded in the payment ledger, or {@code null} for an informational DISPUTE_UPDATED. */
        public final PaymentEventType ledgerEvent;
        public final String historyEvent;

        Transition(DisputeAction action, Set<DisputeStatus> from, DisputeStatus to, Performer performer, Duty duty,
                PaymentEventType ledgerEvent, String historyEvent) {
            this.action = action;
            this.from = from;
            this.to = to;
            this.performer = performer;
            this.duty = duty;
            this.ledgerEvent = ledgerEvent;
            this.historyEvent = historyEvent;
        }

        public boolean allowedFrom(DisputeStatus status) {
            return from.contains(status);
        }
    }

    private DisputeTransitions() {
    }
}

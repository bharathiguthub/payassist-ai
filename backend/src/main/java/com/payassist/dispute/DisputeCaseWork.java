package com.payassist.dispute;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Case work by care staff (CARE_AGENT, CARE_SUPERVISOR): opening cases, notes, evidence and moving a case towards
 * supervisor review. Not exposed to AI tools in Phase 2 (only {@link DisputeQueries} is tool-ready).
 */
public interface DisputeCaseWork {

    /**
     * Opens a case and moves the payment to DISPUTED (investigation status; nothing is reversed). Idempotent per
     * (user, idempotency key). One active case per payment.
     */
    CreatedDispute openDispute(OpenDisputeCommand command);

    /** Appends an investigation note (append-only; validated plain text). Idempotent per (user, key). */
    DisputeView addNote(String caseRef, String body, String idempotencyKey);

    /** Appends evidence metadata. Idempotent per (user, key). */
    DisputeView addEvidence(String caseRef, AddEvidenceCommand command);

    DisputeView startInvestigation(String caseRef, long expectedVersion);

    /** Hands the case to a supervisor for review; at least one evidence item is required. */
    DisputeView requestReview(String caseRef, long expectedVersion);

    /**
     * @param disputedAmount optional; if given it must equal the payment's full unrefunded amount (Phase 2 policy)
     * @param responseDueAt  optional deadline as provided by the bank/network notification
     */
    record OpenDisputeCommand(String paymentRef, DisputeReasonCategory reasonCategory, String networkReasonCode,
            BigDecimal disputedAmount, String currency, Instant responseDueAt, String idempotencyKey) {

        public OpenDisputeCommand {
            Objects.requireNonNull(paymentRef, "paymentRef");
            Objects.requireNonNull(reasonCategory, "reasonCategory");
        }
    }

    record AddEvidenceCommand(EvidenceType type, String documentReference, String description, Instant receivedAt,
            String idempotencyKey) {

        public AddEvidenceCommand {
            Objects.requireNonNull(type, "type");
        }
    }

    /** @param replayed true when an earlier case with the same idempotency key was returned */
    record CreatedDispute(DisputeView dispute, boolean replayed) {
    }
}

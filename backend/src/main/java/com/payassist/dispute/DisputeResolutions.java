package com.payassist.dispute;

/**
 * Human-only supervisor decisions on dispute cases (CARE_SUPERVISOR). <b>Never expose these as AI tools</b>: they
 * change a payment's financial status (WON / LOST / WITHDRAWN) or approve what is sent to the bank or network.
 *
 * <p>Separation of duties is enforced on immutable user IDs: the evidence submission cannot be approved by the person
 * who requested review, and liability cannot be accepted by the person who opened the case.
 */
public interface DisputeResolutions {

    DisputeView returnForInvestigation(String caseRef, ReturnReason reason, long expectedVersion);

    /** Approves the evidence package for submission to the bank/network (not by the review requester). */
    DisputeView approveSubmission(String caseRef, long expectedVersion);

    /** Records the outcome reported by the bank/network: WON restores the payment, LOST makes it CHARGED_BACK. */
    DisputeView recordOutcome(String caseRef, DisputeOutcome outcome, long expectedVersion);

    /** Decides not to contest: the case is LOST and the payment CHARGED_BACK (not by the case opener). */
    DisputeView acceptLiability(String caseRef, long expectedVersion);

    /** The cardholder withdrew the dispute: the payment returns to its pre-dispute status. */
    DisputeView withdraw(String caseRef, WithdrawalReason reason, long expectedVersion);
}

package com.payassist.dispute;

/** Everything a user can do to a case; {@code allowedActions} in a view lists those open to the current user. */
public enum DisputeAction {
    ADD_NOTE,
    ADD_EVIDENCE,
    START_INVESTIGATION,
    REQUEST_REVIEW,
    RETURN_FOR_INVESTIGATION,
    APPROVE_SUBMISSION,
    RECORD_OUTCOME,
    ACCEPT_LIABILITY,
    WITHDRAW
}

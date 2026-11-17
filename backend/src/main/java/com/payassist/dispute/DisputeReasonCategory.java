package com.payassist.dispute;

/**
 * Network-neutral dispute categories. PayAssist does not model any card network's own reason-code scheme; a case can
 * store the network's code verbatim ({@code networkReasonCode}) without interpreting it.
 */
public enum DisputeReasonCategory {
    FRAUDULENT,
    PRODUCT_NOT_RECEIVED,
    NOT_AS_DESCRIBED,
    DUPLICATE_CHARGE,
    CREDIT_NOT_PROCESSED,
    SUBSCRIPTION_CANCELLED,
    OTHER
}

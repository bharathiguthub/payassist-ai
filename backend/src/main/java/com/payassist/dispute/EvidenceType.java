package com.payassist.dispute;

/** Kinds of evidence a case can reference. Only metadata is stored; documents live in their own systems. */
public enum EvidenceType {
    PROOF_OF_DELIVERY,
    CUSTOMER_COMMUNICATION,
    TRANSACTION_RECEIPT,
    TERMS_AND_POLICIES,
    SERVICE_USAGE_RECORD,
    REFUND_OR_CREDIT_RECORD,
    OTHER
}

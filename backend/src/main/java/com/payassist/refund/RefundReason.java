package com.payassist.refund;

/**
 * Why a refund is requested. A fixed list (no free text), so no personal data can be entered and every
 * request is classifiable for reporting.
 */
public enum RefundReason {
    CUSTOMER_REQUEST,
    DUPLICATE_CHARGE,
    PRODUCT_NOT_RECEIVED,
    NOT_AS_DESCRIBED,
    SERVICE_NOT_PROVIDED,
    GOODWILL
}

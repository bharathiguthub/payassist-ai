package com.payassist.support;

/**
 * Seed payments reserved for the dispute tests (which commit writes in the shared mutating context). All are SETTLED
 * seed payments far from the low SETTLED offsets other mutating tests select by position, and not used by
 * {@link RefundTestPayments}. Check them with {@link RefundTestPayments#fresh}.
 */
public final class DisputeTestPayments {

    // DisputeWorkflowIT (refund-eligible at the fixed test time where a test also needs refunds)
    public static final String WON_LIFECYCLE = "PAY-000072";     // CARD   706.68 GBP
    public static final String LOST_OUTCOME = "PAY-000074";      // WALLET 865.06 INR
    public static final String SEPARATION_OF_DUTIES = "PAY-000096"; // CARD 107.24 GBP
    public static final String WITHDRAWAL = "PAY-000105";        // CARD   819.95 USD
    public static final String CASE_WORK = "PAY-000129";         // CARD   220.51 USD

    // DisputeConcurrencyIT
    public static final String REFUND_VS_DISPUTE_RACE = "PAY-000168"; // CARD 808.92 GBP
    public static final String CONCURRENT_OPEN = "PAY-000144";        // CARD 1408.36 GBP
    public static final String CONCURRENT_SAME_KEY = "PAY-000153";    // CARD 2121.07 USD
    public static final String CONCURRENT_RESOLUTION = "PAY-000177";  // CARD 1521.63 USD

    private DisputeTestPayments() {
    }
}

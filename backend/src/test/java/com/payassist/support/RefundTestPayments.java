package com.payassist.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Seed payments reserved for the refund workflow tests (which commit writes in the shared mutating context).
 *
 * <p>All are SETTLED, refundable at the fixed test time (2026-11-16) and far from the payments other mutating
 * tests select by position (the lowest SETTLED references and the three highest small CARD payments), so no
 * test changes another test's data. {@link #fresh} fails loudly if an allocation is ever broken.
 */
public final class RefundTestPayments {

    // RefundConcurrencyIT (WALLET, INR, under the supervisor limit)
    public static final String CONCURRENT_REQUESTS = "PAY-000278";  // 2019.82 INR
    public static final String CONCURRENT_SAME_KEY = "PAY-000302";  // 1420.38 INR
    public static final String CONCURRENT_APPROVALS = "PAY-000326"; //  820.94 INR
    public static final String APPROVE_VS_REJECT = "PAY-000350";    //  221.50 INR

    // RefundWorkflowIT
    public static final String PARTIAL_THEN_FULL = "PAY-000374";    // WALLET 2122.06 INR
    public static final String IDEMPOTENCY = "PAY-000254";          // WALLET  119.26 INR
    public static final String RESERVATIONS = "PAY-000225";         // CARD    322.75 USD
    public static final String SEPARATION_OF_DUTIES = "PAY-000192"; // CARD    209.48 GBP
    public static final String REVALIDATION = "PAY-000201";         // CARD    922.19 USD
    public static final String ESCALATION = "PAY-000249";           // CARD   2223.31 USD (limit 1250)
    public static final String ROLES = "PAY-000199";                // BANK_TRANSFER 763.81 EUR (full refunds only)

    // DataProtectionRefusalIT (WALLET, INR): database-level refusals surfaced as stable problem codes
    public static final String PERMISSION_REFUSAL = "PAY-000026"; // 2063.94 INR
    public static final String GUARD_REFUSAL = "PAY-000050";      // 1464.50 INR

    private RefundTestPayments() {
    }

    /** Asserts the payment is still an untouched seed payment (SETTLED, seed history only) and returns its amount. */
    public static BigDecimal fresh(JdbcTemplate jdbc, String paymentRef) {
        assertThat(jdbc.queryForObject("select status from payments where payment_ref = ?", String.class, paymentRef))
                .as("allocated test payment %s must still be SETTLED", paymentRef).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("""
                select count(*) from payment_events e join payments p on p.id = e.payment_id
                where p.payment_ref = ? and (e.correlation_id is null or e.correlation_id not like 'seed-%')""",
                Long.class, paymentRef)).as("allocated test payment %s must be untouched", paymentRef).isZero();
        return jdbc.queryForObject("select amount from payments where payment_ref = ?", BigDecimal.class, paymentRef);
    }

    public static String currency(JdbcTemplate jdbc, String paymentRef) {
        return jdbc.queryForObject("select currency from payments where payment_ref = ?", String.class, paymentRef);
    }
}

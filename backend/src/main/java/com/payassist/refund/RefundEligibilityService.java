package com.payassist.refund;

import com.payassist.identity.CurrentActor;
import com.payassist.identity.Roles;
import com.payassist.payment.PaymentHistory;
import com.payassist.payment.PaymentHistory.RefundPosition;
import com.payassist.refund.internal.RefundEligibilityEngine;
import com.payassist.refund.internal.RefundReservations;
import com.payassist.shared.Actor;
import com.payassist.shared.AuditRecord;
import com.payassist.shared.DomainException;
import com.payassist.shared.MonetaryAmounts;
import com.payassist.shared.web.CorrelationIdFilter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refund eligibility use case.
 *
 * <p><b>Read-only with respect to money</b>: it never creates, reserves or executes a refund. Its only
 * write is the audit record of the check itself.
 *
 * <p><b>Not a guarantee</b>: the facts can change right after the check (another refund, a dispute). The
 * refund request flow (milestone 4) must re-run {@link RefundEligibilityEngine} inside the transaction that
 * creates the request, after locking the payment row, and reject the request if the decision has changed.
 *
 * <p><b>AI tool readiness</b>: this method is the intended seam for a Spring AI {@code @Tool} / MCP tool
 * ("check whether a payment can be refunded"). It is web-free, permission-checked by method security against
 * the calling user, resolves the actor from the security context (never from an argument), audits every call,
 * accepts only typed, validated inputs, and returns a versioned, fact-only record with stable reason codes.
 */
@Service
public class RefundEligibilityService {

    public static final String ACTION_CHECK = "REFUND_ELIGIBILITY_CHECK";
    static final String RESOURCE_PAYMENT = "PAYMENT";

    private final PaymentHistory payments;
    private final RefundReservations reservations;
    private final RefundEligibilityEngine engine;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final TransactionTemplate snapshotTx;
    private final TransactionTemplate auditTx;

    RefundEligibilityService(PaymentHistory payments, RefundReservations reservations, RefundEligibilityEngine engine,
            ApplicationEventPublisher publisher, Clock clock, PlatformTransactionManager transactionManager) {
        this.payments = payments;
        this.reservations = reservations;
        this.engine = engine;
        this.publisher = publisher;
        this.clock = clock;
        this.snapshotTx = new TransactionTemplate(transactionManager);
        this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshotTx.setReadOnly(true);
        this.auditTx = new TransactionTemplate(transactionManager);
        this.auditTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Checks whether {@code amount} of a payment could be refunded under the current policy.
     *
     * @param amount   amount to refund; {@code null} evaluates refunding everything that is left
     * @param currency ISO 4217 code of {@code amount}; required with an amount, otherwise ignored
     * @throws DomainException.RuleViolation (422) for malformed money: unknown currency, unsupported currency
     *         precision, too many decimal places (never rounded), or a non-positive amount
     */
    @PreAuthorize(Roles.CHECK_REFUND_ELIGIBILITY)
    public RefundEligibility check(String paymentRef, BigDecimal amount, String currency) {
        Actor actor = CurrentActor.require();

        BigDecimal requested = null;
        String requestCurrency = null;
        if (amount != null) {
            if (currency == null) {
                throw new DomainException.RuleViolation("CURRENCY_REQUIRED", "A currency is required with an amount");
            }
            requestCurrency = currency;
            requested = MonetaryAmounts.requirePositive(amount, currency); // rejects bad precision, never rounds
        }

        // Facts are read in one consistent snapshot. The audit record is written afterwards in its own READ COMMITTED
        // transaction, because the audit hash chain cannot be extended from a snapshot (ADR 0013). Still fail closed:
        // if the audit write fails, the result is not returned.
        BigDecimal requestedAmount = requested;
        String currencyOfRequest = requestCurrency;
        RefundEligibility result = snapshotTx.execute(status -> {
            RefundPosition position = payments.refundPosition(paymentRef);
            BigDecimal reserved = reservations.reservedAmount(position.paymentId());
            return engine.evaluate(position, requestedAmount, currencyOfRequest, reserved,
                    Instant.now(clock), MDC.get(CorrelationIdFilter.MDC_KEY));
        });

        auditTx.executeWithoutResult(status -> publisher.publishEvent(
                AuditRecord.of(clock, actor, ACTION_CHECK, RESOURCE_PAYMENT, result.paymentRef())
                        .withDetails(auditDetails(result))));
        return result;
    }

    /** Non-sensitive context only: decision, codes, amounts, versions. No customer data, no free text. */
    private static Map<String, String> auditDetails(RefundEligibility r) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("decision", r.decision().name());
        details.put("reasonCodes", r.reasons().stream().map(x -> x.code().name()).collect(Collectors.joining(",")));
        details.put("requestedAmount", r.requestedAmount().toPlainString());
        details.put("currency", r.currency());
        details.put("remainingRefundableAmount", r.remainingRefundableAmount().toPlainString());
        details.put("policyVersion", r.policyVersion());
        details.put("paymentVersion", Long.toString(r.paymentVersion()));
        return details;
    }
}

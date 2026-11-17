package com.payassist.refund;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A refund request as shown to users and (later) AI tools. Contains no customer data and no immutable user
 * IDs: actors are shown by username. Versioned contract: fields are only added within a schema version.
 *
 * @param eligibility    the eligibility re-validated under the payment row lock when the request was created
 * @param decision       {@code null} until a supervisor approves or rejects
 * @param allowedActions what the <em>current</em> user may do next ({@code APPROVE}, {@code REJECT}); computed
 *                       server-side from role, separation of duties, status and limits. UI hint only: every
 *                       action is re-checked when performed.
 * @param version        optimistic-lock version; send it back as {@code expectedVersion} when deciding
 */
public record RefundRequestView(
        String schemaVersion,
        String refundRef,
        String paymentRef,
        BigDecimal amount,
        String currency,
        RefundReason reason,
        RefundRequestStatus status,
        Eligibility eligibility,
        String requestedBy,
        Instant requestedAt,
        String correlationId,
        Decision decision,
        List<Action> allowedActions,
        long version) {

    public static final String SCHEMA_VERSION = "1.0";

    public enum Action { APPROVE, REJECT }

    public record Eligibility(RefundEligibility.Decision decision, List<String> reasonCodes, String policyVersion) {
    }

    /**
     * @param outcome            APPROVED or REJECTED
     * @param reasonCode         {@code APPROVED} or a {@link RejectionReason}
     * @param ledgerEventRecorded true when the approval recorded a REFUND_RECORDED event in the payment ledger
     */
    public record Decision(RefundRequestStatus outcome, String reasonCode, String decidedBy, Instant decidedAt,
            String correlationId, boolean ledgerEventRecorded) {
    }
}

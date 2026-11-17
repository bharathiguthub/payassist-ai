package com.payassist.refund;

import com.payassist.shared.web.PageResponse;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

/**
 * Refund request use cases that are safe to expose to future AI tools (Spring AI {@code @Tool} / MCP):
 * <ul>
 *   <li>{@link #requestRefund}: creates a request that only a human supervisor can approve;</li>
 *   <li>{@link #getRefundStatus} and {@link #listRefunds}: read-only.</li>
 * </ul>
 * Every method runs as the authenticated user: method security checks that user's roles and the actor is
 * resolved from the security context, never from an argument, so a tool can never act with more permissions
 * than the person using it. Approval and rejection are deliberately <b>not</b> part of this interface
 * (see {@link RefundApprovals}).
 */
public interface RefundRequests {

    /**
     * Creates a refund request after re-validating eligibility under the payment row lock and reserving the
     * amount. Idempotent per (user, idempotency key): retrying with the same key and payload returns the
     * original request; the same key with a different payload is rejected (422 {@code IDEMPOTENCY_KEY_REUSED}).
     */
    CreatedRefundRequest requestRefund(RequestRefundCommand command);

    /** One refund request (audited as a view). */
    RefundRequestView getRefundStatus(String refundRef);

    /** Refund requests matching the filter, newest first. */
    PageResponse<RefundRequestView> listRefunds(RefundQuery query, int page, int size);

    /**
     * @param amount         required; at most the currency's minor units (never rounded)
     * @param idempotencyKey client-generated, 8–64 characters {@code [A-Za-z0-9_-]}; reuse it for retries
     */
    record RequestRefundCommand(String paymentRef, BigDecimal amount, String currency, RefundReason reason,
            String idempotencyKey) {

        public RequestRefundCommand {
            Objects.requireNonNull(paymentRef, "paymentRef");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** @param replayed true when an earlier request with the same idempotency key was returned */
    record CreatedRefundRequest(RefundRequestView request, boolean replayed) {
    }

    /**
     * @param statuses   empty = all
     * @param paymentRef optional exact payment reference
     * @param mine       only requests created by the current user
     */
    record RefundQuery(Set<RefundRequestStatus> statuses, String paymentRef, boolean mine) {

        public RefundQuery {
            statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        }
    }
}

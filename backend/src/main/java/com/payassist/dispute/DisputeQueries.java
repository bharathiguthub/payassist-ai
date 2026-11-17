package com.payassist.dispute;

import com.payassist.shared.web.PageResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only dispute use cases: the only dispute interface intended for future AI tools (Spring AI {@code @Tool} /
 * MCP). Every method runs as the authenticated user (method security; actor from the security context) and returns
 * versioned, fact-only records. Nothing here changes state, apart from audit records of views.
 */
public interface DisputeQueries {

    /** One case with history, notes, evidence and pending refunds (audited as a view). */
    DisputeView getDispute(String caseRef);

    /** Cases matching the filter. Overdue first by due date, then newest. */
    PageResponse<DisputeView.Summary> listDisputes(DisputeQuery query, int page, int size);

    /** The active (non-final) case for a payment, if any. For composition into other read models; not audited. */
    Optional<ActiveDispute> activeDisputeFor(String paymentRef);

    /**
     * @param statuses   empty = all
     * @param paymentRef optional exact payment reference
     * @param overdue    only active cases whose response deadline has passed
     * @param mine       only cases opened by the current user
     */
    record DisputeQuery(Set<DisputeStatus> statuses, String paymentRef, boolean overdue, boolean mine) {

        public DisputeQuery {
            statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        }
    }

    record ActiveDispute(String caseRef, DisputeStatus status, DisputeReasonCategory reasonCategory,
            Instant openedAt, Instant responseDueAt, boolean overdue) {
    }
}

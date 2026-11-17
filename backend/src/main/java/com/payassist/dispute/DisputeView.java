package com.payassist.dispute;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A dispute case as shown to users and (later) read-only AI tools. No customer data and no immutable user IDs:
 * people are shown by username. Versioned contract: fields are only added within a schema version.
 *
 * @param responseDueAt   deadline as provided by the user from the bank/network notification; {@code null} if none
 *                        was provided. PayAssist never computes deadlines.
 * @param overdue         {@code responseDueAt} has passed and the case is not final (a flag only; nothing changes
 *                        automatically)
 * @param pendingRefunds  refund requests on the payment still awaiting a decision; they keep their reservation but
 *                        cannot be approved while the payment is DISPUTED
 * @param allowedActions  what the <em>current</em> user may do now (role, separation of duties, status). UI hint only:
 *                        every action is re-checked by the server.
 * @param version         optimistic-lock version; send it back as {@code expectedVersion} for transitions
 */
public record DisputeView(
        String schemaVersion,
        String caseRef,
        String paymentRef,
        DisputeReasonCategory reasonCategory,
        String networkReasonCode,
        BigDecimal disputedAmount,
        String currency,
        DisputeStatus status,
        String source,
        Instant responseDueAt,
        boolean overdue,
        String openedBy,
        Instant openedAt,
        String reviewRequestedBy,
        Instant updatedAt,
        List<HistoryEntry> history,
        List<Note> notes,
        List<Evidence> evidence,
        List<PendingRefund> pendingRefunds,
        List<DisputeAction> allowedActions,
        long version) {

    public static final String SCHEMA_VERSION = "1.0";

    public record HistoryEntry(int sequenceNo, String eventType, DisputeStatus fromStatus, DisputeStatus toStatus,
            String reasonCode, String actor, String correlationId, Instant occurredAt, boolean paymentLedgerUpdated) {
    }

    /** Note text is plain text; clients must render it as text, never as HTML. */
    public record Note(String body, String author, Instant createdAt) {
    }

    public record Evidence(EvidenceType type, String documentReference, String description, Instant receivedAt,
            String addedBy, Instant addedAt) {
    }

    public record PendingRefund(String refundRef, BigDecimal amount, String currency, String status) {
    }

    /** Compact list item for searches and queues. */
    public record Summary(String caseRef, String paymentRef, DisputeReasonCategory reasonCategory,
            BigDecimal disputedAmount, String currency, DisputeStatus status, String source, Instant responseDueAt,
            boolean overdue, String openedBy, Instant openedAt, String reviewRequestedBy, long version) {
    }
}

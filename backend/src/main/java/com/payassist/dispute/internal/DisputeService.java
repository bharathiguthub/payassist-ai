package com.payassist.dispute.internal;

import com.payassist.dispute.DisputeAction;
import com.payassist.dispute.DisputeCaseWork;
import com.payassist.dispute.DisputeOutcome;
import com.payassist.dispute.DisputeQueries;
import com.payassist.dispute.DisputeResolutions;
import com.payassist.dispute.DisputeStatus;
import com.payassist.dispute.DisputeView;
import com.payassist.dispute.ReturnReason;
import com.payassist.dispute.WithdrawalReason;
import com.payassist.dispute.internal.DisputeTransitions.Duty;
import com.payassist.dispute.internal.DisputeTransitions.Performer;
import com.payassist.dispute.internal.DisputeTransitions.Transition;
import com.payassist.identity.CurrentActor;
import com.payassist.identity.Roles;
import com.payassist.payment.PaymentEventSource;
import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentHistory.RefundPosition;
import com.payassist.payment.PaymentLedger;
import com.payassist.payment.RecordPaymentEvent;
import com.payassist.payment.RecordedPaymentEvent;
import com.payassist.refund.RefundRequestStatus;
import com.payassist.refund.RefundRequests;
import com.payassist.shared.Actor;
import com.payassist.shared.AuditRecord;
import com.payassist.shared.DomainException;
import com.payassist.shared.InvalidStateTransitionException;
import com.payassist.shared.MonetaryAmounts;
import com.payassist.shared.ResourceNotFoundException;
import com.payassist.shared.TextGuard;
import com.payassist.shared.web.CorrelationIdFilter;
import com.payassist.shared.web.PageResponse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dispute case management.
 *
 * <p><b>Transactions.</b> Each write is one READ COMMITTED transaction. Opening a case and every status transition
 * first take the payment row lock ({@link PaymentLedger#lockForUpdate}), then the case row lock: the same
 * payment-then-child order as the refund workflow, so refund and dispute writes on one payment are serialised and
 * cannot deadlock. Notes and evidence lock only the case row. Refused actions are audited in a separate transaction.
 *
 * <p><b>Money.</b> Nothing here moves money. Opening a case records DISPUTE_OPENED (payment becomes DISPUTED, an
 * investigation status); outcomes record DISPUTE_WON / DISPUTE_LOST / DISPUTE_WITHDRAWN as simulated network facts.
 */
@Service
class DisputeService implements DisputeQueries, DisputeCaseWork, DisputeResolutions {

    static final String ACTION_OPEN = "DISPUTE_CASE_OPEN";
    static final String ACTION_VIEW = "DISPUTE_CASE_VIEW";
    static final String ACTION_NOTE = "DISPUTE_NOTE_ADD";
    static final String ACTION_EVIDENCE = "DISPUTE_EVIDENCE_ADD";
    static final String ACTION_TRANSITION = "DISPUTE_CASE_TRANSITION";
    static final String RESOURCE_CASE = "DISPUTE_CASE";
    static final String RESOURCE_PAYMENT = "PAYMENT";
    static final int NOTE_MAX = 2000;
    static final int DESCRIPTION_MAX = 500;
    static final Set<DisputeStatus> ACTIVE = EnumSet.of(DisputeStatus.OPEN, DisputeStatus.UNDER_INVESTIGATION,
            DisputeStatus.PENDING_REVIEW, DisputeStatus.EVIDENCE_SUBMITTED);

    private static final Logger LOG = LoggerFactory.getLogger(DisputeService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Pattern PAYMENT_REF = Pattern.compile("^[A-Z0-9-]{1,32}$");
    private static final Pattern CASE_REF = Pattern.compile("^DSP-[0-9A-F]{12}$");
    private static final Pattern NETWORK_CODE = Pattern.compile("^[A-Za-z0-9._-]{1,16}$");
    private static final Pattern DOCUMENT_REF = Pattern.compile("^[A-Za-z0-9._:/-]{1,128}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PaymentLedger ledger;
    private final DisputeCaseRepository cases;
    private final DisputeCaseEventRepository caseEvents;
    private final DisputeNoteRepository notes;
    private final DisputeEvidenceRepository evidence;
    private final RefundRequests refunds;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    @SuppressWarnings("java:S107")
    DisputeService(PaymentLedger ledger, DisputeCaseRepository cases, DisputeCaseEventRepository caseEvents,
            DisputeNoteRepository notes, DisputeEvidenceRepository evidence, RefundRequests refunds,
            ApplicationEventPublisher publisher, Clock clock, PlatformTransactionManager transactionManager) {
        this.ledger = ledger;
        this.cases = cases;
        this.caseEvents = caseEvents;
        this.notes = notes;
        this.evidence = evidence;
        this.refunds = refunds;
        this.publisher = publisher;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    // --- opening --------------------------------------------------------------------------------------

    @Override
    @PreAuthorize(Roles.WORK_DISPUTES)
    public CreatedDispute openDispute(OpenDisputeCommand command) {
        Actor actor = CurrentActor.require();
        String paymentRef = normalize(command.paymentRef(), PAYMENT_REF, "INVALID_PAYMENT_REF");
        requireKey(command.idempotencyKey());
        String networkCode = command.networkReasonCode() == null || command.networkReasonCode().isBlank() ? null
                : requirePattern(command.networkReasonCode().trim(), NETWORK_CODE, "INVALID_NETWORK_REASON_CODE");
        BigDecimal statedAmount = null;
        if (command.disputedAmount() != null || command.currency() != null) {
            if (command.disputedAmount() == null || command.currency() == null) {
                throw new DomainException.RuleViolation("AMOUNT_AND_CURRENCY_REQUIRED",
                        "Disputed amount and currency must be given together");
            }
            statedAmount = MonetaryAmounts.requirePositive(command.disputedAmount(), command.currency());
        }
        String fingerprint = fingerprint(paymentRef, command.reasonCategory().name(), String.valueOf(networkCode),
                statedAmount == null ? "-" : statedAmount.toPlainString(), String.valueOf(command.currency()),
                String.valueOf(command.responseDueAt()));
        BigDecimal amount = statedAmount;
        try {
            try {
                return tx.execute(s -> open(actor, paymentRef, command, networkCode, amount, fingerprint));
            } catch (DataIntegrityViolationException e) {
                // A concurrent request won the insert: same idempotency key (replay) or another active case.
                return tx.execute(s -> replayOpen(actor, command.idempotencyKey(), fingerprint)).orElseThrow(() ->
                        new DomainException.Conflict("DISPUTE_ALREADY_OPEN", "Payment " + paymentRef
                                + " already has an active dispute case"));
            }
        } catch (DomainException e) {
            auditFailure(actor, ACTION_OPEN, RESOURCE_PAYMENT, paymentRef, e, Map.of());
            throw e;
        }
    }

    private CreatedDispute open(Actor actor, String paymentRef, OpenDisputeCommand command, String networkCode,
            BigDecimal statedAmount, String fingerprint) {
        RefundPosition payment = ledger.lockForUpdate(paymentRef);
        Optional<CreatedDispute> previous = replayOpen(actor, command.idempotencyKey(), fingerprint);
        if (previous.isPresent()) {
            return previous.get();
        }
        cases.findFirstByPaymentIdAndStatusIn(payment.paymentId(), ACTIVE).ifPresent(active -> {
            throw new DomainException.Conflict("DISPUTE_ALREADY_OPEN",
                    "Payment " + paymentRef + " already has an active dispute case " + active.getCaseRef());
        });
        // Phase 2 policy: a case covers the full amount not yet refunded (no partial disputes).
        BigDecimal disputed = payment.amount().subtract(payment.refundedAmount());
        if (statedAmount != null && (!payment.currency().equals(command.currency())
                || statedAmount.compareTo(disputed) != 0)) {
            throw new DomainException.RuleViolation("DISPUTED_AMOUNT_MISMATCH", "A dispute covers the full unrefunded "
                    + "amount of " + disputed.toPlainString() + " " + payment.currency());
        }
        if (command.responseDueAt() != null && command.responseDueAt().isBefore(payment.createdAt())) {
            throw new DomainException.RuleViolation("INVALID_RESPONSE_DUE_DATE",
                    "The response due date cannot be before the payment was created");
        }

        // The payment state machine is the single source of "can this payment be disputed?": only SETTLED and
        // PARTIALLY_REFUNDED can (409 INVALID_STATE_TRANSITION otherwise). Recorded first, so a refused payment never
        // reaches the case insert; if anything later fails, the whole transaction (ledger event included) rolls back.
        RecordedPaymentEvent opened = ledger.record(RecordPaymentEvent.of(payment.paymentRef(),
                        PaymentEventType.DISPUTE_OPENED, actor, PaymentEventSource.PAYASSIST)
                .withAmount(disputed, payment.currency()).withReason(command.reasonCategory().name()));

        Instant now = Instant.now(clock);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        DisputeCase dispute = new DisputeCase(UUID.randomUUID(), newCaseRef(), payment.paymentId(), payment.paymentRef(),
                command.reasonCategory(), networkCode, disputed, payment.currency(), command.responseDueAt(), actor, now,
                correlationId, command.idempotencyKey(), fingerprint);
        cases.saveAndFlush(dispute); // idempotency key and one-active-case-per-payment constraints surface here
        caseEvents.save(new DisputeCaseEvent(dispute.getId(), 1, "OPENED", null, DisputeStatus.OPEN,
                command.reasonCategory().name(), actor, correlationId, now, opened.eventId()));

        audit(actor, ACTION_OPEN, dispute.getCaseRef(), details(dispute, "idempotentReplay", "false"));
        return new CreatedDispute(view(dispute, actor, now), false);
    }

    private Optional<CreatedDispute> replayOpen(Actor actor, String key, String fingerprint) {
        return cases.findByOpenedBySubjectAndIdempotencyKey(actor.subject(), key).map(existing -> {
            if (!fingerprint.equals(existing.getRequestFingerprint())) {
                throw new DomainException.RuleViolation("IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used for a different dispute case");
            }
            audit(actor, ACTION_OPEN, existing.getCaseRef(), details(existing, "idempotentReplay", "true"));
            return new CreatedDispute(view(existing, actor, Instant.now(clock)), true);
        });
    }

    // --- notes and evidence ---------------------------------------------------------------------------

    @Override
    @PreAuthorize(Roles.WORK_DISPUTES)
    public DisputeView addNote(String caseRef, String body, String idempotencyKey) {
        Actor actor = CurrentActor.require();
        String ref = normalize(caseRef, CASE_REF, "INVALID_CASE_REF");
        requireKey(idempotencyKey);
        String text = TextGuard.requireSafe(body, NOTE_MAX);
        String fingerprint = fingerprint(ref, text);
        return guarded(actor, ACTION_NOTE, ref, () -> tx.execute(s -> {
            Optional<DisputeNote> previous = notes.findByAuthorSubjectAndIdempotencyKey(actor.subject(), idempotencyKey);
            if (previous.isPresent()) {
                requireSameFingerprint(previous.get().getRequestFingerprint(), fingerprint);
                return view(cases.findById(previous.get().getCaseId()).orElseThrow(), actor, Instant.now(clock));
            }
            DisputeCase dispute = lockCase(ref);
            requireActive(dispute);
            Instant now = Instant.now(clock);
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            notes.save(new DisputeNote(dispute.getId(), text, actor, now, correlationId, idempotencyKey, fingerprint));
            appendHistory(dispute, "NOTE_ADDED", dispute.getStatus(), dispute.getStatus(), null, actor, now, null);
            dispute.touch(now);
            cases.flush();
            audit(actor, ACTION_NOTE, ref, Map.of("noteLength", Integer.toString(text.length()),
                    "status", dispute.getStatus().name())); // never the note text itself
            return view(dispute, actor, now);
        }));
    }

    @Override
    @PreAuthorize(Roles.WORK_DISPUTES)
    public DisputeView addEvidence(String caseRef, AddEvidenceCommand command) {
        Actor actor = CurrentActor.require();
        String ref = normalize(caseRef, CASE_REF, "INVALID_CASE_REF");
        requireKey(command.idempotencyKey());
        String document = requirePattern(command.documentReference() == null ? "" : command.documentReference().trim(),
                DOCUMENT_REF, "INVALID_DOCUMENT_REFERENCE");
        String description = TextGuard.optionalSafe(command.description(), DESCRIPTION_MAX);
        if (command.receivedAt() != null && command.receivedAt().isAfter(Instant.now(clock))) {
            throw new DomainException.RuleViolation("INVALID_RECEIVED_AT", "Evidence cannot be received in the future");
        }
        String fingerprint = fingerprint(ref, command.type().name(), document, String.valueOf(description),
                String.valueOf(command.receivedAt()));
        return guarded(actor, ACTION_EVIDENCE, ref, () -> tx.execute(s -> {
            Optional<DisputeEvidenceItem> previous = evidence.findByAddedBySubjectAndIdempotencyKey(actor.subject(),
                    command.idempotencyKey());
            if (previous.isPresent()) {
                requireSameFingerprint(previous.get().getRequestFingerprint(), fingerprint);
                return view(cases.findById(previous.get().getCaseId()).orElseThrow(), actor, Instant.now(clock));
            }
            DisputeCase dispute = lockCase(ref);
            requireActive(dispute);
            Instant now = Instant.now(clock);
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            evidence.save(new DisputeEvidenceItem(dispute.getId(), command.type(), document, description,
                    command.receivedAt(), actor, now, correlationId, command.idempotencyKey(), fingerprint));
            appendHistory(dispute, "EVIDENCE_ADDED", dispute.getStatus(), dispute.getStatus(), command.type().name(),
                    actor, now, null);
            dispute.touch(now);
            cases.flush();
            audit(actor, ACTION_EVIDENCE, ref, Map.of("evidenceType", command.type().name(),
                    "status", dispute.getStatus().name()));
            return view(dispute, actor, now);
        }));
    }

    // --- transitions ----------------------------------------------------------------------------------

    @Override
    @PreAuthorize(Roles.WORK_DISPUTES)
    public DisputeView startInvestigation(String caseRef, long expectedVersion) {
        return transition(caseRef, Transition.START_INVESTIGATION, null, expectedVersion);
    }

    @Override
    @PreAuthorize(Roles.WORK_DISPUTES)
    public DisputeView requestReview(String caseRef, long expectedVersion) {
        return transition(caseRef, Transition.REQUEST_REVIEW, null, expectedVersion);
    }

    @Override
    @PreAuthorize(Roles.RESOLVE_DISPUTES)
    public DisputeView returnForInvestigation(String caseRef, ReturnReason reason, long expectedVersion) {
        if (reason == null) {
            throw new DomainException.RuleViolation("REASON_REQUIRED", "A reason is required");
        }
        return transition(caseRef, Transition.RETURN_FOR_INVESTIGATION, reason.name(), expectedVersion);
    }

    @Override
    @PreAuthorize(Roles.RESOLVE_DISPUTES)
    public DisputeView approveSubmission(String caseRef, long expectedVersion) {
        return transition(caseRef, Transition.APPROVE_SUBMISSION, null, expectedVersion);
    }

    @Override
    @PreAuthorize(Roles.RESOLVE_DISPUTES)
    public DisputeView recordOutcome(String caseRef, DisputeOutcome outcome, long expectedVersion) {
        if (outcome == null) {
            throw new DomainException.RuleViolation("OUTCOME_REQUIRED", "An outcome is required");
        }
        return transition(caseRef, outcome == DisputeOutcome.WON ? Transition.RECORD_WON : Transition.RECORD_LOST,
                null, expectedVersion);
    }

    @Override
    @PreAuthorize(Roles.RESOLVE_DISPUTES)
    public DisputeView acceptLiability(String caseRef, long expectedVersion) {
        return transition(caseRef, Transition.ACCEPT_LIABILITY, null, expectedVersion);
    }

    @Override
    @PreAuthorize(Roles.RESOLVE_DISPUTES)
    public DisputeView withdraw(String caseRef, WithdrawalReason reason, long expectedVersion) {
        if (reason == null) {
            throw new DomainException.RuleViolation("REASON_REQUIRED", "A reason is required");
        }
        return transition(caseRef, Transition.WITHDRAW, reason.name(), expectedVersion);
    }

    private DisputeView transition(String caseRef, Transition transition, String reasonCode, long expectedVersion) {
        Actor actor = CurrentActor.require();
        String ref = normalize(caseRef, CASE_REF, "INVALID_CASE_REF");
        return guarded(actor, ACTION_TRANSITION, ref, () -> tx.execute(s -> {
            String paymentRef = cases.findPaymentRefByCaseRef(ref)
                    .orElseThrow(() -> new ResourceNotFoundException("Dispute case", ref));
            ledger.lockForUpdate(paymentRef);               // 1. payment row
            DisputeCase dispute = lockCase(ref);            // 2. case row
            if (expectedVersion != dispute.getVersion()) {
                throw new ObjectOptimisticLockingFailureException(DisputeCase.class, ref);
            }
            requirePerformer(transition, actor);
            requireSeparationOfDuties(transition, dispute, actor);
            DisputeStatus from = dispute.getStatus();
            if (!transition.allowedFrom(from)) {
                throw new InvalidStateTransitionException("Dispute case", ref, from,
                        transition.action);
            }
            if (transition == Transition.REQUEST_REVIEW && evidence.countByCaseId(dispute.getId()) == 0) {
                throw new DomainException.RuleViolation("EVIDENCE_REQUIRED",
                        "Add at least one evidence item before requesting review");
            }

            Instant now = Instant.now(clock);
            // Payment ledger: outcomes change the financial status; other steps add a timeline-only event.
            PaymentEventType ledgerType = transition.ledgerEvent != null ? transition.ledgerEvent
                    : PaymentEventType.DISPUTE_UPDATED;
            RecordedPaymentEvent recorded = ledger.record(RecordPaymentEvent.of(paymentRef, ledgerType, actor,
                    PaymentEventSource.PAYASSIST).withReason(transition.historyEvent));
            dispute.apply(transition, actor, now);
            appendHistory(dispute, transition.historyEvent, from, transition.to, reasonCode, actor, now,
                    transition.ledgerEvent != null ? recorded.eventId() : null);
            cases.flush();

            Map<String, String> details = details(dispute, "transition", transition.name(), "fromStatus", from.name(),
                    "paymentStatus", recorded.toStatus().name());
            if (reasonCode != null) {
                details.put("reasonCode", reasonCode);
            }
            audit(actor, ACTION_TRANSITION, ref, details);
            return view(dispute, actor, now);
        }));
    }

    private static void requirePerformer(Transition transition, Actor actor) {
        if (transition.performer == Performer.SUPERVISOR && !CurrentActor.hasRole(Roles.CARE_SUPERVISOR)) {
            throw new DomainException.Forbidden("SUPERVISOR_REQUIRED", "Only a supervisor can do this");
        }
    }

    /** Separation of duties on immutable user IDs (never usernames). */
    private static void requireSeparationOfDuties(Transition transition, DisputeCase dispute, Actor actor) {
        if (transition.duty == Duty.NOT_REVIEW_REQUESTER && dispute.reviewRequestedBy(actor)) {
            throw new DomainException.Forbidden("SELF_REVIEW_FORBIDDEN",
                    "The evidence submission must be approved by someone other than the person who requested review");
        }
        if (transition.duty == Duty.NOT_OPENER && dispute.openedBy(actor)) {
            throw new DomainException.Forbidden("SELF_LIABILITY_FORBIDDEN",
                    "Liability must be accepted by someone other than the person who opened the case");
        }
    }

    // --- reads ----------------------------------------------------------------------------------------

    @Override
    @PreAuthorize(Roles.READ_DISPUTES)
    public DisputeView getDispute(String caseRef) {
        Actor actor = CurrentActor.require();
        String ref = normalize(caseRef, CASE_REF, "INVALID_CASE_REF");
        return tx.execute(s -> {
            DisputeCase dispute = cases.findByCaseRef(ref)
                    .orElseThrow(() -> new ResourceNotFoundException("Dispute case", ref));
            audit(actor, ACTION_VIEW, ref, Map.of());
            return view(dispute, actor, Instant.now(clock));
        });
    }

    @Override
    @PreAuthorize(Roles.READ_DISPUTES)
    public PageResponse<DisputeView.Summary> listDisputes(DisputeQuery query, int page, int size) {
        Actor actor = CurrentActor.require();
        Instant now = Instant.now(clock);
        List<Specification<DisputeCase>> filters = new ArrayList<>();
        if (!query.statuses().isEmpty()) {
            filters.add((root, q, cb) -> root.get("status").in(query.statuses()));
        }
        if (query.paymentRef() != null && !query.paymentRef().isBlank()) {
            String paymentRef = normalize(query.paymentRef(), PAYMENT_REF, "INVALID_PAYMENT_REF");
            filters.add((root, q, cb) -> cb.equal(root.get("paymentRef"), paymentRef));
        }
        if (query.overdue()) {
            filters.add((root, q, cb) -> cb.and(root.get("status").in(ACTIVE),
                    cb.lessThan(root.get("responseDueAt"), now)));
        }
        if (query.mine()) {
            filters.add((root, q, cb) -> cb.equal(root.get("openedBySubject"), actor.subject()));
        }
        // Earliest deadline first. PostgreSQL sorts NULLs last in ascending order, so cases without a provided
        // deadline follow the dated ones (Hibernate 6 Criteria cannot express NULLS LAST explicitly).
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Order.asc("responseDueAt"),
                Sort.Order.desc("openedAt"), Sort.Order.asc("caseRef")));
        return readTx.execute(s -> {
            Page<DisputeCase> result = cases.findAll(Specification.allOf(filters), pageRequest);
            return PageResponse.from(result, c -> summary(c, now));
        });
    }

    @Override
    @PreAuthorize(Roles.READ_DISPUTES)
    public Optional<ActiveDispute> activeDisputeFor(String paymentRef) {
        String ref = normalize(paymentRef, PAYMENT_REF, "INVALID_PAYMENT_REF");
        Instant now = Instant.now(clock);
        return readTx.execute(s -> cases.findFirstByPaymentRefAndStatusIn(ref, ACTIVE).map(c -> new ActiveDispute(
                c.getCaseRef(), c.getStatus(), c.getReasonCategory(), c.getOpenedAt(), c.getResponseDueAt(),
                c.isOverdue(now))));
    }

    // --- mapping --------------------------------------------------------------------------------------

    private DisputeView view(DisputeCase c, Actor viewer, Instant now) {
        List<DisputeView.HistoryEntry> history = caseEvents.findByCaseIdOrderBySequenceNoAsc(c.getId()).stream()
                .map(e -> new DisputeView.HistoryEntry(e.getSequenceNo(), e.getEventType(), e.getFromStatus(),
                        e.getToStatus(), e.getReasonCode(), e.getActorUsername(), e.getCorrelationId(),
                        e.getOccurredAt(), e.getLedgerEventId() != null))
                .toList();
        List<DisputeView.Note> noteViews = notes.findByCaseIdOrderByCreatedAtAsc(c.getId()).stream()
                .map(n -> new DisputeView.Note(n.getBody(), n.getAuthorUsername(), n.getCreatedAt())).toList();
        List<DisputeEvidenceItem> items = evidence.findByCaseIdOrderByAddedAtAsc(c.getId());
        List<DisputeView.Evidence> evidenceViews = items.stream()
                .map(e -> new DisputeView.Evidence(e.getType(), e.getDocumentReference(), e.getDescription(),
                        e.getReceivedAt(), e.getAddedByUsername(), e.getAddedAt())).toList();
        List<DisputeView.PendingRefund> pending = refunds.listRefunds(new RefundRequests.RefundQuery(
                        EnumSet.of(RefundRequestStatus.PENDING_APPROVAL, RefundRequestStatus.ESCALATION_REQUIRED),
                        c.getPaymentRef(), false), 0, 50).content().stream()
                .map(r -> new DisputeView.PendingRefund(r.refundRef(), r.amount(), r.currency(), r.status().name()))
                .toList();
        return new DisputeView(DisputeView.SCHEMA_VERSION, c.getCaseRef(), c.getPaymentRef(), c.getReasonCategory(),
                c.getNetworkReasonCode(), c.getDisputedAmount(), c.getCurrency(), c.getStatus(), c.getSource(),
                c.getResponseDueAt(), c.isOverdue(now), c.getOpenedByUsername(), c.getOpenedAt(),
                c.getReviewRequestedByUsername(), c.getUpdatedAt(), history, noteViews, evidenceViews, pending,
                allowedActions(c, viewer, !items.isEmpty()), c.getVersion());
    }

    private static DisputeView.Summary summary(DisputeCase c, Instant now) {
        return new DisputeView.Summary(c.getCaseRef(), c.getPaymentRef(), c.getReasonCategory(), c.getDisputedAmount(),
                c.getCurrency(), c.getStatus(), c.getSource(), c.getResponseDueAt(), c.isOverdue(now),
                c.getOpenedByUsername(), c.getOpenedAt(), c.getReviewRequestedByUsername(), c.getVersion());
    }

    /** What the viewer may do now: role, status, separation of duties and evidence presence. */
    static List<DisputeAction> allowedActions(DisputeCase c, Actor viewer, boolean hasEvidence) {
        boolean staff = CurrentActor.hasRole(Roles.CARE_AGENT) || CurrentActor.hasRole(Roles.CARE_SUPERVISOR);
        boolean supervisor = CurrentActor.hasRole(Roles.CARE_SUPERVISOR);
        List<DisputeAction> actions = new ArrayList<>();
        if (!staff || c.getStatus().isFinal()) {
            return actions;
        }
        actions.add(DisputeAction.ADD_NOTE);
        actions.add(DisputeAction.ADD_EVIDENCE);
        for (Transition t : Transition.values()) {
            if (!t.allowedFrom(c.getStatus()) || (t.performer == Performer.SUPERVISOR && !supervisor)
                    || (t.duty == Duty.NOT_REVIEW_REQUESTER && c.reviewRequestedBy(viewer))
                    || (t.duty == Duty.NOT_OPENER && c.openedBy(viewer))
                    || (t == Transition.REQUEST_REVIEW && !hasEvidence)
                    || actions.contains(t.action)) {
                continue;
            }
            actions.add(t.action);
        }
        return List.copyOf(actions);
    }

    // --- helpers --------------------------------------------------------------------------------------

    private DisputeCase lockCase(String ref) {
        return cases.findByCaseRefForUpdate(ref).orElseThrow(() -> new ResourceNotFoundException("Dispute case", ref));
    }

    private static void requireActive(DisputeCase dispute) {
        if (dispute.getStatus().isFinal()) {
            throw new DomainException.Conflict("DISPUTE_CASE_CLOSED",
                    "Dispute case " + dispute.getCaseRef() + " is " + dispute.getStatus() + " and can no longer change");
        }
    }

    private void appendHistory(DisputeCase dispute, String type, DisputeStatus from, DisputeStatus to, String reason,
            Actor actor, Instant at, UUID ledgerEventId) {
        int next = caseEvents.findMaxSequenceNo(dispute.getId()) + 1; // under the case row lock
        caseEvents.save(new DisputeCaseEvent(dispute.getId(), next, type, from, to, reason, actor,
                MDC.get(CorrelationIdFilter.MDC_KEY), at, ledgerEventId));
    }

    private <T> T guarded(Actor actor, String action, String ref, Supplier<T> work) {
        try {
            return work.get();
        } catch (DomainException e) {
            auditFailure(actor, action, RESOURCE_CASE, ref, e, Map.of());
            throw e;
        } catch (ConcurrencyFailureException e) {
            auditFailure(actor, action, RESOURCE_CASE, ref,
                    new DomainException.Conflict("CONCURRENT_MODIFICATION", "stale version"), Map.of());
            throw e;
        }
    }

    private void audit(Actor actor, String action, String resourceId, Map<String, String> details) {
        publisher.publishEvent(AuditRecord.of(clock, actor, action, RESOURCE_CASE, resourceId).withDetails(details));
    }

    private void auditFailure(Actor actor, String action, String resourceType, String resourceId, DomainException e,
            Map<String, String> extra) {
        AuditRecord.Outcome outcome = e instanceof DomainException.Forbidden ? AuditRecord.Outcome.DENIED
                : AuditRecord.Outcome.FAILED;
        Map<String, String> details = new LinkedHashMap<>(extra);
        details.put("code", e.getCode());
        try {
            tx.executeWithoutResult(s -> publisher.publishEvent(AuditRecord.of(clock, actor, action, resourceType,
                    resourceId).withDetails(details).withOutcome(outcome)));
        } catch (RuntimeException auditError) {
            LOG.warn("Could not audit refused {} on {} ({}): {}", action, resourceId, e.getCode(), auditError.toString());
        }
    }

    private static Map<String, String> details(DisputeCase c, String... extra) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("paymentRef", c.getPaymentRef());
        details.put("status", c.getStatus().name());
        details.put("reasonCategory", c.getReasonCategory().name());
        details.put("disputedAmount", c.getDisputedAmount().toPlainString());
        details.put("currency", c.getCurrency());
        for (int i = 0; i + 1 < extra.length; i += 2) {
            details.put(extra[i], extra[i + 1]);
        }
        return details;
    }

    private static void requireKey(String key) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new DomainException.RuleViolation("IDEMPOTENCY_KEY_INVALID",
                    "An Idempotency-Key of 8-64 characters [A-Za-z0-9_-] is required");
        }
    }

    private static void requireSameFingerprint(String stored, String fingerprint) {
        if (!stored.equals(fingerprint)) {
            throw new DomainException.RuleViolation("IDEMPOTENCY_KEY_REUSED",
                    "This Idempotency-Key was already used for a different request");
        }
    }

    private static String normalize(String value, Pattern pattern, String code) {
        return requirePattern(value == null ? "" : value.trim().toUpperCase(), pattern, code);
    }

    private static String requirePattern(String value, Pattern pattern, String code) {
        if (!pattern.matcher(value).matches()) {
            throw new DomainException.RuleViolation(code, "Invalid value");
        }
        return value;
    }

    static String newCaseRef() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        return "DSP-" + HexFormat.of().withUpperCase().formatHex(bytes);
    }

    static String fingerprint(String... parts) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}

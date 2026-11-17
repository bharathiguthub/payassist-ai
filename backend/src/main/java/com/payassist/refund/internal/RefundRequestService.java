package com.payassist.refund.internal;

import com.payassist.identity.CurrentActor;
import com.payassist.identity.Roles;
import com.payassist.payment.PaymentEventSource;
import com.payassist.payment.PaymentEventType;
import com.payassist.payment.PaymentHistory.RefundPosition;
import com.payassist.payment.PaymentLedger;
import com.payassist.payment.RecordPaymentEvent;
import com.payassist.payment.RecordedPaymentEvent;
import com.payassist.refund.RefundApprovals;
import com.payassist.refund.RefundEligibility;
import com.payassist.refund.RefundEligibility.ReasonCode;
import com.payassist.refund.RefundReason;
import com.payassist.refund.RefundRequestStatus;
import com.payassist.refund.RefundRequestView;
import com.payassist.refund.RefundRequests;
import com.payassist.refund.RejectionReason;
import com.payassist.shared.Actor;
import com.payassist.shared.AuditRecord;
import com.payassist.shared.DomainException;
import com.payassist.shared.InvalidStateTransitionException;
import com.payassist.shared.MonetaryAmounts;
import com.payassist.shared.ResourceNotFoundException;
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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
 * Refund request workflow: creation (agents, supervisors), approval and rejection (supervisors), reads.
 *
 * <p><b>Transaction design.</b> Each write runs in one READ COMMITTED transaction that first takes the
 * payment row lock ({@link PaymentLedger#lockForRefund}) and then, for decisions, the refund request row lock.
 * The fixed lock order (payment, then request) prevents deadlocks; the payment lock serialises every refund
 * write for that payment, so reservations, re-validation and ledger writes see a stable state. READ COMMITTED
 * (not REPEATABLE READ) is deliberate: after waiting for the lock, each statement must see the rows the
 * previous lock holder committed. Failure audits run in a separate transaction after the main one rolled back.
 *
 * <p><b>No money moves.</b> Approval records a REFUND_RECORDED fact in the payment ledger (simulated refund).
 */
@Service
class RefundRequestService implements RefundRequests, RefundApprovals {

    static final String ACTION_CREATE = "REFUND_REQUEST_CREATE";
    static final String ACTION_VIEW = "REFUND_REQUEST_VIEW";
    static final String ACTION_APPROVE = "REFUND_REQUEST_APPROVE";
    static final String ACTION_REJECT = "REFUND_REQUEST_REJECT";
    static final String RESOURCE_REQUEST = "REFUND_REQUEST";
    static final String RESOURCE_PAYMENT = "PAYMENT";

    private static final Logger LOG = LoggerFactory.getLogger(RefundRequestService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Pattern PAYMENT_REF = Pattern.compile("^[A-Z0-9-]{1,32}$");
    private static final Pattern REFUND_REF = Pattern.compile("^RFD-[0-9A-F]{12}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PaymentLedger ledger;
    private final RefundRequestRepository requests;
    private final RefundDecisionRepository decisions;
    private final RefundEligibilityEngine engine;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    RefundRequestService(PaymentLedger ledger, RefundRequestRepository requests, RefundDecisionRepository decisions,
            RefundEligibilityEngine engine, ApplicationEventPublisher publisher, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.ledger = ledger;
        this.requests = requests;
        this.decisions = decisions;
        this.engine = engine;
        this.publisher = publisher;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    // --- creation -------------------------------------------------------------------------------------

    @Override
    @PreAuthorize(Roles.REQUEST_REFUNDS)
    public CreatedRefundRequest requestRefund(RequestRefundCommand command) {
        Actor actor = CurrentActor.require();
        String paymentRef = normalize(command.paymentRef(), PAYMENT_REF, "INVALID_PAYMENT_REF");
        if (command.idempotencyKey() == null || !IDEMPOTENCY_KEY.matcher(command.idempotencyKey()).matches()) {
            throw new DomainException.RuleViolation("IDEMPOTENCY_KEY_INVALID",
                    "An Idempotency-Key of 8-64 characters [A-Za-z0-9_-] is required");
        }
        if (command.currency() == null) {
            throw new DomainException.RuleViolation("CURRENCY_REQUIRED", "A currency is required");
        }
        BigDecimal amount = MonetaryAmounts.requirePositive(command.amount(), command.currency()); // never rounds
        String fingerprint = fingerprint(paymentRef, amount, command.currency(), command.reason());
        Map<String, String> failureDetails = Map.of("paymentRef", paymentRef, "amount", amount.toPlainString(),
                "currency", command.currency());

        try {
            try {
                return tx.execute(status -> create(actor, paymentRef, amount, command.currency(), command.reason(),
                        command.idempotencyKey(), fingerprint));
            } catch (DataIntegrityViolationException e) {
                // A concurrent request with the same idempotency key committed first (unique constraint).
                return tx.execute(status -> replay(actor, command.idempotencyKey(), fingerprint)).orElseThrow(() -> e);
            }
        } catch (DomainException e) {
            auditFailure(actor, ACTION_CREATE, RESOURCE_PAYMENT, paymentRef, e.getCode(), e, failureDetails);
            throw e;
        }
    }

    private CreatedRefundRequest create(Actor actor, String paymentRef, BigDecimal amount, String currency,
            RefundReason reason, String idempotencyKey, String fingerprint) {
        RefundPosition position = ledger.lockForRefund(paymentRef);

        // Checked under the payment lock: a concurrent retry with the same key waits here and then finds the
        // committed original instead of creating a duplicate.
        Optional<CreatedRefundRequest> previous = replay(actor, idempotencyKey, fingerprint);
        if (previous.isPresent()) {
            return previous.get();
        }

        Instant now = Instant.now(clock);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        BigDecimal reserved = requests.sumAmount(position.paymentId(), PendingRefundReservations.ACTIVE);
        RefundEligibility eligibility = engine.evaluate(position, amount, currency, reserved, now, correlationId);
        List<String> reasonCodes = eligibility.reasons().stream().map(r -> r.code().name()).toList();
        if (eligibility.decision() == RefundEligibility.Decision.INELIGIBLE) {
            throw notEligible("REFUND_NOT_ELIGIBLE", "The refund is not eligible", eligibility, reasonCodes);
        }

        boolean escalate = eligibility.reasons().stream().anyMatch(r -> r.code() == ReasonCode.SUPERVISOR_LIMIT_EXCEEDED
                || r.code() == ReasonCode.APPROVAL_LIMIT_NOT_CONFIGURED);
        RefundRequestStatus status = escalate ? RefundRequestStatus.ESCALATION_REQUIRED
                : RefundRequestStatus.PENDING_APPROVAL;

        RefundRequest request = new RefundRequest(UUID.randomUUID(), newRefundRef(), position.paymentId(),
                position.paymentRef(), amount, currency, reason, status, eligibility.decision(),
                String.join(",", reasonCodes), eligibility.policyVersion(), actor, now, correlationId,
                idempotencyKey, fingerprint);
        requests.saveAndFlush(request); // surfaces the idempotency unique constraint inside this transaction

        ledger.record(RecordPaymentEvent.of(position.paymentRef(),
                        escalate ? PaymentEventType.REFUND_ESCALATED : PaymentEventType.REFUND_REQUESTED, actor,
                        PaymentEventSource.PAYASSIST)
                .withAmount(amount, currency).withReason(reason.name()));

        audit(actor, ACTION_CREATE, request.getRefundRef(), AuditRecord.Outcome.SUCCESS, details(request,
                "eligibilityDecision", eligibility.decision().name(), "idempotentReplay", "false"));
        return new CreatedRefundRequest(view(request, null, actor), false);
    }

    /** The earlier request for this (user, key), if any; a different payload under the same key is refused. */
    private Optional<CreatedRefundRequest> replay(Actor actor, String idempotencyKey, String fingerprint) {
        return requests.findByRequestedBySubjectAndIdempotencyKey(actor.subject(), idempotencyKey).map(existing -> {
            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                throw new DomainException.RuleViolation("IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used for a different refund request");
            }
            audit(actor, ACTION_CREATE, existing.getRefundRef(), AuditRecord.Outcome.SUCCESS,
                    details(existing, "idempotentReplay", "true"));
            return new CreatedRefundRequest(view(existing, decisions.findByRefundRequestId(existing.getId())
                    .orElse(null), actor), true);
        });
    }

    // --- decisions ------------------------------------------------------------------------------------

    @Override
    @PreAuthorize(Roles.DECIDE_REFUNDS)
    public RefundRequestView approve(String refundRef, Long expectedVersion) {
        Actor actor = CurrentActor.require();
        String ref = normalize(refundRef, REFUND_REF, "INVALID_REFUND_REF");
        return decide(actor, ACTION_APPROVE, ref, () -> tx.execute(status -> doApprove(actor, ref, expectedVersion)));
    }

    @Override
    @PreAuthorize(Roles.DECIDE_REFUNDS)
    public RefundRequestView reject(String refundRef, RejectionReason reason, Long expectedVersion) {
        Actor actor = CurrentActor.require();
        String ref = normalize(refundRef, REFUND_REF, "INVALID_REFUND_REF");
        if (reason == null) {
            throw new DomainException.RuleViolation("REJECTION_REASON_REQUIRED", "A rejection reason is required");
        }
        return decide(actor, ACTION_REJECT, ref,
                () -> tx.execute(status -> doReject(actor, ref, reason, expectedVersion)));
    }

    private RefundRequestView decide(Actor actor, String action, String ref,
            java.util.function.Supplier<RefundRequestView> work) {
        try {
            return work.get();
        } catch (DomainException e) {
            auditFailure(actor, action, RESOURCE_REQUEST, ref, e.getCode(), e, Map.of());
            throw e;
        } catch (ConcurrencyFailureException e) {
            auditFailure(actor, action, RESOURCE_REQUEST, ref, "CONCURRENT_MODIFICATION", e, Map.of());
            throw e;
        }
    }

    private RefundRequestView doApprove(Actor actor, String ref, Long expectedVersion) {
        Locked locked = lock(ref);
        RefundRequest request = locked.request();
        checkVersion(request, expectedVersion);
        if (request.isRequestedBy(actor)) {
            throw new DomainException.Forbidden("SELF_APPROVAL_FORBIDDEN",
                    "A refund request cannot be approved by the person who requested it");
        }
        if (request.getStatus() == RefundRequestStatus.ESCALATION_REQUIRED) {
            throw new DomainException.Forbidden("ESCALATION_REQUIRED", "This refund exceeds the supervisor approval "
                    + "limit and needs escalation; it cannot be approved by a supervisor");
        }
        if (!RefundRequestTransitions.isAllowed(request.getStatus(), RefundRequestStatus.APPROVED)) {
            throw new InvalidStateTransitionException("Refund request", ref, request.getStatus(),
                    RefundRequestStatus.APPROVED);
        }
        if (!withinSupervisorLimit(request)) {
            throw new DomainException.Forbidden("APPROVAL_LIMIT_EXCEEDED",
                    "The amount exceeds the supervisor approval limit for " + request.getCurrency());
        }

        // Re-validate against authoritative state under the lock. Other pending requests still count as
        // reservations; this request's own reservation is what is being consumed. The refund window is
        // judged at submission time: a request submitted in time is not invalidated by review delays.
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        BigDecimal reservedByOthers = requests.sumAmountExcluding(request.getPaymentId(),
                PendingRefundReservations.ACTIVE, request.getId());
        RefundEligibility recheck = engine.evaluate(locked.position(), request.getAmount(), request.getCurrency(),
                reservedByOthers, request.getRequestedAt(), correlationId);
        if (recheck.decision() == RefundEligibility.Decision.INELIGIBLE) {
            throw notEligible("REFUND_NO_LONGER_ELIGIBLE", "The refund is no longer eligible", recheck,
                    recheck.reasons().stream().map(r -> r.code().name()).toList());
        }

        RecordedPaymentEvent recorded = ledger.record(RecordPaymentEvent.of(request.getPaymentRef(),
                        PaymentEventType.REFUND_RECORDED, actor, PaymentEventSource.PAYASSIST)
                .withAmount(request.getAmount(), request.getCurrency()).withReason(request.getReason().name()));
        Instant now = Instant.now(clock);
        RefundDecision decision = decisions.save(new RefundDecision(request.getId(), RefundRequestStatus.APPROVED,
                RefundDecision.APPROVED_REASON, actor, now, correlationId, recorded.eventId()));
        request.transitionTo(RefundRequestStatus.APPROVED, now);
        requests.flush();

        audit(actor, ACTION_APPROVE, ref, AuditRecord.Outcome.SUCCESS, details(request,
                "ledgerSequenceNo", Integer.toString(recorded.sequenceNo()), "paymentStatus",
                recorded.toStatus().name(), "policyVersion", recheck.policyVersion()));
        return view(request, decision, actor);
    }

    private RefundRequestView doReject(Actor actor, String ref, RejectionReason reason, Long expectedVersion) {
        Locked locked = lock(ref);
        RefundRequest request = locked.request();
        checkVersion(request, expectedVersion);
        if (request.isRequestedBy(actor)) {
            throw new DomainException.Forbidden("SELF_REJECTION_FORBIDDEN",
                    "A refund request cannot be decided by the person who requested it");
        }
        Instant now = Instant.now(clock);
        request.transitionTo(RefundRequestStatus.REJECTED, now); // 409 if already decided
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        RefundDecision decision = decisions.save(new RefundDecision(request.getId(), RefundRequestStatus.REJECTED,
                reason.name(), actor, now, correlationId, null));
        requests.flush();
        ledger.record(RecordPaymentEvent.of(request.getPaymentRef(), PaymentEventType.REFUND_REJECTED, actor,
                PaymentEventSource.PAYASSIST).withAmount(request.getAmount(), request.getCurrency())
                .withReason(reason.name()));

        audit(actor, ACTION_REJECT, ref, AuditRecord.Outcome.SUCCESS, details(request, "rejectionReason", reason.name()));
        return view(request, decision, actor);
    }

    private record Locked(RefundRequest request, RefundPosition position) {
    }

    /** Lock order: payment row first, then the refund request row (same order as creation: no deadlocks). */
    private Locked lock(String ref) {
        String paymentRef = requests.findPaymentRefByRefundRef(ref)
                .orElseThrow(() -> new ResourceNotFoundException("Refund request", ref));
        RefundPosition position = ledger.lockForRefund(paymentRef);
        RefundRequest request = requests.findByRefundRefForUpdate(ref)
                .orElseThrow(() -> new ResourceNotFoundException("Refund request", ref));
        return new Locked(request, position);
    }

    private static void checkVersion(RefundRequest request, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != request.getVersion()) {
            throw new ObjectOptimisticLockingFailureException(RefundRequest.class, request.getRefundRef());
        }
    }

    private boolean withinSupervisorLimit(RefundRequest request) {
        BigDecimal limit = engine.policy().supervisorLimit(request.getCurrency());
        return limit != null && request.getAmount().compareTo(limit) <= 0;
    }

    // --- reads ----------------------------------------------------------------------------------------

    @Override
    @PreAuthorize(Roles.READ_REFUNDS)
    public RefundRequestView getRefundStatus(String refundRef) {
        Actor actor = CurrentActor.require();
        String ref = normalize(refundRef, REFUND_REF, "INVALID_REFUND_REF");
        return tx.execute(status -> {
            RefundRequest request = requests.findByRefundRef(ref)
                    .orElseThrow(() -> new ResourceNotFoundException("Refund request", ref));
            RefundDecision decision = decisions.findByRefundRequestId(request.getId()).orElse(null);
            audit(actor, ACTION_VIEW, ref, AuditRecord.Outcome.SUCCESS, Map.of());
            return view(request, decision, actor);
        });
    }

    @Override
    @PreAuthorize(Roles.READ_REFUNDS)
    public PageResponse<RefundRequestView> listRefunds(RefundQuery query, int page, int size) {
        Actor actor = CurrentActor.require();
        List<Specification<RefundRequest>> filters = new ArrayList<>();
        if (!query.statuses().isEmpty()) {
            filters.add((root, q, cb) -> root.get("status").in(query.statuses()));
        }
        if (query.paymentRef() != null && !query.paymentRef().isBlank()) {
            String paymentRef = normalize(query.paymentRef(), PAYMENT_REF, "INVALID_PAYMENT_REF");
            filters.add((root, q, cb) -> cb.equal(root.get("paymentRef"), paymentRef));
        }
        if (query.mine()) {
            filters.add((root, q, cb) -> cb.equal(root.get("requestedBySubject"), actor.subject()));
        }
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.asc("refundRef")));
        return readTx.execute(status -> {
            Page<RefundRequest> result = requests.findAll(Specification.allOf(filters), pageRequest);
            Map<UUID, RefundDecision> decisionByRequest = decisions.findByRefundRequestIdIn(
                    result.getContent().stream().map(RefundRequest::getId).toList()).stream()
                    .collect(Collectors.toMap(RefundDecision::getRefundRequestId, Function.identity()));
            return PageResponse.from(result, r -> view(r, decisionByRequest.get(r.getId()), actor));
        });
    }

    // --- mapping, audit, helpers ----------------------------------------------------------------------

    private RefundRequestView view(RefundRequest r, RefundDecision d, Actor viewer) {
        List<RefundRequestView.Action> actions = new ArrayList<>();
        if (CurrentActor.hasRole(Roles.CARE_SUPERVISOR) && !r.isRequestedBy(viewer)) {
            if (RefundRequestTransitions.isAllowed(r.getStatus(), RefundRequestStatus.APPROVED)
                    && withinSupervisorLimit(r)) {
                actions.add(RefundRequestView.Action.APPROVE);
            }
            if (RefundRequestTransitions.isAllowed(r.getStatus(), RefundRequestStatus.REJECTED)) {
                actions.add(RefundRequestView.Action.REJECT);
            }
        }
        List<String> reasonCodes = r.getEligibilityReasons().isEmpty() ? List.of()
                : Arrays.asList(r.getEligibilityReasons().split(","));
        RefundRequestView.Decision decision = d == null ? null : new RefundRequestView.Decision(d.getDecision(),
                d.getReasonCode(), d.getDecidedByUsername(), d.getDecidedAt(), d.getCorrelationId(),
                d.getLedgerEventId() != null);
        return new RefundRequestView(RefundRequestView.SCHEMA_VERSION, r.getRefundRef(), r.getPaymentRef(),
                r.getAmount(), r.getCurrency(), r.getReason(), r.getStatus(),
                new RefundRequestView.Eligibility(r.getEligibilityDecision(), reasonCodes, r.getPolicyVersion()),
                r.getRequestedByUsername(), r.getRequestedAt(), r.getCorrelationId(), decision, List.copyOf(actions),
                r.getVersion());
    }

    /** Successful action: audited in the current transaction (if the audit fails, the action fails). */
    private void audit(Actor actor, String action, String resourceId, AuditRecord.Outcome outcome,
            Map<String, String> details) {
        publisher.publishEvent(AuditRecord.of(clock, actor, action, RESOURCE_REQUEST, resourceId)
                .withDetails(details).withOutcome(outcome));
    }

    /**
     * Refused action: the main transaction has rolled back, so the audit record is written in its own
     * transaction. Best effort: a failure here is logged and must not hide the original error.
     */
    private void auditFailure(Actor actor, String action, String resourceType, String resourceId, String code,
            RuntimeException cause, Map<String, String> extra) {
        AuditRecord.Outcome outcome = cause instanceof DomainException.Forbidden ? AuditRecord.Outcome.DENIED
                : AuditRecord.Outcome.FAILED;
        Map<String, String> details = new LinkedHashMap<>(extra);
        details.put("code", code);
        try {
            tx.executeWithoutResult(status -> publisher.publishEvent(
                    AuditRecord.of(clock, actor, action, resourceType, resourceId).withDetails(details)
                            .withOutcome(outcome)));
        } catch (RuntimeException e) {
            LOG.warn("Could not audit refused {} on {} ({}): {}", action, resourceId, code, e.toString());
        }
    }

    private static Map<String, String> details(RefundRequest r, String... extra) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("paymentRef", r.getPaymentRef());
        details.put("amount", r.getAmount().toPlainString());
        details.put("currency", r.getCurrency());
        details.put("status", r.getStatus().name());
        for (int i = 0; i + 1 < extra.length; i += 2) {
            details.put(extra[i], extra[i + 1]);
        }
        return details;
    }

    private static DomainException notEligible(String code, String message, RefundEligibility eligibility,
            List<String> reasonCodes) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("reasons", reasonCodes);
        properties.put("policyVersion", eligibility.policyVersion());
        return new DomainException.RuleViolation(code, message + ": " + String.join(", ", reasonCodes), properties);
    }

    private static String normalize(String value, Pattern pattern, String code) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (!pattern.matcher(normalized).matches()) {
            throw new DomainException.RuleViolation(code, "Invalid reference");
        }
        return normalized;
    }

    static String newRefundRef() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        return "RFD-" + HexFormat.of().withUpperCase().formatHex(bytes);
    }

    /** SHA-256 over the canonical payload; amounts are already at currency scale, so "10" and "10.00" match. */
    static String fingerprint(String paymentRef, BigDecimal amount, String currency, RefundReason reason) {
        String canonical = paymentRef + "|" + amount.toPlainString() + "|" + currency + "|" + reason.name();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}

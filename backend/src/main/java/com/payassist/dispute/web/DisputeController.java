package com.payassist.dispute.web;

import com.payassist.dispute.DisputeCaseWork;
import com.payassist.dispute.DisputeCaseWork.AddEvidenceCommand;
import com.payassist.dispute.DisputeCaseWork.CreatedDispute;
import com.payassist.dispute.DisputeCaseWork.OpenDisputeCommand;
import com.payassist.dispute.DisputeOutcome;
import com.payassist.dispute.DisputeQueries;
import com.payassist.dispute.DisputeQueries.DisputeQuery;
import com.payassist.dispute.DisputeReasonCategory;
import com.payassist.dispute.DisputeResolutions;
import com.payassist.dispute.DisputeStatus;
import com.payassist.dispute.DisputeView;
import com.payassist.dispute.EvidenceType;
import com.payassist.dispute.ReturnReason;
import com.payassist.dispute.WithdrawalReason;
import com.payassist.identity.Roles;
import com.payassist.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dispute case API. Authorization: URL rules in {@code SecurityConfig}, {@code @PreAuthorize} here and on the
 * service, business rules in the service (state machine, separation of duties). All POSTs need the CSRF token;
 * creating cases, notes and evidence also needs an {@code Idempotency-Key}; transitions need {@code expectedVersion}.
 */
@RestController
@RequestMapping("/api/v1/disputes")
class DisputeController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
    private static final String CASE_REF = "^[Dd][Ss][Pp]-[0-9A-Fa-f]{12}$";
    private static final String KEY = "^[A-Za-z0-9_-]{8,64}$";

    private final DisputeQueries queries;
    private final DisputeCaseWork work;
    private final DisputeResolutions resolutions;

    DisputeController(DisputeQueries queries, DisputeCaseWork work, DisputeResolutions resolutions) {
        this.queries = queries;
        this.work = work;
        this.resolutions = resolutions;
    }

    // --- reads ----------------------------------------------------------------------------------------

    @GetMapping
    @PreAuthorize(Roles.READ_DISPUTES)
    PageResponse<DisputeView.Summary> list(
            @RequestParam(name = "status", required = false) List<DisputeStatus> statuses,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Za-z0-9-]{1,32}$") String paymentRef,
            @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queries.listDisputes(new DisputeQuery(statuses == null ? Set.of() : Set.copyOf(statuses), paymentRef,
                overdue, mine), page, size);
    }

    @GetMapping("/{caseRef}")
    @PreAuthorize(Roles.READ_DISPUTES)
    DisputeView get(@PathVariable @Pattern(regexp = CASE_REF) String caseRef) {
        return queries.getDispute(caseRef);
    }

    // --- case work (agents and supervisors) -------------------------------------------------------------

    /** 201 for a new case; 200 with {@code Idempotent-Replayed: true} for an exact retry. */
    @PostMapping
    @PreAuthorize(Roles.WORK_DISPUTES)
    ResponseEntity<DisputeView> open(@RequestHeader(IDEMPOTENCY_KEY) @Pattern(regexp = KEY) String key,
            @Valid @RequestBody OpenRequest body) {
        CreatedDispute created = work.openDispute(new OpenDisputeCommand(body.paymentRef(), body.reasonCategory(),
                body.networkReasonCode(), body.disputedAmount(), body.currency(), body.responseDueAt(), key));
        return ResponseEntity.status(created.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(URI.create("/api/v1/disputes/" + created.dispute().caseRef()))
                .header(IDEMPOTENT_REPLAYED, Boolean.toString(created.replayed()))
                .body(created.dispute());
    }

    @PostMapping("/{caseRef}/notes")
    @PreAuthorize(Roles.WORK_DISPUTES)
    DisputeView addNote(@PathVariable @Pattern(regexp = CASE_REF) String caseRef,
            @RequestHeader(IDEMPOTENCY_KEY) @Pattern(regexp = KEY) String key, @Valid @RequestBody NoteRequest body) {
        return work.addNote(caseRef, body.body(), key);
    }

    @PostMapping("/{caseRef}/evidence")
    @PreAuthorize(Roles.WORK_DISPUTES)
    DisputeView addEvidence(@PathVariable @Pattern(regexp = CASE_REF) String caseRef,
            @RequestHeader(IDEMPOTENCY_KEY) @Pattern(regexp = KEY) String key, @Valid @RequestBody EvidenceRequest body) {
        return work.addEvidence(caseRef, new AddEvidenceCommand(body.type(), body.documentReference(),
                body.description(), body.receivedAt(), key));
    }

    @PostMapping("/{caseRef}/start-investigation")
    @PreAuthorize(Roles.WORK_DISPUTES)
    DisputeView startInvestigation(@PathVariable @Pattern(regexp = CASE_REF) String caseRef,
            @Valid @RequestBody VersionRequest body) {
        return work.startInvestigation(caseRef, body.expectedVersion());
    }

    @PostMapping("/{caseRef}/request-review")
    @PreAuthorize(Roles.WORK_DISPUTES)
    DisputeView requestReview(@PathVariable @Pattern(regexp = CASE_REF) String caseRef,
            @Valid @RequestBody VersionRequest body) {
        return work.requestReview(caseRef, body.expectedVersion());
    }

    // --- human-only supervisor decisions ----------------------------------------------------------------

    @PostMapping("/{caseRef}/return-for-investigation")
    @PreAuthorize(Roles.RESOLVE_DISPUTES)
    DisputeView returnForInvestigation(@PathVariable @Pattern(regexp = CASE_REF) String caseRef,
            @Valid @RequestBody ReturnRequest body) {
        return resolutions.returnForInvestigation(caseRef, body.reason(), body.expectedVersion());
    }

    @PostMapping("/{caseRef}/approve-submission")
    @PreAuthorize(Roles.RESOLVE_DISPUTES)
    DisputeView approveSubmission(@PathVariable @Pattern(regexp = CASE_REF) String caseRef,
            @Valid @RequestBody VersionRequest body) {
        return resolutions.approveSubmission(caseRef, body.expectedVersion());
    }

    @PostMapping("/{caseRef}/record-outcome")
    @PreAuthorize(Roles.RESOLVE_DISPUTES)
    DisputeView recordOutcome(@PathVariable @Pattern(regexp = CASE_REF) String caseRef,
            @Valid @RequestBody OutcomeRequest body) {
        return resolutions.recordOutcome(caseRef, body.outcome(), body.expectedVersion());
    }

    @PostMapping("/{caseRef}/accept-liability")
    @PreAuthorize(Roles.RESOLVE_DISPUTES)
    DisputeView acceptLiability(@PathVariable @Pattern(regexp = CASE_REF) String caseRef,
            @Valid @RequestBody VersionRequest body) {
        return resolutions.acceptLiability(caseRef, body.expectedVersion());
    }

    @PostMapping("/{caseRef}/withdraw")
    @PreAuthorize(Roles.RESOLVE_DISPUTES)
    DisputeView withdraw(@PathVariable @Pattern(regexp = CASE_REF) String caseRef,
            @Valid @RequestBody WithdrawRequest body) {
        return resolutions.withdraw(caseRef, body.reason(), body.expectedVersion());
    }

    // --- request bodies -------------------------------------------------------------------------------

    /**
     * @param disputedAmount optional; if given (with currency) it must equal the full unrefunded amount
     * @param responseDueAt  optional, as provided by the bank/network notification (ISO-8601 instant)
     */
    record OpenRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{1,32}$") String paymentRef,
            @NotNull DisputeReasonCategory reasonCategory,
            @Pattern(regexp = "^[A-Za-z0-9._-]{1,16}$") String networkReasonCode,
            @Digits(integer = 17, fraction = 4) BigDecimal disputedAmount,
            @Pattern(regexp = "^[A-Z]{3}$") String currency,
            Instant responseDueAt) {
    }

    record NoteRequest(@NotBlank @Size(max = 2000) String body) {
    }

    record EvidenceRequest(@NotNull EvidenceType type,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:/-]{1,128}$") String documentReference,
            @Size(max = 500) String description, Instant receivedAt) {
    }

    record VersionRequest(@NotNull @Min(0) Long expectedVersion) {
    }

    record ReturnRequest(@NotNull ReturnReason reason, @NotNull @Min(0) Long expectedVersion) {
    }

    record OutcomeRequest(@NotNull DisputeOutcome outcome, @NotNull @Min(0) Long expectedVersion) {
    }

    record WithdrawRequest(@NotNull WithdrawalReason reason, @NotNull @Min(0) Long expectedVersion) {
    }
}

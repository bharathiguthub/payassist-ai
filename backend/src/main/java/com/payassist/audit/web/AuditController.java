package com.payassist.audit.web;

import com.payassist.audit.internal.AuditCheckpointStatus;
import com.payassist.audit.internal.AuditCheckpointStatusService;
import com.payassist.audit.internal.AuditEvent;
import com.payassist.audit.internal.AuditIntegrityReport;
import com.payassist.audit.internal.AuditQueryService;
import com.payassist.audit.internal.AuditQueryService.AuditQuery;
import com.payassist.identity.Roles;
import com.payassist.shared.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only view of the audit trail and its integrity (ADR 0013). Every call is itself audited by
 * {@link AuditQueryService}. Authorization: URL rule ({@code /api/v1/admin/**}), {@code @PreAuthorize} here and on
 * the service.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-events")
class AuditController {

    private static final String NAME = "^[A-Za-z0-9._:-]{1,128}$";
    private static final String CODE = "^[A-Z][A-Z_]{0,63}$";

    private final AuditQueryService audit;
    private final AuditCheckpointStatusService checkpoints;

    AuditController(AuditQueryService audit, AuditCheckpointStatusService checkpoints) {
        this.audit = audit;
        this.checkpoints = checkpoints;
    }

    @GetMapping
    @PreAuthorize(Roles.ADMIN_ONLY)
    PageResponse<AuditEventResponse> list(
            @RequestParam(required = false) @Pattern(regexp = NAME) String actor,
            @RequestParam(required = false) @Pattern(regexp = NAME) String actorSubject,
            @RequestParam(required = false) @Pattern(regexp = CODE) String action,
            @RequestParam(required = false) @Pattern(regexp = CODE) String resourceType,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Za-z0-9 ._:/-]{1,128}$") String resourceId,
            @RequestParam(required = false) @Pattern(regexp = "^(SUCCESS|DENIED|FAILED)$") String outcome,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Za-z0-9-]{8,64}$") String correlationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var query = new AuditQuery(actor, actorSubject, action, resourceType, resourceId, outcome, correlationId,
                from, to);
        return PageResponse.from(audit.search(query, page, size), AuditController::toResponse);
    }

    /** Recomputes the whole hash chain. O(rows); intended for on-demand checks by an administrator. */
    @GetMapping("/integrity")
    @PreAuthorize(Roles.ADMIN_ONLY)
    AuditIntegrityReport integrity() {
        return audit.verifyIntegrity();
    }

    /**
     * Reported audit-integrity status (ADR 0013 section 12): the in-app chain verification and the runs reported by
     * the external checkpoint job, with warnings. Cheap (no chain recomputation); audited.
     */
    @GetMapping("/checkpoint-status")
    @PreAuthorize(Roles.ADMIN_ONLY)
    AuditCheckpointStatus checkpointStatus() {
        return checkpoints.status();
    }

    private static AuditEventResponse toResponse(AuditEvent e) {
        return new AuditEventResponse(e.getId(), e.getOccurredAt(), e.getActor(), e.getActorType(), e.getAction(),
                e.getResourceType(), e.getResourceId(), e.getCorrelationId(), e.getOutcome(), e.getDetails(),
                e.getChainSeq() == null ? "LEGACY" : "CHAINED", e.getChainSeq(), e.getRowHash());
    }

    /**
     * {@code protection}: CHAINED rows are covered by the hash chain; LEGACY rows predate it and are pinned only by
     * the genesis anchor's count and digest.
     */
    record AuditEventResponse(Long id, Instant occurredAt, String actor, String actorType, String action,
            String resourceType, String resourceId, String correlationId, String outcome, Map<String, String> details,
            String protection, Long chainSeq, String rowHash) {
    }
}

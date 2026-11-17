package com.payassist.audit.web;

import com.payassist.audit.internal.AuditEvent;
import com.payassist.audit.internal.AuditEventRepository;
import com.payassist.identity.Roles;
import com.payassist.shared.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only view of the audit trail. */
@RestController
@RequestMapping("/api/v1/admin/audit-events")
class AuditController {

    private final AuditEventRepository auditEvents;

    AuditController(AuditEventRepository auditEvents) {
        this.auditEvents = auditEvents;
    }

    @GetMapping
    @PreAuthorize(Roles.ADMIN_ONLY)
    @Transactional(readOnly = true)
    PageResponse<AuditEventResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var request = PageRequest.of(page, size, Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        return PageResponse.from(auditEvents.findAll(request), AuditController::toResponse);
    }

    private static AuditEventResponse toResponse(AuditEvent e) {
        return new AuditEventResponse(e.getId(), e.getOccurredAt(), e.getActor(), e.getAction(),
                e.getResourceType(), e.getResourceId(), e.getCorrelationId());
    }

    record AuditEventResponse(Long id, Instant occurredAt, String actor, String action, String resourceType,
            String resourceId, String correlationId) {
    }
}

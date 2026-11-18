package com.payassist.audit.internal;

import com.payassist.identity.CurrentActor;
import com.payassist.identity.Roles;
import com.payassist.shared.AuditRecord;
import com.payassist.shared.DomainException;
import com.payassist.shared.TextGuard;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADMIN-only access to the audit trail. Reading the trail and verifying its integrity are themselves audited
 * ({@code AUDIT_LOG_VIEW}, {@code AUDIT_INTEGRITY_CHECK}) in the same transaction: if that audit row cannot be
 * written, nothing is returned (fail closed). Authorization is checked here and on the controller.
 */
@Service
public class AuditQueryService {

    static final String ACTION_VIEW = "AUDIT_LOG_VIEW";
    static final String ACTION_INTEGRITY = "AUDIT_INTEGRITY_CHECK";
    static final String RESOURCE_TYPE = "AUDIT_LOG";
    static final String RESOURCE_ID = "audit_events";

    private final AuditEventRepository auditEvents;
    private final AuditIntegrityVerifier verifier;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    AuditQueryService(AuditEventRepository auditEvents, AuditIntegrityVerifier verifier,
            ApplicationEventPublisher publisher, Clock clock) {
        this.auditEvents = auditEvents;
        this.verifier = verifier;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** Exact-match filters; {@code from} inclusive, {@code to} exclusive. All optional. */
    public record AuditQuery(String actor, String actorSubject, String action, String resourceType,
            String resourceId, String outcome, String correlationId, Instant from, Instant to) {
    }

    @PreAuthorize(Roles.ADMIN_ONLY)
    @Transactional
    public Page<AuditEvent> search(AuditQuery query, int page, int size) {
        var actor = CurrentActor.require();
        if (query.from() != null && query.to() != null && !query.from().isBefore(query.to())) {
            throw new DomainException.RuleViolation("INVALID_TIME_RANGE", "'from' must be before 'to'");
        }
        // Filter values end up in the audit record of this read; refuse anything that looks like personal data.
        for (String text : new String[] {query.actor(), query.resourceId()}) {
            if (TextGuard.containsPersonalData(text)) {
                throw new DomainException.RuleViolation("PERSONAL_DATA_IN_FILTER",
                        "Search by references, not by personal data");
            }
        }

        var request = PageRequest.of(page, size, Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        Page<AuditEvent> result = auditEvents.findAll(specification(query), request);

        Map<String, String> details = new LinkedHashMap<>();
        putIfPresent(details, "filterActor", query.actor());
        putIfPresent(details, "filterActorSubject", query.actorSubject());
        putIfPresent(details, "filterAction", query.action());
        putIfPresent(details, "filterResourceType", query.resourceType());
        putIfPresent(details, "filterResourceId", query.resourceId());
        putIfPresent(details, "filterOutcome", query.outcome());
        putIfPresent(details, "filterCorrelationId", query.correlationId());
        putIfPresent(details, "filterFrom", query.from() == null ? null : query.from().toString());
        putIfPresent(details, "filterTo", query.to() == null ? null : query.to().toString());
        details.put("page", Integer.toString(page));
        details.put("size", Integer.toString(size));
        details.put("returned", Integer.toString(result.getNumberOfElements()));
        publisher.publishEvent(AuditRecord.of(clock, actor, ACTION_VIEW, RESOURCE_TYPE, RESOURCE_ID)
                .withDetails(details));
        return result;
    }

    @PreAuthorize(Roles.ADMIN_ONLY)
    @Transactional
    public AuditIntegrityReport verifyIntegrity() {
        var actor = CurrentActor.require();
        AuditIntegrityReport report = verifier.verify();
        Map<String, String> details = new LinkedHashMap<>();
        details.put("result", report.result());
        details.put("chainedRows", Long.toString(report.chainedRows()));
        details.put("legacyRows", Long.toString(report.legacyRows()));
        putIfPresent(details, "headChainSeq", report.headChainSeq() == null ? null : report.headChainSeq().toString());
        if (report.firstFailure() != null) {
            var failure = report.firstFailure();
            putIfPresent(details, "failureAuditEventId",
                    failure.auditEventId() == null ? null : failure.auditEventId().toString());
            details.put("failureReason", failure.reason());
        }
        var record = AuditRecord.of(clock, actor, ACTION_INTEGRITY, RESOURCE_TYPE, RESOURCE_ID).withDetails(details);
        publisher.publishEvent(AuditIntegrityReport.VALID.equals(report.result()) ? record
                : record.withOutcome(AuditRecord.Outcome.FAILED));
        return report;
    }

    private static Specification<AuditEvent> specification(AuditQuery q) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            equal(predicates, cb, root.get("actor"), q.actor());
            equal(predicates, cb, root.get("actorSubject"), q.actorSubject());
            equal(predicates, cb, root.get("action"), q.action());
            equal(predicates, cb, root.get("resourceType"), q.resourceType());
            equal(predicates, cb, root.get("resourceId"), q.resourceId());
            equal(predicates, cb, root.get("correlationId"), q.correlationId());
            if (q.outcome() != null) {
                // Phase 1 rows have NULL outcome, which means SUCCESS.
                predicates.add("SUCCESS".equals(q.outcome())
                        ? cb.or(cb.equal(root.get("outcome"), "SUCCESS"), cb.isNull(root.get("outcome")))
                        : cb.equal(root.get("outcome"), q.outcome()));
            }
            if (q.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), q.from()));
            }
            if (q.to() != null) {
                predicates.add(cb.lessThan(root.get("occurredAt"), q.to()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static void equal(List<Predicate> predicates,
            CriteriaBuilder cb, Path<String> path,
            String value) {
        if (value != null) {
            predicates.add(cb.equal(path, value));
        }
    }

    private static void putIfPresent(Map<String, String> details, String key, String value) {
        if (value != null) {
            details.put(key, value);
        }
    }
}

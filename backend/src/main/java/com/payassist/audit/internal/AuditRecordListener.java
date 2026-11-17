package com.payassist.audit.internal;

import com.payassist.shared.AuditRecord;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Persists {@link AuditRecord}s published by any module. Synchronous and inside the publisher's
 * transaction: if the audit row cannot be written, the audited operation fails too (fail closed).
 */
@Component
class AuditRecordListener {

    private final AuditEventRepository auditEvents;

    AuditRecordListener(AuditEventRepository auditEvents) {
        this.auditEvents = auditEvents;
    }

    @EventListener
    void on(AuditRecord record) {
        auditEvents.save(new AuditEvent(record.occurredAt(), record.actor().username(), record.actor().subject(),
                record.action(), record.resourceType(), record.resourceId(), record.correlationId()));
    }
}

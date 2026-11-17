package com.payassist.audit.internal;

import com.payassist.payment.PaymentViewedEvent;
import com.payassist.shared.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Records payment views. The listener is synchronous and joins the caller's transaction, so if
 * the audit write fails the payment details are not returned (fail closed). Later phases may move
 * this to an outbox + Kafka for throughput; the payment module would not change.
 */
@Component
class PaymentAccessAuditor {

    static final String ACTION_VIEW = "VIEW";
    static final String RESOURCE_PAYMENT = "PAYMENT";

    private final AuditEventRepository auditEvents;

    PaymentAccessAuditor(AuditEventRepository auditEvents) {
        this.auditEvents = auditEvents;
    }

    @EventListener
    void on(PaymentViewedEvent event) {
        auditEvents.save(new AuditEvent(event.occurredAt(), event.actor(), null, ACTION_VIEW, RESOURCE_PAYMENT,
                event.paymentRef(), MDC.get(CorrelationIdFilter.MDC_KEY), null, "SUCCESS"));
    }
}

package com.payassist.audit.internal;

import com.payassist.identity.CurrentActor;
import com.payassist.shared.Actor;
import com.payassist.shared.AuditRecord;
import com.payassist.shared.DataProtectionRefusal;
import java.time.Clock;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Audits writes that the database refused ({@code DATA_PROTECTION_REFUSED}, outcome DENIED). The request's own
 * transaction has rolled back, so the record is written in a new READ COMMITTED transaction. Best effort: the caller
 * already returns the refusal to the client.
 */
@Component
class DataProtectionRefusalAuditor {

    static final String ACTION = "DATA_PROTECTION_REFUSED";
    private static final int MAX_RESOURCE_ID = 128;

    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final TransactionTemplate tx;

    DataProtectionRefusalAuditor(ApplicationEventPublisher publisher, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.publisher = publisher;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @EventListener
    void on(DataProtectionRefusal refusal) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return;
        }
        Actor actor = CurrentActor.from(authentication);
        String resourceId = refusal.method() + " " + refusal.path();
        if (resourceId.length() > MAX_RESOURCE_ID) {
            resourceId = resourceId.substring(0, MAX_RESOURCE_ID);
        }
        var record = AuditRecord.of(clock, actor, ACTION, "ENDPOINT", resourceId)
                .withDetails(Map.of("code", refusal.code())).withOutcome(AuditRecord.Outcome.DENIED);
        tx.executeWithoutResult(status -> publisher.publishEvent(record));
    }
}

package com.payassist.investigation;

import com.payassist.identity.CurrentActor;
import com.payassist.identity.Roles;
import com.payassist.investigation.internal.InvestigationSummaryBuilder;
import com.payassist.payment.PaymentHistory;
import com.payassist.payment.PaymentHistory.PaymentTimeline;
import com.payassist.shared.AuditRecord;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment investigation use cases. This is the intended seam for future AI tool calling (Spring AI
 * {@code @Tool} / MCP): it is web-free, permission-checked by method security against the calling
 * user, resolves the actor from the security context (never from an argument), audits every access,
 * and returns a stable, versioned, fact-only record.
 */
@Service
public class InvestigationService {

    public static final String ACTION_VIEW_SUMMARY = "VIEW_INVESTIGATION_SUMMARY";
    static final String RESOURCE_PAYMENT = "PAYMENT";

    private final PaymentHistory history;
    private final InvestigationSummaryBuilder builder;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    InvestigationService(PaymentHistory history, InvestigationSummaryBuilder builder,
            ApplicationEventPublisher publisher, Clock clock) {
        this.history = history;
        this.builder = builder;
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Summarises a payment from its recorded facts. Read and audit write share one transaction, so the
     * summary is never returned without its audit record.
     */
    @PreAuthorize(Roles.INVESTIGATE_PAYMENTS)
    @Transactional
    public InvestigationSummary summarize(String paymentRef) {
        PaymentTimeline timeline = history.timeline(paymentRef);
        InvestigationSummary summary = builder.build(timeline, Instant.now(clock));
        publisher.publishEvent(AuditRecord.of(clock, CurrentActor.require(), ACTION_VIEW_SUMMARY, RESOURCE_PAYMENT,
                summary.paymentRef()));
        return summary;
    }
}

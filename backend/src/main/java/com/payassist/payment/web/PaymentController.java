package com.payassist.payment.web;

import com.payassist.identity.Roles;
import com.payassist.payment.PaymentHistory.PaymentTimeline;
import com.payassist.payment.PaymentHistory.TimelineEntry;
import com.payassist.payment.PaymentStatus;
import com.payassist.payment.internal.PaymentHistoryService;
import com.payassist.payment.internal.PaymentQueryService;
import com.payassist.payment.internal.PaymentViews.PaymentDetails;
import com.payassist.payment.internal.PaymentViews.PaymentListItem;
import com.payassist.payment.internal.PaymentViews.PaymentStats;
import com.payassist.shared.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payment investigation API. URL rules in {@code SecurityConfig} are the first gate; the
 * {@code @PreAuthorize} rules here are the second, so a misconfigured URL pattern cannot expose data.
 */
@RestController
@RequestMapping("/api/v1/payments")
class PaymentController {

    private static final String REF_PATTERN = "^[A-Za-z0-9-]{1,32}$";

    private final PaymentQueryService payments;
    private final PaymentHistoryService history;

    PaymentController(PaymentQueryService payments, PaymentHistoryService history) {
        this.payments = payments;
        this.history = history;
    }

    @GetMapping
    @PreAuthorize(Roles.ANY_CARE_ROLE)
    PageResponse<PaymentListItem> search(
            @RequestParam(name = "q", required = false) @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9-]*$") String query,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return payments.search(query, status, page, size);
    }

    @GetMapping("/stats")
    @PreAuthorize(Roles.SUPERVISOR_OR_ADMIN)
    PaymentStats stats() {
        return payments.stats();
    }

    @GetMapping("/{paymentRef}")
    @PreAuthorize(Roles.ANY_CARE_ROLE)
    PaymentDetails details(@PathVariable @Pattern(regexp = REF_PATTERN) String paymentRef, Principal principal) {
        return payments.viewDetails(paymentRef, principal.getName());
    }

    /**
     * Chronological event history (oldest first; ties broken by recording order). Audited as
     * {@code VIEW_TIMELINE}. Returns no customer data: use the details endpoint for that.
     */
    @GetMapping("/{paymentRef}/timeline")
    @PreAuthorize(Roles.INVESTIGATE_PAYMENTS)
    TimelineResponse timeline(@PathVariable @Pattern(regexp = REF_PATTERN) String paymentRef) {
        PaymentTimeline timeline = history.viewTimeline(paymentRef);
        var payment = timeline.payment();
        return new TimelineResponse(payment.paymentRef(), payment.status(), payment.statusDescription(),
                payment.amount(), payment.currency(), timeline.events().size(), timeline.events());
    }

    record TimelineResponse(String paymentRef, PaymentStatus currentStatus, String currentStatusDescription,
            BigDecimal amount, String currency, int eventCount, List<TimelineEntry> events) {
    }
}

package com.payassist.refund.web;

import com.payassist.identity.Roles;
import com.payassist.refund.RefundApprovals;
import com.payassist.refund.RefundReason;
import com.payassist.refund.RefundRequestStatus;
import com.payassist.refund.RefundRequestView;
import com.payassist.refund.RefundRequests;
import com.payassist.refund.RefundRequests.CreatedRefundRequest;
import com.payassist.refund.RefundRequests.RefundQuery;
import com.payassist.refund.RefundRequests.RequestRefundCommand;
import com.payassist.refund.RejectionReason;
import com.payassist.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.net.URI;
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
 * Refund request API. Authorization: URL rules in {@code SecurityConfig}, {@code @PreAuthorize} here and on
 * the service, plus business rules in the service (separation of duties, approval limits). All POSTs need the
 * CSRF token; creation also needs an {@code Idempotency-Key} header.
 */
@RestController
@RequestMapping("/api/v1/refunds")
class RefundRequestController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
    private static final String REFUND_REF = "^[Rr][Ff][Dd]-[0-9A-Fa-f]{12}$";

    private final RefundRequests refunds;
    private final RefundApprovals approvals;

    RefundRequestController(RefundRequests refunds, RefundApprovals approvals) {
        this.refunds = refunds;
        this.approvals = approvals;
    }

    /**
     * 201 Created for a new request; 200 OK with {@code Idempotent-Replayed: true} when the same key and
     * payload were already accepted (nothing new is created).
     */
    @PostMapping
    @PreAuthorize(Roles.REQUEST_REFUNDS)
    ResponseEntity<RefundRequestView> create(
            @RequestHeader(IDEMPOTENCY_KEY) @Pattern(regexp = "^[A-Za-z0-9_-]{8,64}$") String idempotencyKey,
            @Valid @RequestBody CreateRefundRequest body) {
        CreatedRefundRequest created = refunds.requestRefund(new RequestRefundCommand(body.paymentRef(), body.amount(),
                body.currency(), body.reason(), idempotencyKey));
        RefundRequestView view = created.request();
        return ResponseEntity.status(created.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .location(URI.create("/api/v1/refunds/" + view.refundRef()))
                .header(IDEMPOTENT_REPLAYED, Boolean.toString(created.replayed()))
                .body(view);
    }

    @GetMapping("/{refundRef}")
    @PreAuthorize(Roles.READ_REFUNDS)
    RefundRequestView get(@PathVariable @Pattern(regexp = REFUND_REF) String refundRef) {
        return refunds.getRefundStatus(refundRef);
    }

    /** E.g. the supervisor queue: {@code ?status=PENDING_APPROVAL&status=ESCALATION_REQUIRED}. */
    @GetMapping
    @PreAuthorize(Roles.READ_REFUNDS)
    PageResponse<RefundRequestView> list(
            @RequestParam(name = "status", required = false) List<RefundRequestStatus> statuses,
            @RequestParam(required = false) @Pattern(regexp = "^[A-Za-z0-9-]{1,32}$") String paymentRef,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return refunds.listRefunds(new RefundQuery(statuses == null ? Set.of() : Set.copyOf(statuses), paymentRef,
                mine), page, size);
    }

    @PostMapping("/{refundRef}/approve")
    @PreAuthorize(Roles.DECIDE_REFUNDS)
    RefundRequestView approve(@PathVariable @Pattern(regexp = REFUND_REF) String refundRef,
            @Valid @RequestBody(required = false) ApproveRequest body) {
        return approvals.approve(refundRef, body == null ? null : body.expectedVersion());
    }

    @PostMapping("/{refundRef}/reject")
    @PreAuthorize(Roles.DECIDE_REFUNDS)
    RefundRequestView reject(@PathVariable @Pattern(regexp = REFUND_REF) String refundRef,
            @Valid @RequestBody RejectRequest body) {
        return approvals.reject(refundRef, body.reason(), body.expectedVersion());
    }

    /** {@code amount}: JSON number or decimal string; precision beyond the currency's minor units → 422. */
    record CreateRefundRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{1,32}$") String paymentRef,
            @NotNull @Digits(integer = 17, fraction = 4) BigDecimal amount,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency,
            @NotNull RefundReason reason) {
    }

    record ApproveRequest(@Min(0) Long expectedVersion) {
    }

    record RejectRequest(@NotNull RejectionReason reason, @Min(0) Long expectedVersion) {
    }
}

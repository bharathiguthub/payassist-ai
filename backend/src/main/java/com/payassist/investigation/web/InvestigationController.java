package com.payassist.investigation.web;

import com.payassist.identity.Roles;
import com.payassist.investigation.InvestigationService;
import com.payassist.investigation.InvestigationSummary;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Investigation summary API. Read-only for CARE_AGENT, CARE_SUPERVISOR and (explicitly) ADMIN. */
@RestController
@RequestMapping("/api/v1/payments")
class InvestigationController {

    private final InvestigationService investigations;

    InvestigationController(InvestigationService investigations) {
        this.investigations = investigations;
    }

    /** Verified facts and rule-based findings for one payment. Audited as {@code VIEW_INVESTIGATION_SUMMARY}. */
    @GetMapping("/{paymentRef}/investigation-summary")
    @PreAuthorize(Roles.INVESTIGATE_PAYMENTS)
    InvestigationSummary summary(@PathVariable @Pattern(regexp = "^[A-Za-z0-9-]{1,32}$") String paymentRef) {
        return investigations.summarize(paymentRef);
    }
}

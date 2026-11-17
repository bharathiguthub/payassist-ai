package com.payassist.refund.web;

import com.payassist.identity.Roles;
import com.payassist.refund.RefundEligibility;
import com.payassist.refund.RefundEligibilityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Refund eligibility API. POST (with a body) rather than GET because the request carries an amount, but
 * it is read-only: nothing is created. Like every unsafe method it requires the CSRF token.
 */
@RestController
@RequestMapping("/api/v1/refunds")
class RefundEligibilityController {

    private final RefundEligibilityService eligibility;

    RefundEligibilityController(RefundEligibilityService eligibility) {
        this.eligibility = eligibility;
    }

    @PostMapping("/eligibility-check")
    @PreAuthorize(Roles.CHECK_REFUND_ELIGIBILITY)
    RefundEligibility check(@Valid @RequestBody EligibilityCheckRequest request) {
        return eligibility.check(request.paymentRef(), request.amount(), request.currency());
    }

    /**
     * @param amount   optional; JSON number or decimal string. Omit (with currency) to ask how much is refundable.
     *                 Precision beyond the currency's minor units is rejected (422), never rounded.
     * @param currency ISO 4217 code; required when {@code amount} is present
     */
    record EligibilityCheckRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{1,32}$") String paymentRef,
            @Digits(integer = 17, fraction = 4) BigDecimal amount,
            @Pattern(regexp = "^[A-Z]{3}$") String currency) {

        @AssertTrue(message = "currency is required when amount is given")
        boolean isCurrencyGivenWithAmount() {
            return amount == null || currency != null;
        }
    }
}

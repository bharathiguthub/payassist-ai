package com.payassist.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.payassist.shared.DomainException;
import com.payassist.shared.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void invalidTransitionIs409WithStableCode() {
        ProblemDetail problem = handler.handleConflict(
                new InvalidStateTransitionException("Payment", "PAY-000001", "FAILED", "CAPTURED"));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties()).containsEntry("code", "INVALID_STATE_TRANSITION");
        assertThat(problem.getType().toString()).endsWith("/invalid-state-transition");
        assertThat(problem.getDetail()).isEqualTo("Payment PAY-000001 cannot apply CAPTURED in state FAILED");
    }

    @Test
    void ruleViolationIs422WithStableCode() {
        ProblemDetail problem = handler.handleRuleViolation(
                new DomainException.RuleViolation("CURRENCY_MISMATCH", "Amount currency must match"));

        assertThat(problem.getStatus()).isEqualTo(422);
        assertThat(problem.getProperties()).containsEntry("code", "CURRENCY_MISMATCH");
    }

    @Test
    void optimisticLockFailureIs409WithoutInternalDetails() {
        ProblemDetail problem = handler.handleConcurrencyFailure(
                new ObjectOptimisticLockingFailureException("com.payassist.payment.internal.Payment", "some-id"));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties()).containsEntry("code", "CONCURRENT_MODIFICATION");
        assertThat(problem.getDetail()).doesNotContain("com.payassist").doesNotContain("some-id");
    }
}

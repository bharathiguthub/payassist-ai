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

    @Test
    void guardTriggerRefusalIs409RecordProtectedWithoutTheDatabaseMessage() {
        var problem = handler.handleDataAccess(dbError("P0001", "refund_requests: RFD-1 is decided (APPROVED) and final"),
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/v1/refunds/RFD-1/approve"));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties()).containsEntry("code", "RECORD_PROTECTED").containsKey("correlationId");
        assertThat(problem.getDetail()).doesNotContain("refund_requests").doesNotContain("RFD-1");
    }

    @Test
    void missingPrivilegeIs500DatabasePermissionDeniedWithoutTheDatabaseMessage() {
        var problem = handler.handleDataAccess(dbError("42501", "permission denied for table refund_requests"),
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/v1/refunds"));

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getProperties()).containsEntry("code", "DATABASE_PERMISSION_DENIED");
        assertThat(problem.getDetail()).doesNotContain("refund_requests").doesNotContain("permission denied for");
    }

    @Test
    void otherDatabaseFailuresAreGeneric500sWithoutDetails() {
        var problem = handler.handleDataAccess(dbError("23505", "duplicate key value violates unique constraint uq_x"),
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/v1/disputes"));

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getProperties()).doesNotContainKey("code");
        assertThat(problem.getDetail()).doesNotContain("uq_x");
    }

    private static org.springframework.dao.DataAccessException dbError(String sqlState, String message) {
        return new org.springframework.jdbc.UncategorizedSQLException("statement", "update x",
                new java.sql.SQLException(message, sqlState));
    }
}

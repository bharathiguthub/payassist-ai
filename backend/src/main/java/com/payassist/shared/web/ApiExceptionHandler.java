package com.payassist.shared.web;

import com.payassist.shared.DomainException;
import com.payassist.shared.ResourceNotFoundException;
import java.net.URI;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to RFC 9457 Problem Details. The base class already covers validation and
 * type-mismatch errors (400). Unexpected errors fall through to Spring Boot's default handler,
 * which never includes stack traces (see {@code server.error.*} settings).
 *
 * <p>Business-rule failures add a stable {@code code} property so clients (and future AI tools) can
 * react to the rule that failed without parsing human-readable text.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    public static final String CODE_PROPERTY = "code";
    public static final String CONCURRENT_MODIFICATION = "CONCURRENT_MODIFICATION";

    private static final String PROBLEM_BASE = "https://payassist.example/problems/";

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Resource not found");
        problem.setType(URI.create(PROBLEM_BASE + "not-found"));
        return problem;
    }

    @ExceptionHandler(DomainException.Conflict.class)
    ProblemDetail handleConflict(DomainException.Conflict ex) {
        return domainProblem(HttpStatus.CONFLICT, "Conflict with current state", ex);
    }

    @ExceptionHandler(DomainException.RuleViolation.class)
    ProblemDetail handleRuleViolation(DomainException.RuleViolation ex) {
        return domainProblem(HttpStatus.UNPROCESSABLE_ENTITY, "Business rule violated", ex);
    }

    /** Optimistic-lock version mismatch or lock contention: the client should reload and retry. */
    @ExceptionHandler(ConcurrencyFailureException.class)
    ProblemDetail handleConcurrencyFailure(ConcurrencyFailureException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The resource was modified by another request. Reload it and try again.");
        problem.setTitle("Concurrent modification");
        problem.setType(URI.create(PROBLEM_BASE + "concurrent-modification"));
        problem.setProperty(CODE_PROPERTY, CONCURRENT_MODIFICATION);
        return problem;
    }

    private static ProblemDetail domainProblem(HttpStatus status, String title, DomainException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle(title);
        problem.setType(URI.create(PROBLEM_BASE + ex.getCode().toLowerCase().replace('_', '-')));
        problem.setProperty(CODE_PROPERTY, ex.getCode());
        return problem;
    }
}

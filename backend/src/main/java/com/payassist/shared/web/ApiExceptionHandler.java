package com.payassist.shared.web;

import com.payassist.shared.DataProtectionRefusal;
import com.payassist.shared.DomainException;
import com.payassist.shared.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
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
public class ApiExceptionHandler extends ResponseEntityExceptionHandler implements ApplicationEventPublisherAware {

    public static final String RECORD_PROTECTED = "RECORD_PROTECTED";
    public static final String DATABASE_PERMISSION_DENIED = "DATABASE_PERMISSION_DENIED";
    public static final String CORRELATION_ID_PROPERTY = "correlationId";
    /** PL/pgSQL RAISE EXCEPTION (guard and append-only triggers). */
    static final String SQLSTATE_RAISE = "P0001";
    /** insufficient_privilege (the runtime role lacks a grant). */
    static final String SQLSTATE_INSUFFICIENT_PRIVILEGE = "42501";

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private ApplicationEventPublisher publisher;

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

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

    @ExceptionHandler(DomainException.Forbidden.class)
    ProblemDetail handleForbidden(DomainException.Forbidden ex) {
        return domainProblem(HttpStatus.FORBIDDEN, "Action not permitted", ex);
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

    /**
     * Writes the database refused (ADR 0013 section 11). The client gets a stable code and the correlation ID, never
     * the database message (it names tables, triggers and values); the server log keeps the SQL state. Any other
     * data-access failure is a generic 500 without details.
     */
    @ExceptionHandler(DataAccessException.class)
    ProblemDetail handleDataAccess(DataAccessException ex, HttpServletRequest request) {
        String sqlState = sqlState(ex);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        ProblemDetail problem;
        if (SQLSTATE_RAISE.equals(sqlState)) {
            problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                    "This record is protected and cannot be changed this way. Reload it to see its current state.");
            problem.setTitle("Record protected");
            problem.setType(URI.create(PROBLEM_BASE + "record-protected"));
            problem.setProperty(CODE_PROPERTY, RECORD_PROTECTED);
        } else if (SQLSTATE_INSUFFICIENT_PRIVILEGE.equals(sqlState)) {
            problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The server is not permitted to perform this change. Contact support with the correlation ID.");
            problem.setTitle("Database permission denied");
            problem.setType(URI.create(PROBLEM_BASE + "database-permission-denied"));
            problem.setProperty(CODE_PROPERTY, DATABASE_PERMISSION_DENIED);
        } else {
            LOG.error("Unexpected data access failure (sqlState={})", sqlState, ex);
            problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                    "An unexpected error occurred. Contact support with the correlation ID.");
            problem.setTitle("Internal error");
            problem.setProperty(CORRELATION_ID_PROPERTY, correlationId);
            return problem;
        }
        String code = (String) problem.getProperties().get(CODE_PROPERTY);
        LOG.warn("Database refused a write: {} (sqlState={}, {} {})", code, sqlState, request.getMethod(),
                request.getRequestURI());
        problem.setProperty(CORRELATION_ID_PROPERTY, correlationId);
        if (publisher != null) {
            try {
                publisher.publishEvent(new DataProtectionRefusal(code, request.getMethod(), request.getRequestURI()));
            } catch (RuntimeException auditError) {
                LOG.warn("Could not audit the database refusal ({})", auditError.getClass().getSimpleName());
            }
        }
        return problem;
    }

    static String sqlState(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static ProblemDetail domainProblem(HttpStatus status, String title, DomainException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle(title);
        problem.setType(URI.create(PROBLEM_BASE + ex.getCode().toLowerCase().replace('_', '-')));
        problem.setProperty(CODE_PROPERTY, ex.getCode());
        ex.getProperties().forEach(problem::setProperty);
        return problem;
    }
}

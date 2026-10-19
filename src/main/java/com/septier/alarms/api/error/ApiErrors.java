package com.septier.alarms.api.error;

import com.septier.alarms.domain.exception.AlarmConflict;
import com.septier.alarms.domain.exception.ApiFailure;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.*;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.transaction.TransactionException;

@RestControllerAdvice
class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(ApiFailure.class)
    ProblemDetail operational(ApiFailure ex) { return ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(ex.status()), ex.getMessage()); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException ex) {
        LOG.error("Persistent data constraint rejected operation: {}", ex.getClass().getSimpleName());
        return problem(HttpStatus.CONFLICT, "Operation violates a persistent data constraint; repeating it unchanged will not resolve the conflict");
    }
    @ExceptionHandler(AlarmConflict.class)
    ProblemDetail conflict(AlarmConflict ex) { return problem(HttpStatus.CONFLICT, ex.getMessage()); }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException ex) {
        var p = problem(HttpStatus.BAD_REQUEST, "Request validation failed");
        p.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage()).toList());
        return p;
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class,
        HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ProblemDetail malformed(Exception ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid JSON, field type, enum, identifier or pagination. Severities: INFO, WARNING, MAJOR, CRITICAL; statuses: RAISED, CLEARED.");
    }
    @ExceptionHandler({TransientDataAccessException.class, DataAccessResourceFailureException.class,
        org.springframework.transaction.CannotCreateTransactionException.class, org.springframework.transaction.TransactionTimedOutException.class})
    ResponseEntity<ProblemDetail> unavailable(Exception ex) {
        LOG.warn("Alarm database operation failed: {}", ex.getClass().getSimpleName());
        return ResponseEntity.status(503).header("Retry-After", "1")
            .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "Storage is temporarily unavailable; retry using the same eventId"));
    }
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ProblemDetail> permanent(Exception ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.startsWith("40") || state.startsWith("53")
                        || state.equals("55P03") || state.equals("57014") || state.equals("57P01")
                        || state.equals("57P02") || state.equals("57P03"))) return unavailable(ex);
            }
        }
        LOG.error("Non-retryable storage failure: {}", ex.getClass().getSimpleName());
        return ResponseEntity.status(500).body(problem(HttpStatus.INTERNAL_SERVER_ERROR,
            "Storage operation failed; contact the operator rather than repeatedly retrying"));
    }
    private ProblemDetail problem(HttpStatus status, String detail) { return ProblemDetail.forStatusAndDetail(status, detail); }
}

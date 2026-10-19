package com.septier.alarms.api.error;

import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import static org.assertj.core.api.Assertions.*;

class ApiErrorsTest {
    private final ApiErrors errors = new ApiErrors();
    @Test void transientFailureIsRetryableAndSanitized() {
        var result = errors.unavailable(new DataAccessResourceFailureException("secret SQL/password"));
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(result.getBody().getDetail()).doesNotContain("secret");
    }
    @Test void permanentFailureDoesNotRecommendRetry() {
        var result = errors.permanent(new InvalidDataAccessResourceUsageException("secret SQL"));
        assertThat(result.getStatusCode().value()).isEqualTo(500);
        assertThat(result.getHeaders()).doesNotContainKey("Retry-After");
        assertThat(result.getBody().getDetail()).contains("contact the operator").doesNotContain("secret SQL");
    }
    @Test void postgresLockTimeoutAndWrappedCommitDisconnectRemainRetryable() {
        var lock = new org.springframework.jdbc.UncategorizedSQLException("lock", "private SQL",
            new java.sql.SQLException("private error", "55P03"));
        assertThat(errors.permanent(lock).getStatusCode().value()).isEqualTo(503);
        var commit = new org.springframework.transaction.TransactionSystemException("commit",
            new java.sql.SQLException("private error", "08006"));
        assertThat(errors.permanent(commit).getStatusCode().value()).isEqualTo(503);
    }
    @Test void constraintFailureIsAConflict() {
        assertThat(errors.integrity(new DataIntegrityViolationException("private constraint")).getStatus()).isEqualTo(409);
    }
}

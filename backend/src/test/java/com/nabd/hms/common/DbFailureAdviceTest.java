package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OPS-003 criteria 4 and 5: a real database timeout and a real exhausted pool, translated by Spring exactly as in a request,
 * become 503 Problem+JSON through the advice. (Advice registration itself is plain @RestControllerAdvice.)
 */
class DbFailureAdviceTest extends ApiTestBase {

    private static DataAccessException thrownBy(Runnable r) {
        try {
            r.run();
        } catch (DataAccessException e) {
            return e;
        }
        throw new AssertionError("expected a DataAccessException");
    }

    @Test
    void slowQueryAsAppRoleIsCancelledAndBecomes503DbTimeout() {
        long t0 = System.nanoTime();
        DataAccessException ex = thrownBy(() -> jdbc.queryForObject("SELECT pg_sleep(7)::text", String.class));
        assertThat((System.nanoTime() - t0) / 1_000_000).as("cancelled by the role's 5 s statement_timeout").isBetween(4_500L, 6_500L);

        ResponseEntity<ProblemDetail> res = new DbFailureAdvice().handle(ex);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(res.getHeaders().getFirst("Retry-After")).isNotBlank();
        ProblemDetail p = res.getBody();
        assertThat(p.getStatus()).isEqualTo(503);
        assertThat(p.getProperties()).containsEntry("code", "DB_TIMEOUT").containsKey("traceId");
        assertThat(p.getType().toString()).endsWith("/db-timeout");
        assertThat(String.valueOf(p.getDetail()) + p.getTitle()).as("no SQL or driver text leaks").doesNotContain("pg_sleep").doesNotContain("PSQL");
    }

    @Test
    void exhaustedPoolFailsAfterAboutThreeSecondsAndBecomes503() throws Exception {
        HikariDataSource app = jdbc.getDataSource().unwrap(HikariDataSource.class);
        try (HikariDataSource tiny = new HikariDataSource()) {
            tiny.setJdbcUrl(app.getJdbcUrl());
            tiny.setUsername(app.getUsername());
            tiny.setPassword(app.getPassword());
            tiny.setMaximumPoolSize(1);
            tiny.setConnectionTimeout(app.getConnectionTimeout()); // the application's own setting
            try (java.sql.Connection held = tiny.getConnection()) {
                long t0 = System.nanoTime();
                DataAccessException ex = thrownBy(() -> new JdbcTemplate(tiny).queryForObject("SELECT 1", Integer.class));
                assertThat((System.nanoTime() - t0) / 1_000_000).as("fails at the 3 s connection timeout, not Hikari's 30 s default")
                        .isBetween(2_800L, 5_000L);
                assertThat(held.isClosed()).isFalse();
                ResponseEntity<ProblemDetail> res = new DbFailureAdvice().handle(ex);
                assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(res.getBody().getProperties()).containsEntry("code", "DB_UNAVAILABLE");
            }
        }
    }

    @Test
    void unrelatedDatabaseErrorsAreNotDisguisedAs503() {
        DataAccessException ex = thrownBy(() -> jdbc.queryForObject("SELECT (1/0)::text", String.class));
        assertThat(DbFailureAdvice.classify(ex)).isNull();
        assertThatThrownBy(() -> new DbFailureAdvice().handle(ex)).isSameAs(ex);
    }
}

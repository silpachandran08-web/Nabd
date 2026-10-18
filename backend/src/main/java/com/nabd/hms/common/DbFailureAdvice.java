package com.nabd.hms.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.sql.SQLException;
import java.util.UUID;

/**
 * OPS-003: the database enforces timeouts (nabd_app: statement 5 s, lock 3 s) and Hikari fails fast after 3 s, so a
 * slow query or an exhausted pool is a 503 with a stable code, never a 500 or a 30 s hang. Never echoes SQL or driver text.
 */
@RestControllerAdvice
public class DbFailureAdvice {

    private static final Logger log = LoggerFactory.getLogger(DbFailureAdvice.class);
    private static final String TYPE_BASE = "https://api.nabd.health/errors/";

    @ExceptionHandler({QueryTimeoutException.class, UncategorizedSQLException.class, CannotAcquireLockException.class,
            DataAccessResourceFailureException.class})
    public org.springframework.http.ResponseEntity<ProblemDetail> handle(DataAccessException ex) {
        String code = classify(ex);
        if (code == null) {
            throw ex; // not ours: stays whatever it was before this advice existed
        }
        boolean timeout = code.equals("DB_TIMEOUT");
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        p.setType(URI.create(TYPE_BASE + (timeout ? "db-timeout" : "db-unavailable")));
        p.setTitle(timeout ? "Database timeout" : "Database unavailable");
        p.setDetail(timeout ? "The request took too long and was cancelled. Try again."
                : "The service is busy. Try again shortly.");
        p.setProperty("code", code);
        String traceId = MDC.get(RequestCorrelationFilter.MDC_KEY);
        p.setProperty("traceId", traceId != null ? traceId : UUID.randomUUID().toString());
        log.warn("[{}] {} ({})", p.getProperties().get("traceId"), p.getTitle(), ex.getClass().getSimpleName());
        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "2").contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }

    /** DB_TIMEOUT for statement_timeout (57014) / lock_timeout (55P03); DB_UNAVAILABLE when no connection could be had. */
    static String classify(Throwable ex) {
        if (ex instanceof QueryTimeoutException || ex instanceof CannotAcquireLockException) {
            return "DB_TIMEOUT";
        }
        if (ex instanceof CannotGetJdbcConnectionException) {
            return "DB_UNAVAILABLE";
        }
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && ("57014".equals(sql.getSQLState()) || "55P03".equals(sql.getSQLState()))) {
                return "DB_TIMEOUT";
            }
            if (t instanceof java.sql.SQLTransientConnectionException) {
                return "DB_UNAVAILABLE";
            }
        }
        return null;
    }
}

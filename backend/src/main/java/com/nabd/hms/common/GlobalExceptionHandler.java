package com.nabd.hms.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** RFC 7807 problem+json for every error — see api/openapi.yaml's Problem schema. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://api.nabd.health/errors/";

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(ex.status());
        problem.setType(URI.create(TYPE_BASE + ex.typeSlug()));
        problem.setTitle(ex.title());
        problem.setDetail(ex.getMessage());
        return withTraceId(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create(TYPE_BASE + "validation-failed"));
        problem.setTitle("Validation failed");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        problem.setProperty("errors", errors);
        return withTraceId(problem);
    }

    private Map<String, String> toFieldError(FieldError fe) {
        return Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage()));
    }

    private ProblemDetail withTraceId(ProblemDetail problem) {
        // RequestCorrelationFilter always sets this before a controller runs; the fallback only
        // covers an error thrown outside a normal request (there isn't one in this codebase today)
        String traceId = MDC.get(RequestCorrelationFilter.MDC_KEY);
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        problem.setProperty("traceId", traceId);
        log.warn("[{}] {} {}", traceId, problem.getTitle(), problem.getDetail());
        return problem;
    }
}

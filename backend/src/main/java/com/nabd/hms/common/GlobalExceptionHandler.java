package com.nabd.hms.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
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
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://api.nabd.health/errors/";

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(ex.status());
        problem.setType(URI.create(TYPE_BASE + ex.typeSlug()));
        problem.setTitle(ex.title());
        problem.setDetail(ex.getMessage());
        ex.extras().forEach(problem::setProperty);
        return withTraceId(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create(TYPE_BASE + "validation-failed"));
        problem.setTitle("Validation failed");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        problem.setProperty("errors", errors);
        // A readable summary so clients that show only `detail` still name the field (field names and the
        // constraint's message only, never the rejected value).
        problem.setDetail(errors.stream().limit(3).map(e -> e.get("field") + ": " + e.get("message"))
                .collect(java.util.stream.Collectors.joining("; ")));
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(withTraceId(problem));
    }

    /** PLT-027: Spring's own MVC errors (bad param type, unknown route, wrong method ...) get the typed problem and a traceId too. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> r = super.handleExceptionInternal(ex, body, headers, status, request);
        if (r != null && r.getBody() instanceof ProblemDetail p) {
            int code = p.getStatus();
            p.setType(URI.create(TYPE_BASE + (code == 404 ? "not-found" : code == 409 ? "conflict" : code == 403 ? "forbidden"
                    : code == 429 ? "rate-limited" : code < 500 ? "validation" : "internal")));
            withTraceId(p);
        }
        return r;
    }

    /** A token that lacks an expected claim (controllers do UUID.fromString(jwt.getClaimAsString(..))) is a 401, never a 500. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) throws Exception {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex; // Spring Security's own 401/403 handling
        }
        if (ex instanceof RuntimeException re && DbFailureAdvice.classify(ex) != null) {
            return new DbFailureAdvice().handle(re); // 503, whichever advice Spring consults first
        }
        // shortcut: matched by the throwing frame (UUID.fromString on a null claim); move to a typed claim accessor when the 40 controllers are touched next
        if (ex instanceof NullPointerException && ex.getStackTrace().length > 0
                && "java.util.UUID".equals(ex.getStackTrace()[0].getClassName())) {
            ProblemDetail p = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
            p.setType(URI.create(TYPE_BASE + "unauthenticated"));
            p.setTitle("Unauthenticated");
            p.setDetail("The access token is missing a required claim.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("WWW-Authenticate", "Bearer error=\"invalid_token\"")
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(withTraceId(p));
        }
        ProblemDetail p = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        p.setType(URI.create(TYPE_BASE + "internal"));
        p.setTitle("Internal error");
        p.setDetail("Something went wrong. Quote the traceId to support.");
        String traceId = traceId();
        p.setProperty("traceId", traceId);
        log.error("[{}] unhandled {}", traceId, ex.getClass().getName(), ex); // stack stays in the server log only
        return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(p);
    }

    private Map<String, String> toFieldError(FieldError fe) {
        return Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage()));
    }

    private ProblemDetail withTraceId(ProblemDetail problem) {
        // RequestCorrelationFilter always sets this before a controller runs; the fallback only
        // covers an error thrown outside a normal request (there isn't one in this codebase today)
        String traceId = traceId();
        problem.setProperty("traceId", traceId);
        log.warn("[{}] {} {}", traceId, problem.getTitle(), problem.getDetail());
        return problem;
    }

    private static String traceId() {
        String traceId = MDC.get(RequestCorrelationFilter.MDC_KEY);
        return traceId != null ? traceId : UUID.randomUUID().toString();
    }
}

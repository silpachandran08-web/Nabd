package com.nabd.hms.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Tags every log line written during a request with the same id, and echoes it back as
 * X-Request-Id so a caller can hand it to support. GlobalExceptionHandler reuses this exact
 * value as the error response's "traceId" — one id ties what the client sees to every server
 * log line for that request, not two coincidentally-similar ones.
 *
 * Runs at HIGHEST_PRECEDENCE so it wraps the Spring Security filter chain too — a 401/403 still
 * gets logged under the same correlation id as everything else in the request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "requestId";
    private static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // threads are pooled by the servlet container — an un-cleared MDC value leaks onto
            // whatever unrelated request that thread handles next
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(HEADER);
        if (incoming != null) {
            try {
                // only accept a clean UUID from the caller — free-text here would land straight
                // in every log line for the request, an easy log-injection (CWE-117) vector otherwise
                return UUID.fromString(incoming).toString();
            } catch (IllegalArgumentException ignored) {
                // fall through and mint our own
            }
        }
        return UUID.randomUUID().toString();
    }
}

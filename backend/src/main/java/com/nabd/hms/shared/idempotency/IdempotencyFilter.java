package com.nabd.hms.shared.idempotency;

import com.nabd.hms.shared.Idempotent;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.ServletRequestPathUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * PLT-026 (T-031): exactly-once for @Idempotent POSTs. Registered inside the Spring Security chain (SecurityConfig), after
 * authentication, because the key is scoped to (tenant, actor). Only 2xx responses are stored and replayed; a 4xx/5xx
 * releases the key so the corrected or retried request runs again. The request body is only hashed, never stored.
 * shortcut: the request body is buffered in memory, fine for JSON commands, move to a size cap if an @Idempotent upload appears.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED = "Idempotent-Replayed";
    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final ObjectProvider<RequestMappingHandlerMapping> mappings; // lazy: MVC beans are not ready while SecurityConfig builds
    private final IdempotencyStore store;

    IdempotencyFilter(ObjectProvider<RequestMappingHandlerMapping> mappings, IdempotencyStore store) {
        this.mappings = mappings;
        this.store = store;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!"POST".equals(request.getMethod()) || !(auth instanceof JwtAuthenticationToken jwt) || !isIdempotent(request)) {
            chain.doFilter(request, response);
            return;
        }
        String rawKey = request.getHeader(HEADER);
        UUID key = parse(rawKey);
        UUID tenant = parse(jwt.getToken().getClaimAsString("tenantId"));
        UUID actor = parse(jwt.getToken().getSubject());
        if (tenant == null || actor == null) { // not a staff token: leave the 401/403 to the normal chain
            chain.doFilter(request, response);
            return;
        }
        if (key == null) {
            problem(response, HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key required",
                    "This action needs an Idempotency-Key header holding a UUID.");
            return;
        }
        byte[] body = request.getInputStream().readAllBytes();
        byte[] hash = hash(request, body);
        IdempotencyStore.Outcome o = store.begin(tenant, actor, key, request.getMethod(), request.getRequestURI(), hash);
        switch (o.kind()) {
            case IN_PROGRESS -> problem(response, HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS", "Request in progress",
                    "The first attempt with this Idempotency-Key is still running; retry shortly.");
            case REUSED -> problem(response, HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key reused",
                    "This Idempotency-Key was already used with a different request.");
            case REPLAY -> {
                response.setStatus(o.status());
                response.setHeader(REPLAYED, "true");
                if (o.body().length > 0) {
                    response.setContentType("application/json");
                    response.getOutputStream().write(o.body());
                }
            }
            case STARTED -> run(new ReplayableRequest(request, body), response, chain, tenant, actor, key);
        }
    }

    private void run(HttpServletRequest request, HttpServletResponse response, FilterChain chain, UUID tenant, UUID actor, UUID key)
            throws ServletException, IOException {
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapped);
        } catch (IOException | ServletException | RuntimeException e) {
            finish(tenant, actor, key, 500, null);
            throw e;
        }
        finish(tenant, actor, key, wrapped.getStatus(), wrapped.getContentAsByteArray());
        wrapped.copyBodyToResponse();
    }

    private void finish(UUID tenant, UUID actor, UUID key, int status, byte[] body) {
        try {
            if (status >= 200 && status < 300) store.complete(tenant, actor, key, status, body);
            else store.release(tenant, actor, key);
        } catch (RuntimeException e) { // the action already ran; the row stays in_progress until its lease lapses
            log.warn("idempotency bookkeeping failed for status {}", status, e);
        }
    }

    private boolean isIdempotent(HttpServletRequest request) {
        boolean cached = ServletRequestPathUtils.hasParsedRequestPath(request); // getHandler() needs the parsed path before DispatcherServlet
        if (!cached) ServletRequestPathUtils.parseAndCache(request);
        try {
            HandlerExecutionChain h = mappings.getObject().getHandler(request);
            return h != null && h.getHandler() instanceof HandlerMethod m && m.hasMethodAnnotation(Idempotent.class);
        } catch (Exception e) {
            return false;
        } finally {
            if (!cached) ServletRequestPathUtils.clearParsedRequestPath(request);
        }
    }

    private static UUID parse(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] hash(HttpServletRequest r, byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((r.getMethod() + "\n" + r.getRequestURI() + "\n" + r.getQueryString() + "\n").getBytes(StandardCharsets.UTF_8));
            return md.digest(body);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void problem(HttpServletResponse res, HttpStatus status, String code, String title, String detail) throws IOException {
        String traceId = MDC.get("requestId");
        res.setStatus(status.value());
        res.setContentType("application/problem+json");
        res.getWriter().write("{\"type\":\"https://api.nabd.health/errors/" + code.toLowerCase().replace('_', '-') + "\",\"title\":\"" + title
                + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail + "\",\"code\":\"" + code
                + "\",\"traceId\":\"" + (traceId != null ? traceId : UUID.randomUUID()) + "\"}");
    }

    /** Re-serves the already-read body to the controller. */
    private static final class ReplayableRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        ReplayableRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener l) { throw new UnsupportedOperationException(); }
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}

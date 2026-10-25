package com.nabd.hms.shared.ratelimit;

import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.RequestCorrelationFilter;
import com.nabd.hms.common.RequestMeta;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PLT-008: per-tenant + per-user buckets for authenticated calls, per-IP for anonymous (pre-auth, public) calls, kept in
 * Valkey so every replica shares them (TokenBuckets). Runs inside the security chain right after bearer authentication
 * (SecurityConfig), before the idempotency store. Over the limit: 429 Problem type rate_limited with Retry-After.
 * Valkey not configured or failing: the request goes through (fail open), counted in nabd_rate_limit_fail_open_total
 * (alert rule infra/observability/rate-limit.rules.yml). OTP endpoints are left to PLT-006's Postgres OtpSendLimiter,
 * which stays silent (202) and fails closed. Keys hold ids or hashes only, never phone numbers, names or raw IPs.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    static final String EXCEEDED_EVENT = "shared.rate_limit.exceeded.v1";
    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final Set<String> EXEMPT = Set.of("/v1/auth/otp/request", "/v1/auth/otp/verify", "/v1/webhooks/whatsapp");
    private static final long FAIL_OPEN_LOG_EVERY_NANOS = 60_000_000_000L;

    private final TokenBuckets buckets;
    private final PlanRateLimits plans;
    private final RateLimitProperties props;
    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private volatile long nextFailOpenLog = System.nanoTime();

    RateLimitFilter(TokenBuckets buckets, PlanRateLimits plans, RateLimitProperties props, EventPublisher events,
                    ObjectMapper mapper, MeterRegistry meters) {
        this.buckets = buckets;
        this.plans = plans;
        this.props = props;
        this.events = events;
        this.mapper = mapper;
        this.meters = meters;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return EXEMPT.contains(request.getRequestURI()) || !buckets.configured();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID tenant = null;
        List<String> dims;
        List<TokenBuckets.Bucket> list;
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt) {
            String sub = String.valueOf(jwt.getToken().getSubject());
            String tid = jwt.getToken().getClaimAsString("tenantId");
            tenant = tid == null ? null : parse(tid);
            if (tenant != null) {
                PlanRateLimits.Limit t = plans.forTenant(tenant);
                dims = List.of("tenant", "user");
                list = List.of(new TokenBuckets.Bucket("rl:tenant:{" + tenant + "}", t.perMinute(), t.burst()),
                        new TokenBuckets.Bucket("rl:user:{" + tenant + "}:" + sub, props.userPerMinute(), props.userBurst()));
            } else { // operator / owner tokens: no tenant, the user bucket alone
                dims = List.of("user");
                list = List.of(new TokenBuckets.Bucket("rl:user:{" + sub + "}", props.userPerMinute(), props.userBurst()));
            }
        } else {
            dims = List.of("ip");
            list = List.of(new TokenBuckets.Bucket("rl:ip:" + hash(RequestMeta.clientIp(request)), props.ipPerMinute(), props.ipBurst()));
        }
        TokenBuckets.Decision d = buckets.take(list);
        if (d == null) {
            failOpen(dims);
            chain.doFilter(request, response);
            return;
        }
        if (d.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        String dim = dims.get(d.denied());
        TokenBuckets.Bucket b = list.get(d.denied());
        meters.counter("nabd.rate_limit.exceeded", "dimension", dim).increment();
        if (tenant != null && buckets.firstInWindow("rl:evt:" + b.key(), Duration.ofMinutes(1))) {
            publish(tenant, dim, b);
        }
        long retryAfter = Math.max(1, (d.retryAfterMs() + 999) / 1000);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://api.nabd.health/errors/rate_limited");
        body.put("title", "Too many requests");
        body.put("status", 429);
        body.put("detail", "Rate limit reached; retry after " + retryAfter + " s.");
        body.put("dimension", dim);
        body.put("retryAfterSeconds", retryAfter);
        String traceId = MDC.get(RequestCorrelationFilter.MDC_KEY);
        body.put("traceId", traceId != null ? traceId : UUID.randomUUID().toString());
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/problem+json");
        response.getWriter().write(mapper.writeValueAsString(body));
    }

    private void failOpen(List<String> dims) {
        dims.forEach(dim -> meters.counter("nabd.rate_limit.fail_open", "dimension", dim).increment());
        long now = System.nanoTime();
        if (now - nextFailOpenLog >= 0) {
            nextFailOpenLog = now + FAIL_OPEN_LOG_EVERY_NANOS;
            log.error("rate limits failing open: Valkey unavailable (dimensions {}); OTP limits stay on Postgres", dims);
        }
    }

    private void publish(UUID tenant, String dim, TokenBuckets.Bucket b) {
        try {
            events.publish(tenant, EXCEEDED_EVENT, Map.of("tenantId", tenant.toString(), "dimension", dim,
                    "keyHash", hash(b.key()), "limit", b.perMinute(), "windowSeconds", 60));
        } catch (RuntimeException e) { // the 429 still goes out; the counter above already recorded the hit
            log.warn("could not publish {}: {}", EXCEEDED_EVENT, e.getClass().getSimpleName()); // log-phi-ok: exception class name
        }
    }

    private static UUID parse(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String hash(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

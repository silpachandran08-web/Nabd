package com.nabd.hms.shared.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PLT-008: the tenant bucket's size and rate. The tenant's plan (master.plans.api_requests_per_minute / api_burst, via
 * master.subscriptions) wins over app.rate-limit.tenant-*; each replica re-reads it at most every plan-refresh-seconds,
 * so a plan change applies within that time without a deploy. A failed read keeps the last known value (else defaults).
 * shortcut: one map entry per active tenant, never evicted; fine for thousands of tenants, bound it if that grows.
 */
@Component
class PlanRateLimits {

    private static final Logger log = LoggerFactory.getLogger(PlanRateLimits.class);

    record Limit(int perMinute, int burst) {
    }

    private record Cached(Limit limit, long fetchedAtMs) {
    }

    private final JdbcTemplate jdbc;
    private final RateLimitProperties props;
    private final Clock clock;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    PlanRateLimits(JdbcTemplate jdbc, RateLimitProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    Limit forTenant(UUID tenantId) {
        long now = clock.millis();
        Cached c = cache.get(tenantId);
        if (c != null && now - c.fetchedAtMs() < props.planRefreshSeconds() * 1000L) {
            return c.limit();
        }
        Limit limit;
        try {
            limit = jdbc.query("""
                    SELECT p.api_requests_per_minute, p.api_burst FROM master.subscriptions s
                    JOIN master.plans p ON p.id = s.plan_id WHERE s.tenant_id = ?""",
                    (rs, i) -> new Limit(
                            rs.getObject(1) == null ? props.tenantPerMinute() : rs.getInt(1),
                            rs.getObject(2) == null ? props.tenantBurst() : rs.getInt(2)),
                    tenantId).stream().findFirst().orElse(new Limit(props.tenantPerMinute(), props.tenantBurst()));
        } catch (RuntimeException e) {
            log.warn("plan rate limit read failed for tenant {}: {}", tenantId, e.getClass().getSimpleName());
            limit = c != null ? c.limit() : new Limit(props.tenantPerMinute(), props.tenantBurst());
        }
        cache.put(tenantId, new Cached(limit, now));
        return limit;
    }
}

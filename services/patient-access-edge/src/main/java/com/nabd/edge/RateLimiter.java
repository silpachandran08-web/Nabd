package com.nabd.edge;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Fixed one-minute windows in Valkey. Keys carry only hashes (phone: the contact hash; IP: sha-256 prefix), never raw values.
 * Fails open when Valkey is down (counted in edge_rate_limit_fail_open_total and alerted), same stance as Core.
 */
@Component
public class RateLimiter {

    /** ALLOW within the limit; NOTIFY exactly once per window on the first excess (the caller says "Please wait"); DROP after. */
    public enum Decision { ALLOW, NOTIFY, DROP }

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private final StringRedisTemplate valkey;
    private final EdgeProperties props;
    private final Clock clock;
    private final MeterRegistry meters;

    RateLimiter(StringRedisTemplate valkey, EdgeProperties props, Clock clock, MeterRegistry meters) {
        this.valkey = valkey;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
    }

    public Decision phone(UUID tenantId, String contactHash) {
        return check("phone", "edge:rl:phone:{" + tenantId + "}:" + contactHash, props.phonePerMin());
    }

    public Decision ip(String ipHash) {
        return check("ip", "edge:rl:ip:" + ipHash, props.ipPerMin());
    }

    private Decision check(String dimension, String key, int limit) {
        try {
            String k = key + ":" + clock.millis() / 60_000;
            Long n = valkey.opsForValue().increment(k);
            valkey.expire(k, Duration.ofSeconds(90));
            if (n == null || n <= limit) {
                return Decision.ALLOW;
            }
            meters.counter("edge_rate_limited_total", "dimension", dimension).increment();
            return n == limit + 1 ? Decision.NOTIFY : Decision.DROP;
        } catch (RuntimeException e) {
            meters.counter("edge_rate_limit_fail_open_total", "dimension", dimension).increment();
            log.warn("rate limit check failed open, dimension={}", dimension);
            return Decision.ALLOW;
        }
    }
}

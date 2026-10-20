package com.nabd.hms.shared.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * PLT-008: token buckets in Valkey, shared by every replica. One Lua call checks all the buckets of a request (tenant +
 * user, or ip) atomically on the server and only takes a token from each when every bucket has one, so concurrent
 * replicas cannot overspend. Time is the Valkey server's (TIME), never the replica's clock.
 * Returns null when Valkey is not configured or fails: the caller decides fail-open / fail-closed.
 */
@Component
public class TokenBuckets {

    private static final Logger log = LoggerFactory.getLogger(TokenBuckets.class);
    private static final long SKIP_NANOS_AFTER_FAILURE = 2_000_000_000L; // after an error skip Valkey briefly, so a dead server costs no connect timeout per request

    // KEYS[i] has ARGV[2i-1] tokens per minute and bucket size ARGV[2i]. Returns {index of the first empty bucket or 0, retry ms}.
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local tok, deny, retry = {}, 0, 0
            for i = 1, #KEYS do
              local rate, cap = tonumber(ARGV[2*i-1]), tonumber(ARGV[2*i])
              local h = redis.call('HMGET', KEYS[i], 't', 'ts')
              local v, ts = tonumber(h[1]), tonumber(h[2])
              if v == nil or ts == nil then v = cap; ts = now end
              v = math.min(cap, v + math.max(0, now - ts) * rate / 60000)
              tok[i] = v
              if v < 1 then
                local w = math.ceil((1 - v) * 60000 / rate)
                if deny == 0 then deny = i end
                if w > retry then retry = w end
              end
            end
            for i = 1, #KEYS do
              local rate, cap = tonumber(ARGV[2*i-1]), tonumber(ARGV[2*i])
              local v = tok[i]
              if deny == 0 then v = v - 1 end
              redis.call('HSET', KEYS[i], 't', tostring(v), 'ts', tostring(now))
              redis.call('PEXPIRE', KEYS[i], math.ceil(cap * 60000 / rate) + 1000)
            end
            return {deny, retry}
            """, List.class);

    public record Bucket(String key, int perMinute, int burst) {
    }

    /** denied = index (0-based) of the bucket that was empty, or -1 when allowed. */
    public record Decision(int denied, long retryAfterMs) {
        public boolean allowed() {
            return denied < 0;
        }
    }

    private final ObjectProvider<StringRedisTemplate> valkey;
    private volatile long skipUntil = System.nanoTime();

    TokenBuckets(ObjectProvider<StringRedisTemplate> valkey) {
        this.valkey = valkey;
    }

    public boolean configured() {
        return valkey.getIfAvailable() != null;
    }

    public Decision take(List<Bucket> buckets) {
        StringRedisTemplate t = valkey.getIfAvailable();
        if (t == null || System.nanoTime() - skipUntil < 0) {
            return null;
        }
        List<String> keys = new ArrayList<>();
        List<String> args = new ArrayList<>();
        for (Bucket b : buckets) {
            keys.add(b.key());
            args.add(Integer.toString(b.perMinute()));
            args.add(Integer.toString(b.burst()));
        }
        try {
            List<?> r = t.execute(SCRIPT, keys, args.toArray());
            int deny = ((Number) r.get(0)).intValue();
            return new Decision(deny - 1, ((Number) r.get(1)).longValue());
        } catch (RuntimeException e) {
            skipUntil = System.nanoTime() + SKIP_NANOS_AFTER_FAILURE;
            log.debug("valkey token bucket call failed: {}", e.toString());
            return null;
        }
    }

    /** True the first time per window for this key (Valkey SET NX EX), so a flood publishes one event, not one per request. */
    boolean firstInWindow(String key, Duration window) {
        StringRedisTemplate t = valkey.getIfAvailable();
        try {
            return t != null && Boolean.TRUE.equals(t.opsForValue().setIfAbsent(key, "1", window));
        } catch (RuntimeException e) {
            return false;
        }
    }
}

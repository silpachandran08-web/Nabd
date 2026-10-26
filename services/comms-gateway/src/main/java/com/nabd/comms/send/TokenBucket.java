package com.nabd.comms.send;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * COM-020: per-sender token bucket in Valkey, shared by every replica. Key {@code comms:{<sender>}:bucket} (a sender is one phone number, so
 * a tenant's own sender never shares a bucket with another tenant). Capacity = one second of throughput (the sender's mps, 80 for the
 * Cloud API default, set by the caller from the sender's tier). Time comes from the caller's clock, so tests drive it deterministically.
 * {@link #reduce} halves the refill rate until a deadline (Meta 130429); the bucket is emptied so the next send waits.
 */
public class TokenBucket {

    static final double REDUCED_FACTOR = 0.5;

    // KEYS[1] bucket; ARGV: now_ms, rate_per_s. Returns 1 if a token was taken.
    private static final DefaultRedisScript<Long> TAKE = new DefaultRedisScript<>("""
            local b = redis.call('HMGET', KEYS[1], 't', 'ts', 'until')
            local now, rate = tonumber(ARGV[1]), tonumber(ARGV[2])
            local cap = rate
            if tonumber(b[3] or 0) > now then rate = rate * %s end
            local t, ts = tonumber(b[1]), tonumber(b[2])
            if t == nil then t = cap; ts = now end
            t = math.min(cap, t + math.max(0, now - ts) / 1000 * rate)
            local ok = 0
            if t >= 1 then t = t - 1; ok = 1 end
            redis.call('HSET', KEYS[1], 't', tostring(t), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], 3600000)
            return ok
            """.formatted(REDUCED_FACTOR), Long.class);

    private static final DefaultRedisScript<Long> REDUCE = new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1], 't', '0', 'ts', ARGV[1], 'until', ARGV[2])
            redis.call('PEXPIRE', KEYS[1], 3600000)
            return 1
            """, Long.class);

    private final StringRedisTemplate valkey;

    public TokenBucket(StringRedisTemplate valkey) {
        this.valkey = valkey;
    }

    static String key(UUID sender) {
        return "comms:{" + sender + "}:bucket";
    }

    public boolean tryAcquire(UUID sender, double perSecond, Instant now) {
        return Long.valueOf(1).equals(valkey.execute(TAKE, List.of(key(sender)), Long.toString(now.toEpochMilli()), Double.toString(perSecond)));
    }

    public void reduce(UUID sender, Duration forHowLong, Instant now) {
        valkey.execute(REDUCE, List.of(key(sender)), Long.toString(now.toEpochMilli()), Long.toString(now.plus(forHowLong).toEpochMilli()));
    }
}

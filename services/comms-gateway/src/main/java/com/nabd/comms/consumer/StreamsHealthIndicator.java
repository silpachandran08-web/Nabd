package com.nabd.comms.consumer;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** COM-007.1: readiness includes the event streams: a pod that cannot reach Valkey takes no traffic and shows in the board. */
@Component("streams")
class StreamsHealthIndicator implements HealthIndicator {
    private final StringRedisTemplate valkey;

    StreamsHealthIndicator(StringRedisTemplate valkey) {
        this.valkey = valkey;
    }

    @Override
    public Health health() {
        try {
            String pong = valkey.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping());
            return "PONG".equals(pong) ? Health.up().build() : Health.down().build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("error", e.getClass().getSimpleName()).build();
        }
    }
}

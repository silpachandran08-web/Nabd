package com.nabd.hms.shared.ops;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** OPS-025: the "outbox" contributor of the readiness group. */
@Component("outbox")
public class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxLag lag;

    OutboxHealthIndicator(OutboxLag lag) {
        this.lag = lag;
    }

    @Override
    public Health health() {
        long age = lag.ageSeconds(Instant.now());
        return (age > OutboxLag.MAX_AGE_SECONDS ? Health.down() : Health.up()).withDetail("lastPollAgeSeconds", age).build();
    }
}

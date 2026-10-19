package com.nabd.hms.shared.ops;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OPS-025: outbox liveness as seen by this pod. OutboxDispatcher reports every finished poll cycle here; the
 * readiness probe goes DOWN when no cycle has finished for MAX_AGE_SECONDS (dispatcher dead, or a handler stuck).
 * shortcut: this is dispatcher staleness, not the true oldest-pending row age. outbox_events is RLS-forced and
 * no cross-tenant read function exists for nabd_app; upgrade with a SECURITY DEFINER oldest-pending-age function
 * (needs a migration number) and read it in OutboxHealthIndicator.
 * Meters carry no labels, so there is nothing tenant- or patient-specific to leak.
 */
@Component
public class OutboxLag {

    public static final long MAX_AGE_SECONDS = 120;

    private final AtomicReference<Instant> lastPoll = new AtomicReference<>(Instant.now());
    private final Counter claimed;
    private final Counter failed;

    public OutboxLag(MeterRegistry registry) {
        claimed = registry.counter("outbox.events.claimed");
        failed = registry.counter("outbox.events.failed");
        Gauge.builder("outbox.last.poll.age", this, l -> l.ageSeconds(Instant.now())).baseUnit("seconds").register(registry);
    }

    public void polled(int claimedCount, int failedCount) {
        claimed.increment(claimedCount);
        failed.increment(failedCount);
        lastPoll.set(Instant.now());
    }

    /** For tests that need a stale dispatcher. */
    public void polledAt(Instant when) {
        lastPoll.set(when);
    }

    public long ageSeconds(Instant now) {
        return Math.max(0, now.getEpochSecond() - lastPoll.get().getEpochSecond());
    }
}

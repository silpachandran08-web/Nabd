package com.nabd.comms.schedule;

import com.nabd.comms.events.StreamEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * COM-023: every tick (15 s) moves due scheduled sends into the lanes (status queued, oldest first) or settles them:
 * past notAfter -> expired, claim check says gone (410) -> cancelled. A send is never queued after its notAfter. The leader lock
 * keeps one instance ticking; the status compare-and-set keeps a double run harmless. Core unreachable: the row stays scheduled
 * and is retried, and expires once its window has passed. Only runs when the claim check is configured (comms.core.base-url).
 */
@Component
@ConditionalOnProperty("comms.core.base-url")
public class SendScheduler {

    public record Stats(int queued, int cancelled, int expired, int retried) {}

    private static final Logger log = LoggerFactory.getLogger(SendScheduler.class);
    private static final int RECONCILE_EVERY = 20; // ticks (5 min): pick up rows another path scheduled without the index
    private static final int MAX_CONSECUTIVE_CHECK_FAILURES = 3;

    private final ScheduledSendStore store;
    private final DueIndex index;
    private final ClaimCheck claim;
    private final StreamEventPublisher events;
    private final MeterRegistry meters;
    private final Clock clock;
    private final int batch;
    private long ticks;

    public SendScheduler(ScheduledSendStore store, DueIndex index, ClaimCheck claim, StreamEventPublisher events, MeterRegistry meters, Clock clock,
                  @Value("${comms.scheduler.batch:500}") int batch) {
        this.store = store;
        this.index = index;
        this.claim = claim;
        this.events = events;
        this.meters = meters;
        this.clock = clock;
        this.batch = batch;
    }

    @Scheduled(fixedDelayString = "${comms.scheduler.tick-ms:15000}")
    @SchedulerLock(name = "comms-send-scheduler", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    public void scheduledTick() {
        try {
            tick();
        } catch (RuntimeException e) { // DB or Valkey down: the next tick retries
            log.warn("send scheduler tick failed, retrying next tick");
        }
    }

    public Stats tick() {
        Instant now = clock.instant();
        if (ticks++ % RECONCILE_EVERY == 0) {
            index.rebuild(store.scheduled(now.plus(Duration.ofDays(3650)), 100_000));
        }
        List<DueIndex.Entry> due;
        try {
            due = index.due(now, batch);
        } catch (RuntimeException e) { // Valkey down: the DB index serves the same list
            due = store.scheduled(now, batch);
        }
        int queued = 0, cancelled = 0, expired = 0, retried = 0, failures = 0;
        for (DueIndex.Entry e : due) {
            String outcome = handle(e);
            switch (outcome) {
                case "queued" -> queued++;
                case "cancelled" -> cancelled++;
                case "expired" -> expired++;
                case "retry" -> retried++;
                default -> { }
            }
            failures = "retry".equals(outcome) ? failures + 1 : 0;
            if (failures >= MAX_CONSECUTIVE_CHECK_FAILURES) {
                break; // Core is down: stop hammering it, the rest stay due and keep their order
            }
        }
        return new Stats(queued, cancelled, expired, retried);
    }

    private String handle(DueIndex.Entry e) {
        var row = store.find(e.tenantId(), e.messageId());
        if (row.isEmpty() || !"scheduled".equals(row.get().status())) {
            index.remove(e.tenantId(), e.messageId());
            return "skipped";
        }
        Row r = new Row(row.get());
        if (r.late(clock.instant())) {
            return settle(e, r, "expired");
        }
        if (r.requestId() != null) {
            try {
                if (claim.check(e.tenantId(), r.requestId()) == ClaimCheck.Verdict.GONE) {
                    return settle(e, r, "cancelled");
                }
            } catch (RuntimeException ex) {
                meters.counter("comms_scheduled_total", "outcome", "claim_error").increment();
                // stays in the index with its original due time: retried next tick, still ahead of newer sends
                return "retry";
            }
        }
        return settle(e, r, r.late(clock.instant()) ? "expired" : "queued"); // the claim check may have taken long
    }

    private String settle(DueIndex.Entry e, Row r, String to) {
        Instant at = clock.instant();
        boolean won = store.settle(e.tenantId(), e.messageId(), to, at, () -> {
            if (!"queued".equals(to)) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("tenantId", e.tenantId().toString());
                d.put("messageId", e.messageId().toString());
                if (r.requestId() != null) {
                    d.put("requestId", r.requestId().toString());
                }
                d.put("channel", "whatsapp");
                d.put("status", to);
                d.put("at", at.toString());
                d.put("version", at.toEpochMilli());
                events.publish(e.messageId() + ":" + to, "comms.message.status_changed.v1", e.tenantId(), d);
            }
        });
        if (won) {
            meters.counter("comms_scheduled_total", "outcome", to).increment(); // SLO: expired / total (rules file)
        }
        return won ? to : "skipped";
    }

    private record Row(UUID requestId, Instant notAfter) {
        Row(ScheduledSendStore.Row r) {
            this(r.requestId(), r.notAfter());
        }

        boolean late(Instant now) {
            return notAfter != null && now.isAfter(notAfter);
        }
    }
}

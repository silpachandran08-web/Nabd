package com.nabd.comms.send;

import com.nabd.comms.provider.Provider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * COM-020 / COM-010: drives one sender. {@link #tick} sends while the bucket has tokens: lane by weighted fair dequeue, only the lanes the
 * throttle allows (COM-021). 429 / 130429 shrinks the bucket for 60 s and puts the message back without counting an attempt; a transient
 * failure retries with backoff up to {@code maxAttempts}, then the message is dead-lettered (visible, never silently dropped); a permanent
 * rejection is dead-lettered at once. The caller owns the clock, so tests run hours of traffic in milliseconds.
 */
public class SenderLoop {

    /** Where outcomes go; production = {@link JdbcOutcomeStore} (outbound_messages). */
    public interface Outcomes {
        void sent(LaneScheduler.Item item, String providerMessageId, Instant at);

        void deadLettered(LaneScheduler.Item item, String errorCode, Instant at);
    }

    static final Duration RATE_LIMIT_PAUSE = Duration.ofSeconds(60);
    /** After this many failures in a row the provider is treated as down: the loop pauses and further failures cost no attempt. */
    static final int CIRCUIT_AFTER = 5;
    static final Duration CIRCUIT_PAUSE = Duration.ofSeconds(30);

    private final UUID senderId;
    private final Function<LaneScheduler.Item, Provider.OutboundRequest> requestOf;
    private final double perSecond;
    private final int maxAttempts;
    private final LaneScheduler scheduler;
    private final TokenBucket bucket;
    private final Provider provider;
    private final Outcomes outcomes;
    private final Function<UUID, Set<Lane>> allowedLanes;
    private final Clock clock;
    private final MeterRegistry metrics;
    private int consecutiveTransient;
    private Instant pausedUntil = Instant.MIN;

    public SenderLoop(UUID senderId, Function<LaneScheduler.Item, Provider.OutboundRequest> requestOf, double perSecond, int maxAttempts, LaneScheduler scheduler, TokenBucket bucket,
                      Provider provider, Outcomes outcomes, Function<UUID, Set<Lane>> allowedLanes, Clock clock, MeterRegistry metrics) {
        this.senderId = senderId;
        this.requestOf = requestOf; // the caller decrypts the recipient and picks the template: PHI never enters the scheduler
        this.perSecond = perSecond;
        this.maxAttempts = maxAttempts;
        this.scheduler = scheduler;
        this.bucket = bucket;
        this.provider = provider;
        this.outcomes = outcomes;
        this.allowedLanes = allowedLanes;
        this.clock = clock;
        this.metrics = metrics;
        for (Lane l : Lane.values()) {
            metrics.gauge("comms.lane.depth", io.micrometer.core.instrument.Tags.of("lane", l.name(), "sender", senderId.toString()), scheduler, s -> s.depth(l));
        }
    }

    /** @return messages sent in this tick */
    public int tick() {
        int sent = 0;
        if (clock.instant().isBefore(pausedUntil)) {
            return 0;
        }
        Set<Lane> allowed = allowedLanes.apply(senderId);
        while (true) {
            Instant now = clock.instant();
            if (scheduler.depth() == 0 || !bucket.tryAcquire(senderId, perSecond, now)) {
                return sent;
            }
            var next = scheduler.poll(now, allowed);
            if (next.isEmpty()) {
                return sent; // token is not refunded: the bucket refills faster than idle lanes matter
            }
            LaneScheduler.Item it = next.get();
            var result = provider.send(requestOf.apply(it));
            switch (result) {
                case Provider.Accepted a -> {
                    consecutiveTransient = 0;
                    sent++;
                    outcomes.sent(it, a.providerMessageId(), now);
                    Timer.builder("comms.lane.wait").tags("lane", it.lane().name(), "sender", senderId.toString()).publishPercentiles(0.5, 0.95)
                            .register(metrics).record(Duration.between(it.enqueuedAt(), now));
                    count("sent");
                }
                case Provider.RateLimited r -> {
                    bucket.reduce(senderId, RATE_LIMIT_PAUSE, now);
                    scheduler.requeue(it, now, false);
                    count("rate_limited");
                    return sent;
                }
                case Provider.Transient t -> {
                    if (++consecutiveTransient > CIRCUIT_AFTER) {
                        scheduler.requeue(it, now, false);
                        pausedUntil = now.plus(CIRCUIT_PAUSE);
                        count("circuit_open");
                        return sent;
                    }
                    if (it.attempts() + 1 >= maxAttempts) {
                        outcomes.deadLettered(it, t.code(), now);
                        count("dead_letter");
                    } else {
                        scheduler.requeue(it, now.plus(backoff(it.attempts())), true);
                        count("retry");
                    }
                }
                case Provider.Rejected r -> {
                    outcomes.deadLettered(it, r.code(), now);
                    count("dead_letter");
                }
            }
        }
    }

    /** 5 s, 10 s, 20 s ... capped at 5 min. */
    static Duration backoff(int attempts) {
        return Duration.ofSeconds(Math.min(300, 5L << Math.min(attempts, 6)));
    }

    private void count(String outcome) {
        metrics.counter("comms.send", "outcome", outcome).increment();
    }
}

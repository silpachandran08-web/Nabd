package com.nabd.comms.send;

import com.nabd.comms.provider.Provider;
import com.nabd.comms.testkit.ProviderStub;
import com.nabd.comms.testkit.ProviderStub.Mode;
import com.nabd.comms.testkit.TestClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COM-010 chaos modes and COM-020 lanes/buckets. The sender loop runs on a manual clock (1 s ticks) against the fault-injecting
 * {@link ProviderStub}; the bucket is the real Valkey-backed one (embedded Redis 6.2), so hours of traffic run in seconds.
 */
class SendSimulationTest {

    static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    static LettuceConnectionFactory cf;
    static StringRedisTemplate valkey;

    @BeforeAll
    static void valkey() {
        cf = new LettuceConnectionFactory("127.0.0.1", com.nabd.comms.testkit.Infra.redisPort());
        cf.afterPropertiesSet();
        valkey = new StringRedisTemplate(cf);
        valkey.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        cf.destroy();
    }

    /** One sender, a stub, a scheduler and a loop on a manual clock. */
    static class Sim implements SenderLoop.Outcomes {
        final UUID sender = UUID.randomUUID();
        final TestClock clock = new TestClock(T0);
        final ProviderStub stub = new ProviderStub();
        final LaneScheduler sch = new LaneScheduler();
        final Map<UUID, Instant> sentAt = new HashMap<>();
        final Map<Lane, List<Long>> waits = new EnumMap<>(Lane.class);
        final Map<UUID, String> dead = new HashMap<>();
        final Map<UUID, LaneScheduler.Item> sentItems = new HashMap<>();
        final Set<UUID> badRecipients = new HashSet<>();
        Function<UUID, Set<Lane>> gate = id -> Set.of(Lane.values());
        SenderLoop loop;

        Sim(int maxAttempts) {
            loop = new SenderLoop(sender, it -> new Provider.OutboundRequest(it.messageId(), "pn", badRecipients.contains(it.messageId()) ? "+bad" : "+15550000", "tpl"),
                    80, maxAttempts, sch, new TokenBucket(valkey), stub, this, id -> gate.apply(id), clock, new SimpleMeterRegistry());
        }

        public void sent(LaneScheduler.Item it, String wamid, Instant at) {
            sentAt.put(it.messageId(), at);
            sentItems.put(it.messageId(), it);
            waits.computeIfAbsent(it.lane(), l -> new ArrayList<>()).add(Duration.between(it.enqueuedAt(), at).toSeconds());
        }

        public void deadLettered(LaneScheduler.Item it, String code, Instant at) {
            dead.put(it.messageId(), code);
        }

        UUID add(Lane l, UUID tenant) {
            UUID id = UUID.randomUUID();
            sch.add(new LaneScheduler.Item(id, tenant, l, clock.instant(), clock.instant(), 0));
            return id;
        }

        void step() {
            loop.tick();
            clock.advance(Duration.ofSeconds(1));
        }

        void run(int seconds) {
            for (int i = 0; i < seconds; i++) step();
        }

        long p(Lane l, double q) {
            List<Long> w = waits.get(l).stream().sorted().toList();
            return w.get((int) Math.min(w.size() - 1, Math.ceil(q * w.size()) - 1));
        }
    }

    // ---- COM-010 acceptance 1: 429 storm, 5,000 queued ----

    @Test
    void storm429With5000QueuedBacksOffAndLosesNothingAndDuplicatesNothing() {
        var sim = new Sim(5);
        UUID[] tenants = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        for (int i = 0; i < 5000; i++) sim.add(Lane.BULK, tenants[i % 3]);
        sim.stub.mode = Mode.STORM_429;
        sim.run(120);
        assertThat(sim.stub.acceptedMessages()).as("nothing gets through a storm").isZero();
        assertThat(sim.stub.calls.get()).as("backed off: about one probe per second, not 80").isLessThanOrEqualTo(125);
        sim.stub.mode = Mode.NORMAL;
        sim.run(400);
        assertThat(sim.sentAt).as("every message sent").hasSize(5000);
        assertThat(sim.dead).as("dead letters").isEmpty();
        assertThat(sim.stub.duplicates()).as("duplicates counted by the provider ground truth").isZero();
        assertThat(sim.stub.acceptedMessages()).isEqualTo(5000);
        assertThat(sim.sch.depth()).isZero();
    }

    // ---- chaos matrix (doc05 4.9) ----

    @Test
    void providerDownForThirtyMinutesQueuesEverythingAndDrainsAfterRecovery() {
        var sim = new Sim(5);
        for (int i = 0; i < 200; i++) sim.add(Lane.REMINDER, UUID.randomUUID());
        sim.stub.mode = Mode.DOWN;
        sim.run(1800);
        assertThat(sim.stub.acceptedMessages()).isZero();
        assertThat(sim.dead).as("an outage is not a per-message failure").isEmpty();
        assertThat(sim.stub.calls.get()).as("circuit pauses the loop").isLessThan(300);
        sim.stub.mode = Mode.NORMAL;
        sim.run(120);
        assertThat(sim.sentAt).hasSize(200);
        assertThat(sim.stub.duplicates()).isZero();
    }

    @Test
    void partial5xxRetriesUntilEverythingIsSentWithoutDuplicates() {
        var sim = new Sim(10);
        for (int i = 0; i < 1000; i++) sim.add(Lane.TRANSACTIONAL, UUID.randomUUID());
        sim.stub.mode = Mode.PARTIAL_5XX;
        sim.run(900);
        assertThat(sim.sentAt).hasSize(1000);
        assertThat(sim.dead).isEmpty();
        assertThat(sim.stub.duplicates()).isZero();
    }

    @Test
    void timeoutAfterAcceptanceLosesNothingAndEveryDuplicateIsCounted() {
        var sim = new Sim(10);
        for (int i = 0; i < 100; i++) sim.add(Lane.REMINDER, UUID.randomUUID());
        sim.stub.mode = Mode.LATENCY;
        sim.run(3);
        int acceptedButTimedOut = sim.stub.acceptedMessages();
        sim.stub.mode = Mode.NORMAL;
        sim.run(120);
        assertThat(sim.sentAt).hasSize(100);
        assertThat(acceptedButTimedOut).isPositive();
        assertThat(sim.stub.duplicates()).as("known limit: the provider offers no idempotency key, so an ambiguous timeout can double-send")
                .isEqualTo(acceptedButTimedOut);
    }

    @Test
    void permanentRejectionIsDeadLetteredOnceAndVisible() {
        var sim = new Sim(5);
        UUID bad = sim.add(Lane.REMINDER, UUID.randomUUID());
        sim.badRecipients.add(bad);
        UUID good = sim.add(Lane.REMINDER, UUID.randomUUID());
        sim.run(10);
        assertThat(sim.dead).containsEntry(bad, "131026").hasSize(1);
        assertThat(sim.sentAt).containsKey(good);
        assertThat(sim.stub.calls.get()).isEqualTo(2);
    }

    @Test
    void messagesThatKeepFailingEndAsDeadLettersNotSilence() {
        var sim = new Sim(3);
        for (int i = 0; i < 4; i++) sim.add(Lane.REMINDER, UUID.randomUUID());
        sim.stub.mode = Mode.DOWN;
        sim.run(600);
        assertThat(sim.sentAt).isEmpty();
        assertThat(sim.sch.depth() + sim.dead.size()).as("lost = 0").isEqualTo(4);
    }

    // ---- COM-020 ----

    @Test
    void remindersAndOtpsStayFastDuringAFiveTimesBulkBurst() {
        var sim = new Sim(5);
        UUID clinic = UUID.randomUUID(), recallClinic = UUID.randomUUID();
        List<UUID> reminders = new ArrayList<>();
        for (int s = 0; s < 300; s++) {
            if (s == 10) for (int i = 0; i < 4000; i++) sim.add(Lane.BULK, recallClinic); // the burst: 5x everything else together
            if (s < 120) {
                for (int i = 0; i < 8; i++) reminders.add(sim.add(Lane.REMINDER, clinic));
                for (int i = 0; i < 8; i++) sim.add(Lane.BULK, clinic);
                sim.add(Lane.OTP, clinic);
            }
            sim.step();
        }
        sim.run(120);
        assertThat(sim.p(Lane.REMINDER, 0.95)).as("reminder p95 wait (s)").isLessThan(30);
        long inWindow = reminders.stream()
                .filter(id -> Duration.between(sim.sentItems.get(id).enqueuedAt(), sim.sentAt.get(id)).toMinutes() < 10).count();
        assertThat(inWindow / (double) reminders.size()).as("reminders sent inside a 10 minute window").isGreaterThanOrEqualTo(0.99);
        assertThat(sim.p(Lane.OTP, 0.99)).as("OTP p99 wait (s)").isLessThan(3);
        sim.run(120);
        assertThat(sim.sch.depth()).as("bulk was deferred, never dropped").isZero();
        assertThat(sim.dead).isEmpty();
    }

    @Test
    void aSecondTenantKeepsAtLeastThirtyPercentOfBulkWhileAnotherQueues10000() {
        var sim = new Sim(5);
        UUID big = UUID.randomUUID(), small = UUID.randomUUID();
        for (int i = 0; i < 10_000; i++) sim.add(Lane.BULK, big);
        for (int i = 0; i < 1_000; i++) sim.add(Lane.BULK, small);
        sim.run(20);
        long total = sim.sentItems.size();
        long smallSent = sim.sentItems.values().stream().filter(i -> i.tenantId().equals(small)).count();
        assertThat(total).isEqualTo(1600);
        assertThat(smallSent / (double) total).isGreaterThanOrEqualTo(0.30);
    }

    @Test
    void metaRateLimitShrinksTheBucketForSixtySecondsThenItRecovers() {
        UUID sender = UUID.randomUUID();
        var bucket = new TokenBucket(valkey);
        Instant t = T0;
        assertThat(bucket.tryAcquire(sender, 80, t)).isTrue();
        bucket.reduce(sender, Duration.ofSeconds(60), t);
        int got = 0;
        for (int sec = 1; sec <= 10; sec++) {
            for (int k = 0; k < 200; k++) if (bucket.tryAcquire(sender, 80, t.plusSeconds(sec))) got++;
        }
        assertThat(got).as("10 s at half rate").isBetween(390, 410);
        int after = 0;
        for (int k = 0; k < 200; k++) if (bucket.tryAcquire(sender, 80, t.plusSeconds(62))) after++;
        assertThat(after).as("full rate again after the 60 s").isGreaterThanOrEqualTo(79);
    }

    @Test
    void aPausedLaneKeepsItsMessagesAndSendsThemWhenTheThrottleLifts() {
        var sim = new Sim(5);
        UUID t = UUID.randomUUID();
        for (int i = 0; i < 50; i++) sim.add(Lane.BULK, t);
        for (int i = 0; i < 50; i++) sim.add(Lane.REMINDER, t);
        sim.gate = id -> Set.of(Lane.OTP, Lane.TRANSACTIONAL, Lane.REMINDER);
        sim.run(5);
        assertThat(sim.waits.get(Lane.REMINDER)).hasSize(50);
        assertThat(sim.waits.get(Lane.BULK)).isNull();
        assertThat(sim.sch.depth(Lane.BULK)).isEqualTo(50);
        sim.gate = id -> Set.of(Lane.values());
        sim.run(5);
        assertThat(sim.waits.get(Lane.BULK)).hasSize(50);
    }
}

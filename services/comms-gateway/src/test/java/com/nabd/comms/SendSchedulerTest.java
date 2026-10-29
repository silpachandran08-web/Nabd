package com.nabd.comms;

import com.nabd.comms.consumer.CommsConsumerProperties;
import com.nabd.comms.events.StreamEventPublisher;
import com.nabd.comms.schedule.ClaimCheck;
import com.nabd.comms.schedule.ScheduledSendStore;
import com.nabd.comms.schedule.SendScheduler;
import com.nabd.comms.schedule.ValkeyDueIndex;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-023 against a real Postgres (RLS as comms_app) and a Valkey stand-in; the clock is simulated, the provider side is the 'queued' status. */
class SendSchedulerTest {

    static final Instant T0 = Instant.parse("2026-11-02T03:30:00Z");
    static JdbcTemplate app, su;
    static TransactionTemplate tx;
    static StringRedisTemplate valkey;
    static StreamEventPublisher events;
    static ObjectMapper json = JsonMapper.builder().build();
    static final String REGION = "sch";

    @BeforeAll
    static void start() throws Exception {
        String db = "sched_" + UUID.randomUUID().toString().replace("-", "");
        TestInfra.freshComms(db);
        var ds = new DriverManagerDataSource(TestInfra.url(db), "comms_app", TestInfra.APP_PW);
        app = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        su = new JdbcTemplate(new DriverManagerDataSource(TestInfra.url(db), TestInfra.PG.getUsername(), TestInfra.PG.getPassword()));
        var cf = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", TestInfra.redisPort));
        cf.afterPropertiesSet();
        valkey = new StringRedisTemplate(cf);
    }

    static class SimClock extends Clock {
        volatile Instant now = T0;
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    /** Core's claim check: valid unless the request was cancelled; can fail like an outage. */
    static class Core implements ClaimCheck {
        final java.util.Set<UUID> cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet();
        volatile boolean down;
        volatile double flake;
        final Random rnd = new Random(7);
        final List<UUID> calls = new CopyOnWriteArrayList<>();
        public Verdict check(UUID tenantId, UUID requestId) {
            calls.add(requestId);
            if (down || (flake > 0 && rnd.nextDouble() < flake)) {
                throw new IllegalStateException("core unavailable");
            }
            return cancelled.contains(requestId) ? Verdict.GONE : Verdict.VALID;
        }
    }

    record Fixture(SimClock clock, Core core, ScheduledSendStore store, ValkeyDueIndex index, SendScheduler scheduler, SimpleMeterRegistry meters) {}

    static Fixture fixture() {
        var clock = new SimClock();
        var props = new CommsConsumerProperties(REGION, new CommsConsumerProperties.Consumer(true, 50, 5, 60000));
        var index = new ValkeyDueIndex(valkey, props);
        var store = new ScheduledSendStore(app, tx, index);
        var core = new Core();
        var meters = new SimpleMeterRegistry();
        return new Fixture(clock, core, store, index, scheduler(store, index, core, clock, meters, props), meters);
    }

    static SendScheduler scheduler(ScheduledSendStore store, ValkeyDueIndex index, ClaimCheck core, SimClock clock, SimpleMeterRegistry meters, CommsConsumerProperties props) {
        events = new StreamEventPublisher(valkey, props, json, clock);
        return new SendScheduler(store, index, core, events, meters, clock, 500);
    }

    static ScheduledSendStore.NewSend send(UUID tenant, UUID request, Instant notBefore, Instant notAfter) {
        return new ScheduledSendStore.NewSend(UUID.randomUUID(), tenant, request, null, new byte[] {1}, new byte[] {2}, "appt_reminder_24h", "en", "REMINDER", notBefore, notAfter);
    }

    static List<String> statusEvents(UUID tenant, String status) {
        var recs = valkey.opsForStream().range("ev:" + REGION + ":comms", org.springframework.data.domain.Range.unbounded());
        List<String> out = new ArrayList<>();
        for (var r : recs == null ? List.<org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object>>of() : recs) {
            JsonNode n = json.readTree(String.valueOf(r.getValue().get("ce")));
            if (tenant.toString().equals(n.path("tenantid").asString()) && status.equals(n.path("data").path("status").asString())) {
                out.add(n.path("data").path("messageId").asString());
            }
        }
        return out;
    }

    Map<String, Object> row(UUID tenant, UUID id) {
        return su.queryForMap("SELECT status, updated_at, not_after FROM outbound_messages WHERE id = ? AND tenant_id = ?", id, tenant);
    }

    /** One clinic day of reminders: 1,000 sends with 10-minute windows, ticking every 15 s until the last window is over. */
    Map<UUID, ScheduledSendStore.NewSend> clinicDay(Fixture f, UUID tenant, int n) {
        Random r = new Random(11);
        Map<UUID, ScheduledSendStore.NewSend> all = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            Instant nb = T0.plusSeconds(r.nextInt(12 * 3600));
            var s = send(tenant, UUID.randomUUID(), nb, nb.plus(Duration.ofMinutes(10)));
            f.store().schedule(s, T0);
            all.put(s.id(), s);
        }
        return all;
    }

    void runDay(Fixture f, Duration length, java.util.function.Consumer<Instant> perTick) {
        for (Instant t = T0; !t.isAfter(T0.plus(length)); t = t.plusSeconds(15)) {
            f.clock().now = t;
            perTick.accept(t);
            f.scheduler().tick();
        }
    }

    @Test
    void clinicDayUnderTransientCoreFaultsSendsInsideTheWindowAndNeverAfter() {
        var f = fixture();
        UUID tenant = UUID.randomUUID();
        var all = clinicDay(f, tenant, 1000);
        f.core().flake = 0.05; // 5% of claim checks fail and are retried 15 s later
        runDay(f, Duration.ofHours(12).plusMinutes(11), t -> { });
        int queued = 0, expired = 0;
        for (var s : all.values()) {
            var row = row(tenant, s.id());
            String st = (String) row.get("status");
            Instant at = ((java.sql.Timestamp) row.get("updated_at")).toInstant();
            if ("queued".equals(st)) {
                queued++;
                assertThat(at).as("never queued after notAfter").isBetween(s.notBefore(), s.notAfter());
            } else {
                assertThat(st).isEqualTo("expired");
                expired++;
            }
        }
        assertThat(queued + expired).isEqualTo(1000);
        assertThat(queued).as(">= 99% inside the window").isGreaterThanOrEqualTo(990);
        assertThat(statusEvents(tenant, "expired")).as("every expired send has its status event").hasSize(expired);
        assertThat(f.meters().counter("comms_scheduled_total", "outcome", "queued").count()).isEqualTo(queued);
    }

    @Test
    void aThirtyMinuteCoreOutageQueuesWhatIsStillInsideItsWindowOldestFirstAndExpiresTheRest() {
        var f = fixture();
        UUID tenant = UUID.randomUUID();
        var all = clinicDay(f, tenant, 300);
        Instant outageFrom = T0.plus(Duration.ofHours(5)), outageTo = outageFrom.plus(Duration.ofMinutes(30));
        List<UUID> afterRecovery = new ArrayList<>();
        runDay(f, Duration.ofHours(12).plusMinutes(11), t -> {
            f.core().down = !t.isBefore(outageFrom) && t.isBefore(outageTo);
            if (t.equals(outageTo)) {
                f.core().calls.clear();
            }
        });
        var byRequest = new java.util.HashMap<UUID, ScheduledSendStore.NewSend>();
        all.values().forEach(s -> byRequest.put(s.requestId(), s));
        int expiredInOutage = 0, queuedFromBacklog = 0;
        for (var s : all.values()) {
            var row = row(tenant, s.id());
            Instant at = ((java.sql.Timestamp) row.get("updated_at")).toInstant();
            if ("queued".equals(row.get("status"))) {
                assertThat(at).isBetween(s.notBefore(), s.notAfter());
                if (s.notBefore().isBefore(outageTo) && !at.isBefore(outageTo)) {
                    queuedFromBacklog++;
                }
            } else if (s.notAfter().isAfter(outageFrom) && s.notBefore().isBefore(outageTo) && s.notAfter().isBefore(outageTo)) {
                assertThat(row.get("status")).isEqualTo("expired");
                expiredInOutage++;
            }
        }
        assertThat(expiredInOutage).as("windows that ended during the outage expired").isGreaterThan(0);
        assertThat(queuedFromBacklog).as("windows still open at recovery went out").isGreaterThan(0);
        assertThat(statusEvents(tenant, "expired")).isNotEmpty();
        // oldest first after recovery: the claim checks of the backlog follow not_before order
        List<Instant> order = f.core().calls.stream().map(byRequest::get).filter(java.util.Objects::nonNull)
                .map(ScheduledSendStore.NewSend::notBefore).filter(nb -> nb.isBefore(outageTo.plusSeconds(30))).toList();
        List<Instant> sorted = new ArrayList<>(order);
        Collections.sort(sorted);
        assertThat(order).isEqualTo(sorted);
    }

    @Test
    void anAppointmentCancelledOneMinuteBeforeTheReminderIsCancelledAndNeverQueued() {
        var f = fixture();
        UUID tenant = UUID.randomUUID(), request = UUID.randomUUID();
        Instant due = T0.plus(Duration.ofHours(1));
        var s = send(tenant, request, due, due.plus(Duration.ofMinutes(10)));
        f.store().schedule(s, T0);
        f.clock().now = due.minusSeconds(60);
        f.core().cancelled.add(request); // Core answers 410 from now on
        f.scheduler().tick();
        assertThat(row(tenant, s.id()).get("status")).as("not due yet").isEqualTo("scheduled");
        f.clock().now = due;
        var stats = f.scheduler().tick();
        assertThat(stats.cancelled()).isEqualTo(1);
        assertThat(row(tenant, s.id()).get("status")).isEqualTo("cancelled");
        assertThat(statusEvents(tenant, "cancelled")).containsExactly(s.id().toString());
        f.clock().now = due.plusSeconds(60);
        assertThat(f.scheduler().tick().queued()).isZero();
    }

    @Test
    void aSendNotYetDueAndOneWithoutAWindowBehave() {
        var f = fixture();
        UUID tenant = UUID.randomUUID();
        var later = send(tenant, UUID.randomUUID(), T0.plusSeconds(100), null); // no notAfter: never expires
        f.store().schedule(later, T0);
        f.clock().now = T0.plusSeconds(99);
        assertThat(f.scheduler().tick().queued()).isZero();
        f.clock().now = T0.plus(Duration.ofDays(3));
        assertThat(f.scheduler().tick().queued()).isEqualTo(1);
    }

    @Test
    void twoSchedulerInstancesDispatchEachDueSendOnce() throws Exception {
        var f = fixture();
        var props = new CommsConsumerProperties(REGION, new CommsConsumerProperties.Consumer(true, 50, 5, 60000));
        var second = scheduler(f.store(), f.index(), f.core(), f.clock(), new SimpleMeterRegistry(), props);
        UUID tenant = UUID.randomUUID();
        for (int i = 0; i < 100; i++) { // 100 sends still inside their window, 100 already past it
            f.store().schedule(send(tenant, UUID.randomUUID(), T0, T0.plus(Duration.ofHours(1))), T0);
            f.store().schedule(send(tenant, UUID.randomUUID(), T0, T0.plusSeconds(30)), T0);
        }
        f.clock().now = T0.plus(Duration.ofMinutes(5));
        AtomicInteger queued = new AtomicInteger(), expired = new AtomicInteger();
        var go = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (SendScheduler s : List.of(f.scheduler(), second)) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    var st = s.tick();
                    queued.addAndGet(st.queued());
                    expired.addAndGet(st.expired());
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            });
            t.start();
            ts.add(t);
        }
        go.countDown();
        for (Thread t : ts) t.join();
        assertThat(queued.get()).as("exactly one winner per row").isEqualTo(100);
        assertThat(expired.get()).isEqualTo(100);
        assertThat(statusEvents(tenant, "expired")).as("one event per expired send").hasSize(100).doesNotHaveDuplicates();
        assertThat(su.queryForObject("SELECT count(*) FROM outbound_messages WHERE tenant_id = ? AND status = 'scheduled'", Integer.class, tenant)).isZero();
    }

    @Test
    void lostValkeyIndexIsRebuiltFromTheDatabase() {
        var f = fixture();
        UUID tenant = UUID.randomUUID();
        var s = send(tenant, UUID.randomUUID(), T0, T0.plus(Duration.ofHours(1)));
        f.store().schedule(s, T0);
        valkey.delete("comms:sched:{" + REGION + "}"); // Valkey restarted empty
        f.clock().now = T0.plusSeconds(20);
        assertThat(f.scheduler().tick().queued()).isEqualTo(1); // first tick of a new scheduler reconciles from the DB
    }

    @Test
    void scheduledRowsAreTenantIsolated() {
        var f = fixture();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var s = send(a, UUID.randomUUID(), T0, null);
        f.store().schedule(s, T0);
        assertThat(f.store().find(b, s.id())).as("tenant B cannot read tenant A's send").isEmpty();
        assertThat(f.store().settle(b, s.id(), "cancelled", T0, () -> { })).as("nor settle it").isFalse();
        assertThat(app.queryForObject("SELECT count(*) FROM outbound_messages", Integer.class)).as("no scheduler flag, no tenant: nothing visible").isZero();
        assertThat(f.store().find(a, s.id())).isPresent();
        f.store().settle(a, s.id(), "cancelled", T0, () -> { }); // leave nothing scheduled for the other tests
    }
}

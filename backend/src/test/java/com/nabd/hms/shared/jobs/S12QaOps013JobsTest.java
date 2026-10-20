package com.nabd.hms.shared.jobs;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nabd.hms.auth.AuthPurgeJob;
import com.nabd.hms.common.OutboxPurgeJob;
import com.nabd.hms.messaging.WhatsAppMessagePurgeJob;
import com.nabd.hms.shared.BatchedPurge;
import com.nabd.hms.support.ApiTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;

import javax.sql.DataSource;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S12 QA-3, OPS-013 (independent): real job beans racing across two replicas, lock expiry takeover, 10k batching
 * through the real job with concurrent OPD writes, retention boundaries for all four tables, config override,
 * job metrics on success and on failure. */
class S12QaOps013JobsTest extends ApiTestBase {

    @Autowired LockProvider lockProvider;
    @Autowired DataSource dataSource;
    @Autowired Clock clock;
    @Autowired AuthPurgeJob authPurge;
    @Autowired WhatsAppMessagePurgeJob messagePurge;
    @Autowired OutboxPurgeJob outboxPurge;
    @Autowired MeterRegistry meters;
    @LocalManagementPort int mgmtPort;

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void capture() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(SkipLoggingLockProvider.class)).addAppender(logs);
        // release (never delete: ShedLock caches that a row exists and then only UPDATEs it)
        su.update("UPDATE shedlock SET lock_until = timezone('utc', now()) - interval '1 second' "
                + "WHERE name IN ('sessions-purge', 'login-attempts-purge', 'messages-purge', 'outbox-purge')");
        ((java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(lockProvider, "lastLogged")).clear(); // skip log is rate-limited per minute
    }

    @AfterEach
    void detach() {
        su.update("UPDATE shedlock SET lock_until = timezone('utc', now()) - interval '1 second' "
                + "WHERE name IN ('sessions-purge', 'login-attempts-purge', 'messages-purge', 'outbox-purge')"); // never leave a held lock behind
        ((Logger) LoggerFactory.getLogger(SkipLoggingLockProvider.class)).detachAppender(logs);
    }

    private LockProvider replica(String name) {
        return new SkipLoggingLockProvider(new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource)).usingDbTime().withLockedByValue(name).build()), clock);
    }

    private double runs(String job) {
        var t = meters.find("job.duration").tag("job", job).timer();
        return t == null ? 0 : t.count();
    }

    private double failures(String job) {
        var c = meters.find("job.failures").tag("job", job).counter();
        return c == null ? 0 : c.count();
    }

    /** n sessions expired `expiredDays` ago for one staff member; token_hash prefixed for counting. */
    private String seedExpiredSessions(int n, double expiredDays) {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "q3ops013-" + UUID.randomUUID() + "@example.test", null, false);
        String tag = "q3-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        su.update("""
                INSERT INTO sessions (id, tenant_id, staff_id, family_id, token_hash, created_at, last_seen_at, expires_at)
                SELECT gen_random_uuid(), ?, ?, gen_random_uuid(), ? || g, now() - interval '90 days', now() - interval '90 days',
                       now() - make_interval(secs => ?)
                FROM generate_series(1, ?) g""", t.id(), s.id(), tag, expiredDays * 86400, n);
        return tag;
    }

    private int countTag(String tag) {
        return su.queryForObject("SELECT count(*) FROM sessions WHERE token_hash LIKE ? || '%'", Integer.class, tag);
    }

    // ---------- AC1 ----------

    @Test
    void ac1_theRealPurgeBeanFiredOnTwoReplicasAtOnceRunsOnce_andTheOtherLogsASkip() throws Exception {
        String tag = seedExpiredSessions(12_000, 40); // > 1 batch, so the run lasts past the pause
        Object target = AopTestUtils.getTargetObject(authPurge);
        AtomicInteger replicaBRuns = new AtomicInteger();
        double before = runs("sessions-purge");
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> a = pool.submit(() -> { go.await(); authPurge.purgeSessions(); return null; });           // replica A: the scheduled bean
        Future<?> b = pool.submit(() -> { go.await();                                                       // replica B: same job, own locked_by
            new DefaultLockingTaskExecutor(replica("replica-b")).executeWithLock((Runnable) () -> {
                replicaBRuns.incrementAndGet();
                ((AuthPurgeJob) target).purgeSessions();
            }, new LockConfiguration(Instant.now(), "sessions-purge", Duration.ofHours(2), Duration.ZERO));
            return null; });
        go.countDown();
        a.get(60, TimeUnit.SECONDS);
        b.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        int executions = (int) (runs("sessions-purge") - before) + replicaBRuns.get();
        assertThat(executions).as("executions across the two replicas").isEqualTo(1);
        assertThat(su.queryForObject("SELECT count(*) FROM shedlock WHERE name = 'sessions-purge'", Integer.class)).isEqualTo(1);
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage)).contains("job sessions-purge skipped: locked by another replica");
        assertThat(countTag(tag)).isZero();
    }

    @Test
    void ac1_boundary_aCrashedReplicasLockBlocksUntilLockUntil_thenAnotherReplicaTakesOver() {
        String tag = seedExpiredSessions(3, 40);
        // replica B crashed mid-run: its row still holds the lock for 10 more minutes
        holdLock("sessions-purge", "replica-b-dead");
        double before = runs("sessions-purge");
        authPurge.purgeSessions();
        assertThat(runs("sessions-purge")).isEqualTo(before);
        assertThat(countTag(tag)).isEqualTo(3);
        // lockAtMostFor elapsed
        su.update("UPDATE shedlock SET lock_until = timezone('utc', now()) - interval '1 second' WHERE name = 'sessions-purge'");
        authPurge.purgeSessions();
        assertThat(runs("sessions-purge")).isEqualTo(before + 1);
        assertThat(countTag(tag)).isZero();
        assertThat(su.queryForObject("SELECT locked_by FROM shedlock WHERE name = 'sessions-purge'", String.class)).isNotEqualTo("replica-b-dead");
    }

    @Test
    void ac1_everyPurgeJobHoldsItsOwnLock_aHeldSessionsLockDoesNotBlockTheOthers() {
        long attempt = su.queryForObject("INSERT INTO login_attempts (email, ip_address, succeeded, attempted_at) "
                + "VALUES ('q3@example.test', '192.0.2.9', false, now() - interval '100 days') RETURNING id", Long.class);
        holdLock("sessions-purge", "other");
        authPurge.purgeLoginAttempts();
        assertThat(su.queryForObject("SELECT count(*) FROM login_attempts WHERE id = ?", Integer.class, attempt)).isZero();
    }

    // ---------- AC2 ----------

    @Test
    void ac2_retentionBoundary_30DaysAfterExpiry_exactlyAroundTheLine() {
        String old = seedExpiredSessions(1, 30 + 1.0 / 24);    // 30 d 1 h
        String young = seedExpiredSessions(1, 30 - 1.0 / 24);  // 29 d 23 h
        String live = seedExpiredSessions(1, -1);              // not expired yet
        authPurge.purgeSessions();
        assertThat(countTag(old)).isZero();
        assertThat(countTag(young)).isEqualTo(1);
        assertThat(countTag(live)).isEqualTo(1);
    }

    @Test
    void ac2_25kExpiredRowsGoIn10kBatches_andConcurrentOpdWritesOnSessionsNeverWaitASecond() throws Exception {
        new JdbcTemplate(dataSource).queryForObject("SELECT purge_sessions(30, 1000000)", Integer.class); // other tests' leftovers
        String tag = seedExpiredSessions(25_000, 45);
        String liveTag = seedExpiredSessions(1, -1);
        UUID live = su.queryForObject("SELECT id FROM sessions WHERE token_hash LIKE ? || '%'", UUID.class, liveTag);
        UUID doomed = su.queryForObject("SELECT id FROM sessions WHERE token_hash LIKE ? || '%' LIMIT 1", UUID.class, tag);

        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong worstMs = new AtomicLong();
        AtomicInteger writes = new AtomicInteger();
        Thread opd = new Thread(() -> {
            JdbcTemplate app = new JdbcTemplate(superuserDataSource());
            while (!stop.get()) {
                for (UUID id : List.of(live, doomed)) { // a refresh on a live session and a late revoke of an expired one
                    long t0 = System.nanoTime();
                    app.update("UPDATE sessions SET last_seen_at = now() WHERE id = ?", id);
                    worstMs.accumulateAndGet((System.nanoTime() - t0) / 1_000_000, Math::max);
                    writes.incrementAndGet();
                }
            }
        });
        // the real job's own loop, counting its batch calls (the bean's config: 10k, 200 ms pause)
        List<Integer> batches = new ArrayList<>();
        JdbcTemplate app = new JdbcTemplate(dataSource);
        opd.start();
        long t0 = System.nanoTime();
        int total = BatchedPurge.run(b -> {
            int n = app.queryForObject("SELECT purge_sessions(?, ?)", Integer.class, 30, b);
            batches.add(n);
            return n;
        }, 10_000, 200, 500);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        stop.set(true);
        opd.join();
        assertThat(batches.get(0)).isEqualTo(10_000);
        assertThat(batches.get(1)).isEqualTo(10_000);
        assertThat(batches).hasSizeBetween(3, 4).allMatch(n -> n <= 10_000);
        assertThat(total).isBetween(24_999, 25_000); // `doomed` may be skipped (SKIP LOCKED) while OPD holds it
        assertThat(ms).as("two 200 ms pauses between full batches").isGreaterThanOrEqualTo(400);
        assertThat(writes.get()).isPositive();
        assertThat(worstMs.get()).as("worst OPD write wait on sessions during the purge (ms)").isLessThan(1000);
        assertThat(countTag(liveTag)).isEqualTo(1);

        // and the bean itself is configured to exactly that batch / pause
        Object target = AopTestUtils.getTargetObject(authPurge);
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(target, "batch")).isEqualTo(10_000);
    }

    @Test
    void ac2_otherTables_outboxDone7dDead90dPendingKept_loginAttempts90d_messages24Months() {
        SeededTenant t = seedTenant();
        long done8 = outbox(t.id(), "done", 8), done6 = outbox(t.id(), "done", 6);
        long dead91 = outbox(t.id(), "dead", 91), dead89 = outbox(t.id(), "dead", 89), pending120 = outbox(t.id(), "pending", 120);
        long la91 = attempt(91), la89 = attempt(89);
        UUID m25 = message(t.id(), "25 months"), m23 = message(t.id(), "23 months");

        outboxPurge.scheduled();
        authPurge.purgeLoginAttempts();
        messagePurge.purge();

        assertThat(outboxLeft(done8, done6, dead91, dead89, pending120)).containsExactlyInAnyOrder(done6, dead89, pending120);
        assertThat(su.queryForList("SELECT id FROM login_attempts WHERE id IN (?, ?)", Long.class, la91, la89)).containsExactly(la89);
        assertThat(su.queryForList("SELECT id FROM whatsapp_messages WHERE id IN (?, ?)", UUID.class, m25, m23)).containsExactly(m23);
    }

    @Test
    void ac2_retentionIsConfiguration_aCellWith10DaySessionRetentionPurgesAt10Days() throws Exception {
        Constructor<AuthPurgeJob> c = AuthPurgeJob.class.getDeclaredConstructor(JdbcTemplate.class, int.class, int.class, int.class, long.class, int.class);
        c.setAccessible(true);
        AuthPurgeJob tenDays = c.newInstance(new JdbcTemplate(dataSource), 10, 90, 10_000, 0L, 500);
        String d11 = seedExpiredSessions(1, 11), d9 = seedExpiredSessions(1, 9);
        tenDays.purgeSessions();
        assertThat(countTag(d11)).isZero();
        assertThat(countTag(d9)).isEqualTo(1);
    }

    // ---------- AC4 ----------

    @Test
    void ac4_everyPurgeJobExportsTheThreeMetricsWithItsJobLabel() throws Exception {
        authPurge.purgeSessions();
        authPurge.purgeLoginAttempts();
        messagePurge.purge();
        outboxPurge.scheduled();
        String scrape = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + mgmtPort + "/actuator/prometheus"))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        for (String job : List.of("sessions-purge", "login-attempts-purge", "messages-purge", "outbox-purge")) {
            assertThat(scrape).as(job).contains("job_last_success_timestamp{job=\"" + job + "\"}", "job_duration_seconds_count{job=\"" + job + "\"}",
                    "job_failures_total{job=\"" + job + "\"}");
        }
        assertThat(scrape).doesNotContain("tenant_id=").doesNotContain("tenantId=");
    }

    @Test
    void ac4_aFailedRunIncrementsFailures_andLeavesLastSuccessUntouched() throws Exception {
        authPurge.purgeLoginAttempts();
        double last = meters.get("job.last.success.timestamp").tag("job", "login-attempts-purge").gauge().value();
        double failed = failures("login-attempts-purge");
        double runs = runs("login-attempts-purge");
        try (Connection c = superuserDataSource().getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("LOCK TABLE login_attempts IN ACCESS EXCLUSIVE MODE"); // the night's run hits a 1 s lock timeout
            Thread.sleep(1100); // so a success would move last_success by >= 1 s
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> authPurge.purgeLoginAttempts()).hasMessageContaining("lock timeout");
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(2500));
            c.rollback();
        }
        assertThat(failures("login-attempts-purge")).isEqualTo(failed + 1);
        assertThat(runs("login-attempts-purge")).isEqualTo(runs + 1);
        assertThat(meters.get("job.last.success.timestamp").tag("job", "login-attempts-purge").gauge().value()).isEqualTo(last);
        // the lock is released after a failed run: the next night runs
        authPurge.purgeLoginAttempts();
        assertThat(runs("login-attempts-purge")).isEqualTo(runs + 2);
    }

    // ---------- fixtures ----------

    private void holdLock(String job, String by) {
        su.update("INSERT INTO shedlock (name, lock_until, locked_at, locked_by) VALUES (?, timezone('utc', now()) + interval '10 minutes', "
                + "timezone('utc', now()), ?) ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until, locked_at = EXCLUDED.locked_at, "
                + "locked_by = EXCLUDED.locked_by", job, by);
    }

    private long outbox(UUID tenantId, String status, int daysAgo) {
        return su.queryForObject("INSERT INTO outbox_events (tenant_id, event_type, payload, status, next_attempt_at, created_at, updated_at) "
                + "VALUES (?, 'q3.ops013.test', '{}'::jsonb, ?, 'infinity', now() - make_interval(days => ?), now() - make_interval(days => ?)) RETURNING id",
                Long.class, tenantId, status, daysAgo, daysAgo);
    }

    private List<Long> outboxLeft(long... ids) {
        List<Long> out = new ArrayList<>();
        for (long id : ids) {
            if (su.queryForObject("SELECT count(*) FROM outbox_events WHERE id = ?", Integer.class, id) == 1) out.add(id);
        }
        return out;
    }

    private long attempt(int daysAgo) {
        return su.queryForObject("INSERT INTO login_attempts (email, ip_address, succeeded, attempted_at) "
                + "VALUES ('q3@example.test', '192.0.2.1', false, now() - make_interval(days => ?)) RETURNING id", Long.class, daysAgo);
    }

    private UUID message(UUID tenantId, String age) {
        return su.queryForObject("INSERT INTO whatsapp_messages (tenant_id, to_phone, template_name, language, created_at) "
                + "VALUES (?, '+910000000000', 'q3_ops013', 'en', now() - ?::interval) RETURNING id", UUID.class, tenantId, age);
    }
}

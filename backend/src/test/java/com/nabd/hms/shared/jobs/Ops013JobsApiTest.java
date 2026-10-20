package com.nabd.hms.shared.jobs;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nabd.hms.auth.AuthPurgeJob;
import com.nabd.hms.messaging.WhatsAppMessagePurgeJob;
import com.nabd.hms.support.ApiTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OPS-013 acceptance: one execution across replicas, purge retention/batching/lock waits, job metrics. */
class Ops013JobsApiTest extends ApiTestBase {

    @Autowired LockProvider lockProvider;
    @Autowired DataSource dataSource;
    @Autowired Clock clock;
    @Autowired AuthPurgeJob authPurge;
    @Autowired WhatsAppMessagePurgeJob messagePurge;
    @Autowired MeterRegistry meters;
    @LocalManagementPort int mgmtPort;

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureSkipLog() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(SkipLoggingLockProvider.class)).addAppender(logs);
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger(SkipLoggingLockProvider.class)).detachAppender(logs);
    }

    /** A second replica: its own lock provider (own locked_by) on the same database, as nabd_app. */
    private LockProvider replicaB() {
        return new SkipLoggingLockProvider(new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource)).usingDbTime().withLockedByValue("replica-b").build()), clock);
    }

    private static LockConfiguration cfg(String name) {
        return new LockConfiguration(java.time.Instant.now(Clock.systemUTC()), name, Duration.ofMinutes(1), Duration.ZERO);
    }

    private List<String> skipLines() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.contains("skipped")).toList();
    }

    @Test
    void twoReplicasDueAtTheSameTime_exactlyOneExecutes_otherLogsSkip() throws Exception {
        String job = "ops013-test-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Runnable task = () -> {
            runs.incrementAndGet();
            try { Thread.sleep(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (LockProvider p : List.of(lockProvider, replicaB())) {
            DefaultLockingTaskExecutor ex = new DefaultLockingTaskExecutor(p);
            pool.submit(() -> { start.await(); ex.executeWithLock(task, cfg(job)); return null; });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        assertThat(runs.get()).isEqualTo(1);
        assertThat(su.queryForObject("SELECT count(*) FROM shedlock WHERE name = ?", Integer.class, job)).isEqualTo(1);
        assertThat(skipLines()).containsExactly("job " + job + " skipped: locked by another replica");
    }

    @Test
    void scheduledJobBean_skipsWhileAnotherReplicaHoldsItsLock_thenRunsAndExportsMetrics() throws Exception {
        UUID expired = seedSession(Duration.ofDays(31), null);
        SimpleLock held = replicaB().lock(cfg("sessions-purge")).orElseThrow();
        double runsBefore = durationCount("sessions-purge");
        authPurge.purgeSessions();                          // replica A's scheduler fires while B runs it
        assertThat(sessionExists(expired)).isTrue();
        assertThat(durationCount("sessions-purge")).isEqualTo(runsBefore); // skipped run records nothing
        assertThat(skipLines()).contains("job sessions-purge skipped: locked by another replica");

        held.unlock();
        authPurge.purgeSessions();
        assertThat(sessionExists(expired)).isFalse();
        assertThat(durationCount("sessions-purge")).isEqualTo(runsBefore + 1);
        assertThat(meters.get("job.last.success.timestamp").tag("job", "sessions-purge").gauge().value()).isPositive();
        assertThat(meters.get("job.failures").tag("job", "sessions-purge").counter().count()).isZero();

        String scrape = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + mgmtPort + "/actuator/prometheus"))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        assertThat(scrape).contains("job_last_success_timestamp{job=\"sessions-purge\"}", "job_duration_seconds_count{job=\"sessions-purge\"}",
                "job_failures_total{job=\"sessions-purge\"} 0.0");
    }

    @Test
    void sessionsPurge_respectsThe30DayBoundary_andRevocationsAfterExpiry() {
        UUID old = seedSession(Duration.ofDays(31), null);
        UUID young = seedSession(Duration.ofDays(29), null);
        UUID revokedRecently = seedSession(Duration.ofDays(31), Duration.ofDays(10));
        UUID revokedOld = seedSession(Duration.ofDays(40), Duration.ofDays(35));
        UUID live = seedSession(Duration.ofDays(-5), null);

        authPurge.purgeSessions();

        assertThat(sessionExists(old)).isFalse();
        assertThat(sessionExists(revokedOld)).isFalse();
        assertThat(sessionExists(young)).isTrue();
        assertThat(sessionExists(revokedRecently)).isTrue();
        assertThat(sessionExists(live)).isTrue();
    }

    @Test
    void sessionsPurge_deletesInBatchesOf10k_eachCallAtMostOneBatch() {
        assertThat(ReflectionTestUtils.getField((Object) AopTestUtils.getTargetObject(authPurge), "batch")).isEqualTo(10_000);
        JdbcTemplate app = new JdbcTemplate(dataSource); // as nabd_app: the DEFINER function is its only way past RLS
        app.queryForObject("SELECT purge_sessions(30, 1000000)", Integer.class); // leftovers of other tests
        for (int i = 0; i < 5; i++) seedSession(Duration.ofDays(45), null);
        assertThat(app.queryForObject("SELECT purge_sessions(30, 2)", Integer.class)).isEqualTo(2);
        assertThat(app.queryForObject("SELECT purge_sessions(30, 2)", Integer.class)).isEqualTo(2);
        assertThat(app.queryForObject("SELECT purge_sessions(30, 2)", Integer.class)).isEqualTo(1);
        assertThat(app.queryForObject("SELECT purge_sessions(30, 10000)", Integer.class)).isZero();
    }

    @Test
    void sessionsPurge_neverWaitsMoreThanOneSecondOnLocks() throws Exception {
        assertThat(su.queryForObject("SELECT array_to_string(proconfig, ',') FROM pg_proc WHERE proname = 'purge_sessions'", String.class))
                .contains("lock_timeout=1s");
        UUID rowLocked = seedSession(Duration.ofDays(31), null);
        UUID free = seedSession(Duration.ofDays(31), null);
        JdbcTemplate app = new JdbcTemplate(dataSource);
        try (Connection c = superuserDataSource().getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("SELECT 1 FROM sessions WHERE id = '" + rowLocked + "' FOR UPDATE"); // a request holding a row
            long t0 = System.nanoTime();
            app.queryForObject("SELECT purge_sessions(30, 10000)", Integer.class);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(1));
            assertThat(sessionExists(free)).isFalse();
            c.rollback();
        }
        assertThat(sessionExists(rowLocked)).isTrue(); // skipped, picked up next night

        try (Connection c = superuserDataSource().getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("LOCK TABLE sessions IN ACCESS EXCLUSIVE MODE"); // worst case: a table lock
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> app.queryForObject("SELECT purge_sessions(30, 10000)", Integer.class))
                    .hasMessageContaining("lock timeout");
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(2500));
            c.rollback();
        }
    }

    @Test
    void loginAttemptsAndMessagesPurge_respectTheirRetention() {
        long oldAttempt = seedLoginAttempt(91), youngAttempt = seedLoginAttempt(89);
        SeededTenant t = seedTenant();
        UUID oldMsg = seedMessage(t.id(), "25 months"), youngMsg = seedMessage(t.id(), "23 months");

        authPurge.purgeLoginAttempts();
        messagePurge.purge();

        assertThat(su.queryForObject("SELECT count(*) FROM login_attempts WHERE id IN (?, ?)", Integer.class, oldAttempt, youngAttempt)).isEqualTo(1);
        assertThat(su.queryForObject("SELECT count(*) FROM login_attempts WHERE id = ?", Integer.class, youngAttempt)).isEqualTo(1);
        assertThat(su.queryForObject("SELECT count(*) FROM whatsapp_messages WHERE id = ?", Integer.class, oldMsg)).isZero();
        assertThat(su.queryForObject("SELECT count(*) FROM whatsapp_messages WHERE id = ?", Integer.class, youngMsg)).isEqualTo(1);
        assertThat(meters.get("job.duration").tag("job", "login-attempts-purge").timer().count()).isPositive();
        assertThat(meters.get("job.duration").tag("job", "messages-purge").timer().count()).isPositive();
    }

    @Test
    void shedlockIsAPlatformTable_appMayLockButNotDelete() {
        assertThat(su.queryForObject("""
                SELECT has_table_privilege('nabd_app', 'shedlock', 'SELECT') AND has_table_privilege('nabd_app', 'shedlock', 'INSERT')
                   AND has_table_privilege('nabd_app', 'shedlock', 'UPDATE') AND NOT has_table_privilege('nabd_app', 'shedlock', 'DELETE')
                   AND NOT has_table_privilege('nabd_reports', 'shedlock', 'SELECT')""", Boolean.class)).isTrue();
        assertThat(su.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = 'shedlock' AND column_name = 'tenant_id'",
                Integer.class)).isZero();
    }

    // ---- fixtures (superuser: seeding and counting bypass RLS) ----

    private UUID seedSession(Duration expiredAgo, Duration revokedAgo) {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "ops013-" + UUID.randomUUID() + "@example.test", null, false);
        UUID id = UUID.randomUUID();
        su.update("""
                INSERT INTO sessions (id, tenant_id, staff_id, family_id, token_hash, created_at, last_seen_at, expires_at, revoked_at, revoked_reason)
                VALUES (?, ?, ?, gen_random_uuid(), ?, now() - interval '60 days', now() - interval '60 days',
                        now() - make_interval(secs => ?), now() - make_interval(secs => ?), ?)""",
                id, t.id(), s.id(), "ops013-" + id, (double) expiredAgo.toSeconds(),
                revokedAgo == null ? null : (double) revokedAgo.toSeconds(), revokedAgo == null ? null : "logout");
        return id;
    }

    private boolean sessionExists(UUID id) {
        return su.queryForObject("SELECT count(*) FROM sessions WHERE id = ?", Integer.class, id) == 1;
    }

    private long seedLoginAttempt(int daysAgo) {
        return su.queryForObject("""
                INSERT INTO login_attempts (email, ip_address, succeeded, attempted_at)
                VALUES ('ops013@example.test', '192.0.2.1', false, now() - make_interval(days => ?)) RETURNING id""", Long.class, daysAgo);
    }

    private UUID seedMessage(UUID tenantId, String age) {
        return su.queryForObject("INSERT INTO whatsapp_messages (tenant_id, to_phone, template_name, language, created_at) "
                + "VALUES (?, '+910000000000', 'ops013_test', 'en', now() - ?::interval) RETURNING id", UUID.class, tenantId, age);
    }

    private double durationCount(String job) {
        var t = meters.find("job.duration").tag("job", job).timer();
        return t == null ? 0 : t.count();
    }
}

package com.nabd.hms.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.common.Ops020Replica;
import com.nabd.hms.common.TenantConfigEvictions;
import com.nabd.hms.common.TenantMismatchException;
import com.nabd.hms.common.valkey.ValkeyConfig;
import com.nabd.hms.common.valkey.ValkeyProperties;
import com.nabd.hms.config.AuthProperties;
import com.nabd.hms.shared.cache.CacheKeyPolicy;
import com.nabd.hms.shared.cache.DataTier;
import com.nabd.hms.shared.relay.OutboxRelay;
import com.nabd.hms.shared.relay.RelayProperties;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ValkeyTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OPS-020 with a real Redis 6.2 (the Valkey stand-in). "Replica B" is a second set of production beans (its own Lettuce
 * client, its own SessionLivenessCache / decoder / ClinicClock / subscription) next to the app context (replica A),
 * both on the one embedded server: the only thing replicas share in production.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class Ops020CacheApiTest extends ApiTestBase {

    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();
    static final String NEW_ZONE = "Pacific/Chatham"; // distinctive: the log/metric scan looks for it
    static final List<String> SQL = new CopyOnWriteArrayList<>();
    static final List<LettuceConnectionFactory> FACTORIES = new ArrayList<>();
    static final ListAppender<ILoggingEvent> LOGS = new ListAppender<>();
    static final Set<String> CACHED_VALUES = new java.util.concurrent.ConcurrentSkipListSet<>();
    static volatile boolean counting;
    static volatile Thread countThread;
    static volatile PrometheusMeterRegistry prometheus;

    /**
     * The same warm walk-in check-in on integ/s12 283ed54 (before OPS-020) sent 26 statements: 10 tenant binds (several per
     * transaction), the liveness SELECT, 2 tenant-zone lookups and 13 business statements (report s12-OPS-020.md).
     */
    static final int BASELINE_WARM_STATEMENTS = 26;

    @DynamicPropertySource
    static void valkey(DynamicPropertyRegistry r) {
        r.add("app.valkey.url", VALKEY::url);
        r.add("app.cache.tenant-config-ttl-seconds", () -> "30");
        r.add("app.rate-limit.user-per-minute", () -> "100000");
        r.add("app.rate-limit.user-burst", () -> "100000");
        r.add("app.rate-limit.ip-per-minute", () -> "100000");
        r.add("app.rate-limit.ip-burst", () -> "100000");
    }

    @TestConfiguration
    static class CountingDataSource {
        @Bean
        static BeanPostProcessor countStatements() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource ds && !(bean instanceof Proxy) ? proxy(DataSource.class, ds) : bean;
                }
            };
        }
    }

    @Autowired
    SessionLivenessChecker checker;
    @Autowired
    @Qualifier("accessTokenDecoder")
    JwtDecoder accessTokenDecoder;
    @Autowired
    CacheKeyPolicy keys;
    @Autowired
    AuthProperties authProperties;
    @Autowired
    StringRedisTemplate valkey;
    @Autowired
    MeterRegistry meters;
    @Autowired
    ObjectProvider<PrometheusMeterRegistry> prometheusRegistry;
    @Autowired
    ClinicClock clinicClock;
    @Autowired
    TenantConfigEvictions evictions;
    @Autowired
    OutboxDispatcher dispatcher;
    @Autowired
    Environment env;

    @BeforeEach
    void captureLogs() {
        if (!LOGS.isStarted()) { // after Boot configured logback, once per class
            LOGS.start();
            ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(LOGS);
        }
        prometheus = prometheusRegistry.getIfAvailable();
    }

    /** CI (cache_values.py --samples) re-checks these files: no cached value in logs or the Prometheus scrape. */
    @AfterAll
    static void writeScanSamplesAndStop() throws Exception {
        try {
            Path dir = Path.of("target", "cache-scan");
            Files.createDirectories(dir);
            List<String> logs = LOGS.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            String scrape = prometheus == null ? "" : prometheus.scrape();
            Files.write(dir.resolve("logs.txt"), logs);
            Files.writeString(dir.resolve("metrics.txt"), scrape);
            Files.write(dir.resolve("values.txt"), CACHED_VALUES);
            assertThat(CACHED_VALUES).contains(NEW_ZONE);
            for (String v : CACHED_VALUES.stream().filter(v -> v.length() >= 4).toList()) {
                assertThat(String.join("\n", logs)).as("no cached value in logs").doesNotContain(v);
                assertThat(scrape).as("no cached value in metrics").doesNotContain(v);
            }
            assertThat(scrape).contains("nabd_session_cache_lookups_total");
        } finally {
            ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(LOGS);
            FACTORIES.forEach(LettuceConnectionFactory::destroy);
            VALKEY.close();
        }
    }

    // ---------- replica B ----------

    static StringRedisTemplate replicaTemplate() {
        LettuceConnectionFactory f = new ValkeyConfig().valkeyConnectionFactory(new ValkeyProperties(VALKEY.url(), 500, 250), new MockEnvironment());
        f.afterPropertiesSet();
        f.start();
        FACTORIES.add(f);
        return new StringRedisTemplate(f);
    }

    static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        return new StaticListableBeanFactory(Map.of("b", bean)).getBeanProvider(type);
    }

    SessionLivenessCache replicaCache(StringRedisTemplate t) {
        return new SessionLivenessCache(provider(StringRedisTemplate.class, t), keys, new SimpleMeterRegistry(), authProperties, Clock.systemUTC(), jdbc);
    }

    // ---------- statement counting ----------

    static void record(String sql) {
        if (counting && (Thread.currentThread().getName().startsWith("http-nio") || Thread.currentThread() == countThread)) {
            SQL.add(sql.replaceAll("\\s+", " ").trim());
        }
    }

    static <T> T proxy(Class<T> type, T target) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, args) -> {
            String n = m.getName();
            if ((n.equals("prepareStatement") || n.equals("prepareCall")) && args != null && args[0] instanceof String s) {
                record(s);
            } else if (target instanceof Statement && n.startsWith("execute") && args != null && args[0] instanceof String s) {
                record(s);
            }
            Object out;
            try {
                out = m.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (out instanceof Connection c && n.equals("getConnection")) {
                return proxy(Connection.class, c);
            }
            if (n.equals("createStatement") && out instanceof Statement st) {
                return proxy(Statement.class, st);
            }
            return out;
        }));
    }

    // ---------- fixtures ----------

    private static String phone() {
        return "+9198" + String.format("%08d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L));
    }

    private static String mail(String p) {
        return p + UUID.randomUUID().toString().substring(0, 8) + "@ops020.test";
    }

    private String patient(String token) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Ops Twenty " + uniqueLetters(), "phone", phone(), "dob", java.time.LocalDate.of(1940, 1, 1).plusDays(Math.abs(UUID.randomUUID().getLeastSignificantBits() % 20000)).toString(), "gender", "female")), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("patient: %s %s", r.getStatusCode(), r.getBody()).isTrue();
        return (String) r.getBody().get("id");
    }

    private ResponseEntity<Map> checkIn(String token, String patientId, UUID doctorId) {
        return exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class);
    }

    private HttpStatus get(String token) {
        return (HttpStatus) exchange("/v1/auth/sessions", HttpMethod.GET, authed(token), String.class).getStatusCode();
    }

    private UUID sid(String token) {
        return UUID.fromString(accessTokenDecoder.decode(token).getClaimAsString("sid"));
    }

    private String key(UUID sid) {
        return "nabd:sess:g:" + sid;
    }

    private double counter(String name) {
        Counter c = meters.find(name).counter();
        return c == null ? 0 : c.count();
    }

    private static long errorLines() {
        return LOGS.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR
                && e.getFormattedMessage().startsWith("session revocation cache unavailable")).count();
    }

    private String mfaLogin(SeededTenant tenant, SeededStaff staff) {
        ResponseEntity<Map> l = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        ResponseEntity<Map> v = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", l.getBody().get("challengeId"), "code", currentTotpCode())), Map.class);
        assertThat(v.getStatusCode()).as("mfa verify: %s", v.getBody()).isEqualTo(HttpStatus.OK);
        return (String) v.getBody().get("accessToken");
    }

    /** Polls replica B's decoder until the token is refused; returns the ms it took (fails after 3 s). */
    private long msUntilRefused(SessionRevocationJwtDecoder decoderB, String token, long t0) throws InterruptedException {
        while (System.nanoTime() - t0 < 3_000_000_000L) {
            try {
                decoderB.decode(token);
            } catch (BadJwtException e) {
                assertThat(e.getMessage()).isEqualTo("session revoked");
                return (System.nanoTime() - t0) / 1_000_000;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("token still accepted on replica B after 3 s");
    }

    // ---------- AC3: revocation on any replica within 1 s ----------

    @Test
    void revokedSessionIsRejectedOnAnotherReplicaWithinOneSecondForLogoutBreakGlassEndAndRoleChange() throws Exception {
        StringRedisTemplate tB = replicaTemplate();
        SessionLivenessCache cacheB = replicaCache(tB);
        AtomicInteger dbChecksB = new AtomicInteger();
        SessionLivenessChecker countingChecker = new SessionLivenessChecker(null, null, null) {
            @Override
            boolean isActive(UUID tenantId, UUID sessionId) {
                dbChecksB.incrementAndGet();
                return checker.isActive(tenantId, sessionId);
            }
        };
        SessionRevocationJwtDecoder decoderB = new SessionRevocationJwtDecoder(accessTokenDecoder, countingChecker, cacheB);

        SeededTenant t = seedTenant();
        UUID full = seedFullAccessRole(t.id());
        // logout
        SeededStaff s1 = seedStaff(t, full, mail("lo"), phone(), false);
        String tok1 = loginAndGetAccessToken(s1);
        decoderB.decode(tok1); // B warms its view (miss -> DB -> live marker)
        assertThat(get(tok1)).isEqualTo(HttpStatus.OK); // A serves it from the cache
        dbChecksB.set(0);
        decoderB.decode(tok1);
        assertThat(dbChecksB.get()).as("live session answered from Valkey, no DB check on B").isZero();
        long t0 = System.nanoTime();
        assertThat(exchange("/v1/auth/logout", HttpMethod.POST, authed(tok1), Void.class).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(msUntilRefused(decoderB, tok1, t0)).as("logout -> refused on B, ms").isLessThan(1000);
        assertThat(get(tok1)).isEqualTo(HttpStatus.UNAUTHORIZED);

        // break-glass end: the elevated token dies on B
        SeededStaff doc = seedStaff(t, seedBreakGlassDoctorRole(t.id()), mail("bg"), phone(), false);
        String docTok = loginAndGetAccessToken(doc);
        ResponseEntity<Map> bg = activateBreakGlass(docTok, "Unconscious patient in triage, no relative present yet", null);
        assertThat(bg.getStatusCode().is2xxSuccessful()).as("break-glass: %s", bg.getBody()).isTrue();
        String bgTok = (String) bg.getBody().get("accessToken");
        decoderB.decode(bgTok);
        t0 = System.nanoTime();
        assertThat(exchange("/v1/auth/break-glass/" + bg.getBody().get("grantId") + "/deactivate", HttpMethod.POST,
                authedJsonBody(bgTok, Map.of()), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(msUntilRefused(decoderB, bgTok, t0)).as("break-glass end -> refused on B, ms").isLessThan(1000);

        // role change (staff module's revoke path)
        SeededStaff owner = seedStaff(t, full, mail("ow"), phone(), true);
        String ownerTok = mfaLogin(t, owner);
        UUID recRole = seedRole(t.id(), "Desk " + UUID.randomUUID().toString().substring(0, 6), false,
                new ModuleGrant("queue", true, true, true, false, false, false, false));
        SeededStaff rec = seedStaff(t, recRole, mail("rc"), phone(), false);
        String recTok = loginAndGetAccessToken(rec);
        decoderB.decode(recTok);
        UUID newRole = seedRole(t.id(), "Desk+ " + UUID.randomUUID().toString().substring(0, 6), false,
                new ModuleGrant("queue", true, true, true, false, false, false, false),
                new ModuleGrant("patients", true, false, false, false, false, false, false));
        dbChecksB.set(0);
        t0 = System.nanoTime();
        assertThat(exchange("/v1/staff/" + rec.id(), HttpMethod.PATCH, authedJsonBody(ownerTok, Map.of("roleId", newRole.toString())),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(msUntilRefused(decoderB, recTok, t0)).as("role change -> refused on B, ms").isLessThan(1000);
        assertThat(dbChecksB.get()).as("B learnt every revocation from Valkey, not from its own DB check").isZero();
        assertThat(get(ownerTok)).isEqualTo(HttpStatus.OK); // the owner's own session is untouched
        assertThat(valkey.getExpire(key(sid(recTok)))).as("revoked marker TTL = access-token life")
                .isBetween(1L, authProperties.accessTokenTtlMinutes() * 60L);
    }

    @Test
    void liveMarkerNeverOutlivesSixtySecondsAndUnknownOrForeignSessionsStillGoToTheDb() {
        SeededTenant t = seedTenant();
        SeededTenant other = seedTenant();
        String tok = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), mail("lv"), phone(), false));
        assertThat(get(tok)).isEqualTo(HttpStatus.OK);
        UUID sid = sid(tok);
        assertThat(valkey.opsForValue().get(key(sid))).isEqualTo("1");
        assertThat(valkey.getExpire(key(sid))).isBetween(1L, 60L);
        // the DB still decides a miss: a random sid, and a real sid presented with another tenant's id, are refused
        assertThat(checker.isActive(t.id(), UUID.randomUUID())).isFalse();
        assertThat(checker.isActive(other.id(), sid)).isFalse();
        SessionLivenessCache cache = replicaCache(valkey);
        AtomicInteger db = new AtomicInteger();
        assertThat(cache.isActive(UUID.randomUUID(), null, () -> db.incrementAndGet() < 0)).isFalse();
        assertThat(db.get()).isEqualTo(1);
    }

    // ---------- AC5: Valkey down ----------

    @Test
    void valkeyDownFallsBackToTheDbFailsClosedForRevokedSessionsCheckInStillWorksAndAlerts() throws Exception {
        SeededTenant t = seedTenant();
        UUID full = seedFullAccessRole(t.id());
        SeededStaff desk = seedStaff(t, full, mail("dn"), phone(), false);
        String live = loginAndGetAccessToken(desk);
        String doomed = loginAndGetAccessToken(seedStaff(t, full, mail("dm"), phone(), false));
        String patientId = patient(live);
        assertThat(get(doomed)).isEqualTo(HttpStatus.OK); // cached as live
        UUID doomedSid = sid(doomed);
        double fallbacksBefore = counter("nabd.session_cache.fallback");
        long errorLinesBefore = errorLines();
        VALKEY.stop();
        try {
            // revoked while Valkey is down: the write fails, the DB still refuses it on the very next request
            assertThat(exchange("/v1/auth/logout", HttpMethod.POST, authed(doomed), Void.class).getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(get(doomed)).as("fail closed for a revoked session").isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(checkIn(live, patientId, desk.id()).getStatusCode()).as("check-in still works").isEqualTo(HttpStatus.CREATED);
            assertThat(get(live)).isEqualTo(HttpStatus.OK);
            assertThat(counter("nabd.session_cache.fallback")).as("alert metric nabd_session_cache_fallback_total").isGreaterThan(fallbacksBefore);
            assertThat(errorLines() > errorLinesBefore || errorLinesBefore > 0).as("ERROR line (at most once a minute)").isTrue();
        } finally {
            VALKEY.restart();
        }
        // back: the revocation that could not be written is flushed, and the cache serves again
        String marker = null;
        for (int i = 0; i < 60 && !"0".equals(marker); i++) {
            Thread.sleep(500);
            assertThat(get(live)).isEqualTo(HttpStatus.OK);
            try {
                marker = valkey.opsForValue().get(key(doomedSid));
            } catch (RuntimeException reconnecting) {
                marker = null;
            }
        }
        assertThat(marker).as("pending revocation written once Valkey is back").isEqualTo("0");
        assertThat(get(doomed)).isEqualTo(HttpStatus.UNAUTHORIZED);
        String rule = Files.readString(Path.of("../infra/observability/session-cache.rules.yml"));
        assertThat(rule).contains("alert: NabdSessionCacheFallback").contains("increase(nabd_session_cache_fallback_total[1m])").contains("for: 0m");
    }

    // ---------- AC4: timezone change reaches every instance within 5 s ----------

    @Test
    void timezoneChangeConsumedFromOrganizationConfigChangedReachesAllInstancesWithinFiveSeconds() throws Exception {
        SeededTenant t = seedTenant();
        String tok = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), mail("tz"), phone(), false));
        StringRedisTemplate tB = replicaTemplate();
        ClinicClock clockB = Ops020Replica.clinicClock(jdbc, keys, 30);
        TenantConfigEvictions evictionsB = new TenantConfigEvictions(clockB, provider(StringRedisTemplate.class, tB),
                provider(RedisConnectionFactory.class, tB.getConnectionFactory()));
        evictionsB.ensureSubscribed();
        evictions.ensureSubscribed();
        ZoneId before = clinicClock.zone(t.id());
        assertThat(clockB.zone(t.id())).isEqualTo(before);
        assertThat(before.getId()).isNotEqualTo(NEW_ZONE);

        ResponseEntity<Map> patch = exchange("/v1/setup/profile", HttpMethod.PATCH,
                authedJsonBody(tok, Map.of("name", "Ops020 Clinic", "timezone", NEW_ZONE)), Map.class);
        assertThat(patch.getStatusCode()).as("profile: %s", patch.getBody()).isEqualTo(HttpStatus.OK);
        CACHED_VALUES.add(NEW_ZONE);
        // committed, not yet consumed: both instances still answer from their cache (the event is what moves them)
        assertThat(clinicClock.zone(t.id())).isEqualTo(before);
        assertThat(clockB.zone(t.id())).isEqualTo(before);

        // FIX-C (D1): with VALKEY_URL set the relay owns domain rows (V1680), so the dispatcher no longer delivers this
        // event; the worker's relay XADDs it and this context's 'cache-invalidator' group (consumers on, 200 ms) consumes it
        dispatcher.poll(); // still polled as in production: it must not be what moves the zone
        long t0 = System.nanoTime();
        OutboxRelay relay = new OutboxRelay(jdbc, valkey, new RelayProperties(true, 500, 10, 2000, 2000, 1000, 1_000_000, null, null, null),
                OutboxRelay.direct(env.getProperty("spring.datasource.url"), env.getProperty("spring.datasource.username"),
                        env.getProperty("spring.datasource.password")), new SimpleMeterRegistry());
        relay.start();
        try {
            while ((!clinicClock.zone(t.id()).getId().equals(NEW_ZONE) || !clockB.zone(t.id()).getId().equals(NEW_ZONE))
                    && System.nanoTime() - t0 < 10_000_000_000L) {
                Thread.sleep(20);
            }
        } finally {
            relay.stop();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(clinicClock.zone(t.id()).getId()).isEqualTo(NEW_ZONE);
        assertThat(clockB.zone(t.id()).getId()).isEqualTo(NEW_ZONE);
        assertThat(ms).as("consumed -> every instance on the new zone, ms").isLessThan(5000);
        assertThat(new JdbcTemplate(superuserDataSource()).queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? "
                + "AND event_type = 'organization.config.changed.v1' AND status = 'done'", Integer.class, t.id())).as("consumed once").isEqualTo(1);
    }

    // ---------- AC2: statements per check-in ----------

    @Test
    void walkInCheckInSendsFewerStatements() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff staff = seedStaff(t, seedFullAccessRole(t.id()), mail("ct"), phone(), false);
        String token = loginAndGetAccessToken(staff);
        List<String> cold = countCheckIn(token, staff.id());
        List<String> warm = countCheckIn(token, staff.id());
        List<String> out = new ArrayList<>(List.of("cold=" + cold.size() + " warm=" + warm.size()));
        cold.forEach(s -> out.add("cold: " + (s.length() > 160 ? s.substring(0, 160) : s)));
        warm.forEach(s -> out.add("warm: " + (s.length() > 160 ? s.substring(0, 160) : s)));
        Files.write(Path.of("target", "ops020-statements.txt"), out);
        assertThat(warm).as("no session-liveness, tenant-zone or second tenant-bind statement on a warm check-in")
                .noneMatch(s -> s.contains("FROM sessions") || s.contains("SELECT timezone, region FROM tenants"));
        long binds = warm.stream().filter(s -> s.contains("set_config('app.tenant_id'")).count();
        assertThat(binds).as("one tenant bind per transaction (idempotency claim, check-in, idempotency complete)").isLessThanOrEqualTo(3);
        // PLT-019: RepositoryTransactionGuard (test-only, nabd.tenancy.strict-repositories) adds a verifyBinding() round trip per repository call; production sends none
        long guardChecks = warm.stream().filter(s -> s.startsWith("SELECT current_setting('app.tenant_id', true) || current_setting('app.visible_tenant_ids', true)")).count();
        assertThat(warm.size() - guardChecks).as("statements per warm check-in (without the test-only binding checks)").isLessThanOrEqualTo(BASELINE_WARM_STATEMENTS - 9);
    }

    private List<String> countCheckIn(String token, UUID doctorId) {
        String patientId = patient(token);
        SQL.clear();
        counting = true;
        ResponseEntity<Map> r;
        try {
            r = checkIn(token, patientId, doctorId);
        } finally {
            counting = false;
        }
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new ArrayList<>(SQL);
    }

    @Test
    void aTransactionIsBoundToItsTenantOnceAndAMismatchStillFails() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            SQL.clear();
            countThread = Thread.currentThread();
            counting = true;
            try {
                tenantContext.set(a.id());
                tenantContext.set(a.id());
                tenantContext.set(a.id());
            } finally {
                counting = false;
            }
            assertThat(SQL).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT app_tenant_id()", UUID.class)).isEqualTo(a.id());
        });
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            tenantContext.set(a.id());
            assertThatThrownBy(() -> tenantContext.set(b.id())).isInstanceOf(TenantMismatchException.class);
        })).isInstanceOf(UnexpectedRollbackException.class);
        // the next transaction starts unbound again
        tx.executeWithoutResult(s -> {
            tenantContext.set(b.id());
            assertThat(jdbc.queryForObject("SELECT app_tenant_id()", UUID.class)).isEqualTo(b.id());
        });
    }

    // ---------- ADR-04 gate + key format ----------

    @Test
    void cacheKeyPolicyIsABeanLoadedFromTheClassificationAndGatesEveryCachedValue() {
        assertThat(keys.tierOf("tenants.timezone")).isEqualTo(DataTier.OPERATIONAL);
        assertThat(keys.tierOf("sessions.revoked_at")).isEqualTo(DataTier.OPERATIONAL);
        assertThat(keys.tierOf("sessions.ip_address")).isEqualTo(DataTier.PII);
        assertThat(keys.tierOf("patients.name")).isEqualTo(DataTier.PII);
        assertThat(keys.tierOf("patients.no_such_column")).as("unknown columns count as PHI").isEqualTo(DataTier.PHI);
        UUID sid = UUID.randomUUID();
        assertThat(keys.key("sess", null, List.of("sessions.id", "sessions.revoked_at"), false, sid.toString())).isEqualTo(key(sid));
        assertThatThrownBy(() -> keys.key("sess", null, List.of("sessions.id", "patients.name"), false, sid.toString()))
                .isInstanceOf(CacheKeyPolicy.CacheRefusedException.class);
        assertThatThrownBy(() -> keys.key("tz", UUID.randomUUID(), List.of("patients.no_such_column"), false, "x"))
                .isInstanceOf(CacheKeyPolicy.CacheRefusedException.class);
    }

    /** Patient registration refuses a second name+dob match (duplicate candidates, 409): a fresh surname each time. */
    static String uniqueLetters() {
        StringBuilder b = new StringBuilder();
        for (char c : UUID.randomUUID().toString().replace("-", "").substring(0, 8).toCharArray()) {
            b.append((char) ('a' + Character.digit(c, 16)));
        }
        return b.toString();
    }
}

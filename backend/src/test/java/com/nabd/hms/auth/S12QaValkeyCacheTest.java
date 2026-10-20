package com.nabd.hms.auth;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.valkey.ValkeyConfig;
import com.nabd.hms.common.valkey.ValkeyProperties;
import com.nabd.hms.config.AuthProperties;
import com.nabd.hms.shared.cache.CacheKeyPolicy;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ValkeyTestSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Bean;
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

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprint 12 independent QA (QA-7): OPS-020 acceptance against real Postgres and Redis 6.2 (Valkey stand-in).
 * "Replica B" = a second SessionLivenessCache + decoder on its own Lettuce client, as a second pod would have.
 * Timezone propagation through the event path is tested end to end in S12QaEventPipelineTest (relay running).
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class S12QaValkeyCacheTest extends ApiTestBase {

    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();
    static final List<LettuceConnectionFactory> FACTORIES = new ArrayList<>();
    static final ListAppender<ILoggingEvent> LOGS = new ListAppender<>();
    static final List<String> SQL = new CopyOnWriteArrayList<>();
    static volatile boolean counting;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.valkey.url", VALKEY::url);
        r.add("app.cache.tenant-config-ttl-seconds", () -> "30");
        r.add("app.rate-limit.user-per-minute", () -> "100000");
        r.add("app.rate-limit.user-burst", () -> "100000");
        r.add("app.rate-limit.ip-per-minute", () -> "100000");
        r.add("app.rate-limit.ip-burst", () -> "100000");
    }

    @AfterAll
    static void stop() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(LOGS);
        FACTORIES.forEach(LettuceConnectionFactory::destroy);
        VALKEY.close();
    }

    /** Counts every statement sent on a request thread while {@link #counting} is on. */
    @TestConfiguration
    static class CountingDataSource {
        @Bean
        static BeanPostProcessor qa7CountStatements() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource ds && !Proxy.isProxyClass(bean.getClass()) ? proxy(DataSource.class, ds) : bean;
                }
            };
        }
    }

    static <T> T proxy(Class<T> type, T target) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, args) -> {
            String n = m.getName();
            boolean sqlArg = args != null && args.length > 0 && args[0] instanceof String;
            if (counting && Thread.currentThread().getName().startsWith("http-nio") && sqlArg
                    && (n.equals("prepareStatement") || n.equals("prepareCall") || (target instanceof Statement && n.startsWith("execute")))) {
                SQL.add(((String) args[0]).replaceAll("\\s+", " ").trim());
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
            if (out instanceof Statement st && n.equals("createStatement")) {
                return proxy(Statement.class, st);
            }
            return out;
        }));
    }

    @Autowired SessionLivenessChecker checker;
    @Autowired @Qualifier("accessTokenDecoder") JwtDecoder accessTokenDecoder;
    @Autowired CacheKeyPolicy keys;
    @Autowired AuthProperties authProperties;
    @Autowired StringRedisTemplate valkey;
    @Autowired MeterRegistry meters;
    @Autowired ClinicClock clinicClock;
    @LocalManagementPort int mgmtPort;

    @BeforeEach
    void logs() {
        if (!LOGS.isStarted()) {
            LOGS.start();
            ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(LOGS);
        }
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) { // reconnect after an outage test
            try {
                valkey.opsForValue().get("qa7:ping");
                return;
            } catch (RuntimeException e) {
                sleep(100);
            }
        }
    }

    // ======================================================================================
    // AC1: Terraform managed Valkey in ap-south-1: TLS required, at rest, replica in a 2nd AZ, private only
    // ======================================================================================

    @Test
    void ops020_ac1_terraformValkeyIsTlsOnlyEncryptedAtRestReplicatedAcrossTwoZonesAndPrivate() throws Exception {
        String tf = Files.readString(Path.of("../infra/terraform/modules/cell/aws/valkey.tf"));
        String rg = tf.substring(tf.indexOf("resource \"aws_elasticache_replication_group\""));
        assertThat(rg).containsPattern("engine\\s+=\\s+\"valkey\"");
        assertThat(rg).containsPattern("transit_encryption_enabled\\s+=\\s+true").containsPattern("transit_encryption_mode\\s+=\\s+\"required\"");
        assertThat(rg).containsPattern("at_rest_encryption_enabled\\s+=\\s+true").containsPattern("kms_key_id\\s+=");
        assertThat(rg).containsPattern("num_cache_clusters\\s+=\\s+2").containsPattern("multi_az_enabled\\s+=\\s+true")
                .containsPattern("automatic_failover_enabled\\s+=\\s+true").containsPattern("preferred_cache_cluster_azs\\s+=\\s+slice\\(var\\.azs, 0, 2\\)");
        assertThat(rg).containsPattern("auth_token\\s+=");
        assertThat(tf).containsPattern("subnet_ids\\s+=\\s+aws_subnet\\.private\\[\\*\\]\\.id");
        assertThat(tf).doesNotContain("0.0.0.0/0").doesNotContain("::/0").contains("cidr_blocks = [var.vpc_cidr]");
        String cell = Files.readString(Path.of("../infra/terraform/cells/in/main.tf"));
        assertThat(cell).contains("region            = \"ap-south-1\"").containsPattern("azs\\s+=\\s+\\[\"ap-south-1a\", \"ap-south-1b\"");
    }

    // ======================================================================================
    // AC2: DB statements per check-in <= 6 (engineer measured 17)
    // ======================================================================================

    @Test
    @Tag("known-defect-OPS-020")
    void ops020_ac2_aWarmWalkInCheckInSendsAtMostSixDatabaseStatements() throws Exception {
        U u = user();
        List<String> cold = countCheckIn(u);
        List<String> warm = countCheckIn(u);
        List<String> warm2 = countCheckIn(u);
        List<String> out = new ArrayList<>(List.of("cold=" + cold.size() + " warm=" + warm.size() + " warm2=" + warm2.size()));
        warm.forEach(s -> out.add("warm: " + (s.length() > 200 ? s.substring(0, 200) : s)));
        Files.write(Path.of("target", "qa7-ops020-statements.txt"), out);
        System.out.println("QA7 OPS-020 statements per check-in: " + out.get(0));
        assertThat(warm).as("no liveness or tenant-zone lookup on a warm check-in")
                .noneMatch(s -> s.contains("FROM sessions") || s.contains("timezone, region FROM tenants"));
        assertThat(warm.size()).as("DB statements per warm check-in (AC: <= 6)").isLessThanOrEqualTo(6);
    }

    private List<String> countCheckIn(U u) {
        String patientId = patient(u.token);
        SQL.clear();
        counting = true;
        ResponseEntity<Map> r;
        try {
            r = checkIn(u, patientId);
        } finally {
            counting = false;
        }
        assertThat(r.getStatusCode().value()).isEqualTo(201);
        return new ArrayList<>(SQL);
    }

    // ======================================================================================
    // AC3: revoked session (logout, device sign-out, break-glass end) refused on another replica within 1 s,
    // even when that replica had the session cached live; other sessions and other tenants unaffected
    // ======================================================================================

    @Test
    void ops020_ac3_revocationsAreRefusedOnAWarmReplicaWithinOneSecondAndNothingElseIsSignedOut() throws Exception {
        StringRedisTemplate tB = replicaTemplate(VALKEY.url());
        AtomicInteger dbB = new AtomicInteger();
        SessionRevocationJwtDecoder decoderB = decoderB(tB, dbB);

        U a = user();
        U other = user(); // another tenant
        String second = loginAndGetAccessToken(a.staff); // a second device of the same person
        for (String tok : List.of(a.token, second, other.token)) {
            decoderB.decode(tok);
            decoderB.decode(tok);
        }
        assertThat(valkey.opsForValue().get(key(sid(a.token)))).as("B cached it live").isEqualTo("1");

        // device sign-out of `second` from the first device
        long t0 = System.nanoTime();
        assertThat(exchange("/v1/auth/sessions/" + sid(second), HttpMethod.DELETE, authed(a.token), Void.class).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(msUntilRefused(decoderB, second, t0)).as("device sign-out -> refused on B, ms").isLessThan(1000);
        decoderB.decode(a.token); // the device that signed the other out still works

        // logout
        t0 = System.nanoTime();
        assertThat(exchange("/v1/auth/logout", HttpMethod.POST, authed(a.token), Void.class).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(msUntilRefused(decoderB, a.token, t0)).as("logout -> refused on B, ms").isLessThan(1000);
        assertThat(get(a.token)).isEqualTo(HttpStatus.UNAUTHORIZED);

        // break-glass end
        SeededStaff doc = seedStaff(a.tenant, seedBreakGlassDoctorRole(a.tenant.id()), mail(), phone(), false);
        String docTok = loginAndGetAccessToken(doc);
        ResponseEntity<Map> bg = activateBreakGlass(docTok, "Unresponsive patient brought in, no relative reachable yet", null);
        assertThat(bg.getStatusCode().is2xxSuccessful()).as("break-glass %s", bg.getBody()).isTrue();
        String bgTok = (String) bg.getBody().get("accessToken");
        decoderB.decode(bgTok);
        t0 = System.nanoTime();
        assertThat(exchange("/v1/auth/break-glass/" + bg.getBody().get("grantId") + "/deactivate", HttpMethod.POST,
                authedJsonBody(bgTok, Map.of()), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(msUntilRefused(decoderB, bgTok, t0)).as("break-glass end -> refused on B, ms").isLessThan(1000);

        // tenant isolation: the other tenant's session is untouched, on A and on B
        decoderB.decode(other.token);
        assertThat(get(other.token)).isEqualTo(HttpStatus.OK);
        assertThat(valkey.opsForValue().get(key(sid(other.token)))).isEqualTo("1");
    }

    /** Partial outage: the revoking pod cannot reach Valkey, the other pod can and still holds the live marker. */
    @Test
    void ops020_ac3_boundary_revocationOnAPodCutOffFromValkeyIsStillRefusedOnAnotherPodWithinOneSecond() throws Exception {
        StringRedisTemplate tB = replicaTemplate(VALKEY.url());
        SessionRevocationJwtDecoder decoderB = decoderB(tB, new AtomicInteger());
        U a = user();
        decoderB.decode(a.token);
        assertThat(valkey.opsForValue().get(key(sid(a.token)))).isEqualTo("1");
        int dead;
        try (ServerSocket s = new ServerSocket(0)) {
            dead = s.getLocalPort();
        }
        SessionLivenessCache cut = cache(replicaTemplate("redis://127.0.0.1:" + dead));
        UUID sid = sid(a.token);
        long t0 = System.nanoTime();
        // pod A's revocation: the DB write commits, the cache write fails (A queues it for its own next call)
        new JdbcTemplate(superuserDataSource()).update("UPDATE sessions SET revoked_at = now(), revoked_reason = 'logout' WHERE id = ?", sid);
        cut.revoked(List.of(sid));
        sleep(1000);
        boolean refused;
        try {
            decoderB.decode(a.token);
            refused = false;
        } catch (BadJwtException e) {
            refused = true;
        }
        System.out.println("QA7 OPS-020 partial outage: refused on B after " + (System.nanoTime() - t0) / 1_000_000 + " ms = " + refused
                + ", marker=" + valkey.opsForValue().get(key(sid)) + " ttl=" + valkey.getExpire(key(sid)));
        assertThat(refused).as("revoked session refused on another replica within 1 s").isTrue();
    }

    // ======================================================================================
    // AC5: Valkey down -> DB fallback (fail closed), check-in works without hanging, alert input + ERROR line
    // ======================================================================================

    @Test
    void ops020_ac5_valkeyDownFailsClosedCheckInWorksPromptlyAndTheFallbackAlertInputMoves() throws Exception {
        U a = user();
        String doomed = loginAndGetAccessToken(a.staff);
        String patientId = patient(a.token);
        assertThat(get(doomed)).isEqualTo(HttpStatus.OK);
        assertThat(get(a.token)).isEqualTo(HttpStatus.OK);
        assertThat(valkey.opsForValue().get(key(sid(doomed)))).as("cached live before the outage").isEqualTo("1");
        double fallbacks = counter("nabd.session_cache.fallback");
        VALKEY.stop();
        try {
            assertThat(exchange("/v1/auth/logout", HttpMethod.POST, authed(doomed), Void.class).getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(get(doomed)).as("revoked during the outage: refused (DB)").isEqualTo(HttpStatus.UNAUTHORIZED);
            long t0 = System.nanoTime();
            ResponseEntity<Map> ci = checkIn(a, patientId);
            long ciMs = (System.nanoTime() - t0) / 1_000_000;
            assertThat(ci.getStatusCode().value()).as("check-in with Valkey down").isEqualTo(201);
            List<Long> gets = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                long g = System.nanoTime();
                assertThat(get(a.token)).isEqualTo(HttpStatus.OK);
                gets.add((System.nanoTime() - g) / 1_000_000);
            }
            System.out.println("QA7 OPS-020 Valkey down: check-in ms=" + ciMs + " GET ms=" + gets);
            assertThat(ciMs).as("check-in does not hang on Valkey").isLessThan(3000);
            assertThat(gets).allSatisfy(x -> assertThat(x).isLessThan(1500));
            assertThat(counter("nabd.session_cache.fallback")).isGreaterThan(fallbacks);
            assertThat(LOGS.list.stream().anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR
                    && e.getFormattedMessage().startsWith("session revocation cache unavailable"))).isTrue();
            assertThat(scrape()).contains("nabd_session_cache_fallback_total");
        } finally {
            VALKEY.restart();
        }
        for (int i = 0; i < 60; i++) { // reconnect
            sleep(250);
            assertThat(get(a.token)).isEqualTo(HttpStatus.OK);
            assertThat(get(doomed)).as("still refused after Valkey is back").isEqualTo(HttpStatus.UNAUTHORIZED);
            try {
                if ("1".equals(valkey.opsForValue().get(key(sid(a.token))))) {
                    break;
                }
            } catch (RuntimeException reconnecting) {
                // keep polling
            }
        }
        String rule = Files.readString(Path.of("../infra/observability/session-cache.rules.yml"));
        assertThat(rule).contains("NabdSessionCacheFallback").contains("nabd_session_cache_fallback_total");
    }

    // ======================================================================================
    // AC6: no cached value in logs or metrics (keys may appear)
    // ======================================================================================

    @Test
    void ops020_ac6_cachedZoneAndSessionValuesNeverReachLogsOrTheMetricsScrape() throws Exception {
        U a = user();
        String zone = "Pacific/Marquesas"; // distinctive value to look for
        ResponseEntity<Map> patch = exchange("/v1/setup/profile", HttpMethod.PATCH,
                authedJsonBody(a.token, Map.of("name", "Qa7 Clinic", "timezone", zone)), Map.class);
        assertThat(patch.getStatusCode().value()).isEqualTo(200);
        clinicClock.evict(a.tenant.id());
        assertThat(clinicClock.zone(a.tenant.id()).getId()).isEqualTo(zone); // now a cached value
        checkIn(a, patient(a.token));
        get(a.token);
        String logs = String.join("\n", LOGS.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        String scrape = scrape();
        assertThat(logs).as("cached zone in logs").doesNotContain(zone);
        assertThat(scrape).as("cached zone in metrics").doesNotContain(zone).doesNotContain("Marquesas");
        assertThat(scrape).as("no session id as a metric tag").doesNotContain(sid(a.token).toString());
    }

    // ---------- helpers ----------

    record U(SeededTenant tenant, SeededStaff staff, String token) {
    }

    private SessionRevocationJwtDecoder decoderB(StringRedisTemplate tB, AtomicInteger dbChecks) {
        SessionLivenessChecker counting = new SessionLivenessChecker(null, null, null) {
            @Override
            boolean isActive(UUID tenantId, UUID sessionId) {
                dbChecks.incrementAndGet();
                return checker.isActive(tenantId, sessionId);
            }
        };
        return new SessionRevocationJwtDecoder(accessTokenDecoder, counting, cache(tB));
    }

    private SessionLivenessCache cache(StringRedisTemplate t) {
        return new SessionLivenessCache(new StaticListableBeanFactory(Map.of("b", t)).getBeanProvider(StringRedisTemplate.class),
                keys, new SimpleMeterRegistry(), authProperties, Clock.systemUTC(), jdbc);
    }

    static StringRedisTemplate replicaTemplate(String url) {
        LettuceConnectionFactory f = new ValkeyConfig().valkeyConnectionFactory(new ValkeyProperties(url, 500, 250), new MockEnvironment());
        f.afterPropertiesSet();
        f.start();
        FACTORIES.add(f);
        return new StringRedisTemplate(f);
    }

    private long msUntilRefused(SessionRevocationJwtDecoder decoderB, String token, long t0) {
        while (System.nanoTime() - t0 < 3_000_000_000L) {
            try {
                decoderB.decode(token);
            } catch (BadJwtException e) {
                return (System.nanoTime() - t0) / 1_000_000;
            }
            sleep(10);
        }
        throw new AssertionError("still accepted on replica B after 3 s");
    }

    private UUID sid(String token) {
        return UUID.fromString(accessTokenDecoder.decode(token).getClaimAsString("sid"));
    }

    private static String key(UUID sid) {
        return "nabd:sess:g:" + sid;
    }

    private HttpStatus get(String token) {
        return (HttpStatus) exchange("/v1/auth/sessions", HttpMethod.GET, authed(token), String.class).getStatusCode();
    }

    private double counter(String name) {
        Counter c = meters.find(name).counter();
        return c == null ? 0 : c.count();
    }

    private String scrape() throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + mgmtPort + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    static String uniqueLetters() {
        StringBuilder b = new StringBuilder();
        for (char c : UUID.randomUUID().toString().replace("-", "").substring(0, 10).toCharArray()) {
            b.append((char) ('a' + Character.digit(c, 16)));
        }
        return b.toString();
    }

    private static String phone() {
        return "+9198" + String.format("%08d", Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000_000L));
    }

    private static String mail() {
        return "qa7-" + UUID.randomUUID().toString().substring(0, 8) + "@qa7.test";
    }

    private U user() {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), mail(), phone(), false);
        return new U(t, s, loginAndGetAccessToken(s));
    }

    private String patient(String token) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Qa Seven " + uniqueLetters(), "phone", phone(), "dob", java.time.LocalDate.of(1950, 1, 1)
                        .plusDays(Math.abs(UUID.randomUUID().getLeastSignificantBits() % 20000)).toString(), "gender", "female")), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("patient: %s %s", r.getStatusCode(), r.getBody()).isTrue();
        return (String) r.getBody().get("id");
    }

    private ResponseEntity<Map> checkIn(U u, String patientId) {
        return exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(u.token, Map.of(
                "patientId", patientId, "doctorId", u.staff.id().toString())), Map.class);
    }
}

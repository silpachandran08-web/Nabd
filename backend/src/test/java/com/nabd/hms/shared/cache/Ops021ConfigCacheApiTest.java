package com.nabd.hms.shared.cache;

import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.common.TenantConfigEvictions;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.Ops020Replica;
import com.nabd.hms.common.valkey.ValkeyConfig;
import com.nabd.hms.common.valkey.ValkeyProperties;
import com.nabd.hms.shared.relay.OutboxRelay;
import com.nabd.hms.shared.relay.RelayProperties;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ValkeyTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OPS-021: versioned L1/L2 config cache. "Instance B" is a second ConfigCache + TenantConfigEvictions on its own Lettuce
 * client against the one embedded server (the only thing instances share in production).
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class Ops021ConfigCacheApiTest extends ApiTestBase {

    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();
    static final List<LettuceConnectionFactory> FACTORIES = new ArrayList<>();
    static final ConfigCache.Domain CAT = ConfigCache.Domain.CATALOGUE;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.valkey.url", VALKEY::url);
        r.add("app.cache.config-ttl-seconds", () -> "300");
        r.add("app.rate-limit.user-per-minute", () -> "100000");
        r.add("app.rate-limit.user-burst", () -> "100000");
        r.add("app.rate-limit.ip-per-minute", () -> "100000");
        r.add("app.rate-limit.ip-burst", () -> "100000");
    }

    @AfterAll
    static void stop() {
        FACTORIES.forEach(LettuceConnectionFactory::destroy);
        VALKEY.close();
    }

    @Autowired ConfigCache cache;
    @Autowired TenantConfigEvictions evictions;
    @Autowired CacheKeyPolicy keys;
    @Autowired ObjectMapper json;
    @Autowired TenantScope tenantScope;
    @Autowired StringRedisTemplate valkey;
    @Autowired ClinicClock clinicClock;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired Environment env;

    // ---------- helpers ----------

    static StringRedisTemplate template(ValkeyTestSupport server) {
        LettuceConnectionFactory f = new ValkeyConfig().valkeyConnectionFactory(new ValkeyProperties(server.url(), 500, 250), new MockEnvironment());
        f.afterPropertiesSet();
        f.start();
        FACTORIES.add(f);
        return new StringRedisTemplate(f);
    }

    static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        return new StaticListableBeanFactory(Map.of("b", bean)).getBeanProvider(type);
    }

    ConfigCache instance(StringRedisTemplate t, long l1Ms) {
        return instance(t, l1Ms, 3_600_000);
    }

    ConfigCache instance(StringRedisTemplate t, long l1Ms, long l2Ms) {
        return new ConfigCache(provider(StringRedisTemplate.class, t), keys, json, tenantScope, java.time.Duration.ofMillis(l1Ms), java.time.Duration.ofMillis(l2Ms));
    }

    private static Map<String, Object> row(String v) {
        return Map.of("v", v);
    }

    // ---------- AC1: two instances, a price change, within 5 s, checkout uses it ----------

    private static final AtomicInteger PHONES = new AtomicInteger((int) (System.nanoTime() % 5_000_000));
    private int seq;

    private String phone() {
        return "+91971" + String.format("%07d", 1_000_000 + PHONES.getAndIncrement());
    }

    private static String uniqueLetters() {
        StringBuilder b = new StringBuilder();
        for (char c : UUID.randomUUID().toString().replace("-", "").toCharArray()) {
            b.append(Character.isDigit(c) ? (char) ('g' + (c - '0')) : c);
        }
        return b.toString();
    }

    private String pendingEntry(String token, UUID doctor) {
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Cfg " + uniqueLetters(), "phone", phone(), "dob", java.time.LocalDate.of(1940, 1, 1).plusDays(seq++ * 997L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 9000)).toString(), "gender", "male")), Map.class);
        assertThat(p.getStatusCode().is2xxSuccessful()).as("patient: %s %s", p.getStatusCode(), p.getBody()).isTrue();
        String patient = (String) p.getBody().get("id");
        String id = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patient, "doctorId", doctor.toString())), Map.class).getBody().get("id");
        for (String s : new String[] {"waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending"}) {
            exchange("/v1/queue/" + id + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", s)), Map.class);
        }
        return id;
    }

    private double checkoutPrice(String token, UUID doctor, String code) {
        ResponseEntity<Map> r = exchange("/v1/billing/checkout/" + pendingEntry(token, doctor), HttpMethod.POST,
                authedJsonBody(token, Map.of("lineItems", List.of(Map.of("chargeCode", code, "quantity", 1)))), Map.class);
        assertThat(r.getStatusCode()).as("checkout: %s", r.getBody()).isEqualTo(HttpStatus.OK);
        return ((Number) ((Map) ((List) r.getBody().get("lineItems")).get(0)).get("unitPrice")).doubleValue();
    }

    @Test
    void priceChangeReachesEveryInstanceWithinFiveSecondsAndCheckoutUsesIt() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "own" + t.id() + "@cfg.test", phone(), false);
        String token = loginAndGetAccessToken(owner);
        ResponseEntity<Map> created = exchange("/v1/setup/charges", HttpMethod.POST, authedJsonBody(token, Map.of(
                "code", "CONS", "name", "Consultation", "category", "Consultation", "baseAmount", 400,
                "effectiveFrom", "2026-01-01", "active", true)), Map.class);
        assertThat(created.getStatusCode()).as("charge: %s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        String chargeId = (String) created.getBody().get("id");

        // instance B (its own client, cache and subscription); A is this context
        StringRedisTemplate tB = template(VALKEY);
        ConfigCache cacheB = instance(tB, 300_000);
        TenantConfigEvictions evictionsB = new TenantConfigEvictions(Ops020Replica.clinicClock(jdbc, keys, 30), provider(StringRedisTemplate.class, tB),
                provider(RedisConnectionFactory.class, tB.getConnectionFactory()), cacheB);
        evictionsB.ensureSubscribed();
        evictions.ensureSubscribed();

        assertThat(checkoutPrice(token, owner.id(), "CONS")).isEqualTo(400.0); // A caches the row
        // the checkout row is in A's L1 and Valkey; B reads it through L2 (B's loader must not run)
        Map<String, Object> seenByB = cacheB.get(t.id(), CAT, "CONS", Map.class, () -> {
            throw new AssertionError("B should find CONS in L2");
        });
        assertThat(((Number) seenByB.get("baseAmount")).doubleValue()).isEqualTo(400.0);

        ResponseEntity<Map> patch = exchange("/v1/setup/charges/" + chargeId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "code", "CONS", "name", "Consultation", "category", "Consultation", "baseAmount", 450,
                "effectiveFrom", "2026-01-01", "active", true)), Map.class);
        assertThat(patch.getStatusCode()).as("price change: %s", patch.getBody()).isEqualTo(HttpStatus.OK);
        // committed, not consumed yet: the event (after commit) is what moves the instances
        assertThat(checkoutPrice(token, owner.id(), "CONS")).as("before the cache-invalidator consumed the event").isEqualTo(400.0);

        dispatcher.poll(); // as in production: with Valkey the relay owns domain rows, so this must not be what moves the price
        long t0 = System.nanoTime();
        OutboxRelay relay = new OutboxRelay(jdbc, valkey, new RelayProperties(true, 500, 10, 2000, 2000, 1000, 1_000_000, null, null, null),
                OutboxRelay.direct(env.getProperty("spring.datasource.url"), env.getProperty("spring.datasource.username"),
                        env.getProperty("spring.datasource.password")), new SimpleMeterRegistry());
        relay.start();
        try {
            String verKey = "nabd:cfg:t:" + t.id() + ":catalogue:ver";
            while (!"1".equals(valkey.opsForValue().get(verKey)) && System.nanoTime() - t0 < 10_000_000_000L) {
                Thread.sleep(20);
            }
            assertThat(valkey.opsForValue().get(verKey)).as("version bumped by the consumer").isEqualTo("1");
            // A serves the new price through checkout; B through its own cache (L2 miss on v1 -> its loader reads the DB)
            assertThat(checkoutPrice(token, owner.id(), "CONS")).isEqualTo(450.0);
            Map<String, Object> afterB = cacheB.get(t.id(), CAT, "CONS", Map.class, () -> Map.of("baseAmount", jdbcPrice(t.id())));
            assertThat(((Number) afterB.get("baseAmount")).doubleValue()).isEqualTo(450.0);
        } finally {
            relay.stop();
        }
        assertThat((System.nanoTime() - t0) / 1_000_000).as("consumed -> both instances on the new price, ms").isLessThan(5000);
    }

    private BigDecimal jdbcPrice(UUID tenant) {
        return inTenantTx(tenant, () -> jdbc.queryForObject("SELECT base_amount FROM charge_catalogue WHERE tenant_id = ? AND code = 'CONS'", BigDecimal.class, tenant));
    }

    // ---------- AC2: stampede ----------

    @Test
    void twoHundredConcurrentColdReadsLoadOncePerInstanceAndOnceAcrossInstances() throws Exception {
        UUID tenant = UUID.randomUUID();
        ConfigCache a = instance(template(VALKEY), 300_000);
        ConfigCache b = instance(template(VALKEY), 300_000);
        AtomicInteger loads = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(200);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> out = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ConfigCache c = i % 2 == 0 ? a : b;
            out.add(pool.submit(() -> {
                go.await();
                return c.get(tenant, CAT, "COLD", Map.class, () -> {
                    loads.incrementAndGet();
                    sleep(300);
                    return row("cold");
                });
            }));
        }
        go.countDown();
        for (Future<Object> f : out) {
            assertThat(((Map) f.get(20, TimeUnit.SECONDS)).get("v")).isEqualTo("cold");
        }
        pool.shutdownNow();
        assertThat(loads.get()).as("DB loads for 200 concurrent cold reads on 2 instances").isEqualTo(1);
    }

    @Test
    void singleFlightWithoutValkeyLoadsOncePerInstance() throws Exception {
        ConfigCache solo = new ConfigCache(emptyProvider(), keys, json, tenantScope, java.time.Duration.ofMinutes(5), java.time.Duration.ofHours(1));
        UUID tenant = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(200);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> out = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            out.add(pool.submit(() -> {
                go.await();
                return solo.get(tenant, CAT, "COLD", Map.class, () -> {
                    loads.incrementAndGet();
                    sleep(200);
                    return row("cold");
                });
            }));
        }
        go.countDown();
        for (Future<Object> f : out) {
            f.get(20, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertThat(loads.get()).isEqualTo(1);
    }

    private ObjectProvider<StringRedisTemplate> emptyProvider() {
        return new StaticListableBeanFactory(Map.of()).getBeanProvider(StringRedisTemplate.class);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- AC3: tenant id in every key; no raw key construction ----------

    @Test
    void everyKeyContainsTheTenantIdAndTenantsDoNotShareValues() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        ConfigCache c = instance(template(VALKEY), 300_000);
        assertThat(c.get(t1, CAT, "SAMECODE", Map.class, () -> row("one")).get("v")).isEqualTo("one");
        assertThat(c.get(t2, CAT, "SAMECODE", Map.class, () -> row("two")).get("v")).isEqualTo("two");
        assertThat(c.get(t1, CAT, "SAMECODE", Map.class, () -> row("never"))).containsEntry("v", "one");
        Set<String> mine = new java.util.HashSet<>(valkey.keys("nabd:cfg:*SAMECODE*"));
        assertThat(mine).hasSize(2).allMatch(k -> k.contains(":t:" + t1) || k.contains(":t:" + t2));
        assertThat(keys.key("cfg", t1, CAT.sourceColumns(), false, "catalogue", "X", "v0")).contains(t1.toString());
        assertThatThrownBy(() -> keys.key("cfg", null, CAT.sourceColumns(), false, "catalogue", "X"))
                .isInstanceOf(CacheKeyPolicy.CacheRefusedException.class); // financial prices need a tenant key
    }

    @Test
    void noRawCacheKeysOrSpringCacheAnnotationsOutsideSharedCache() throws Exception {
        Path root = Path.of("src", "main", "java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                String rel = root.relativize(f).toString().replace('\\', '/');
                if (!rel.startsWith("com/nabd/hms/shared/cache/") && !rel.endsWith("common/TenantConfigEvictions.java")) {
                    assertThat(src).as("raw nabd:cfg key in %s", rel).doesNotContain("nabd:cfg");
                }
                assertThat(src).as("Spring cache annotation / unmanaged cache in %s", rel)
                        .doesNotContain("@Cacheable").doesNotContain("@CachePut").doesNotContain("@CacheEvict").doesNotContain("@Caching");
            }
        }
    }

    // ---------- AC4: PHI default-deny ----------

    /** Data classes ADR-04 forbids in a cache: allergies, active meds, pregnancy, stock at dispense, slot at booking, invoice totals, break-glass. */
    static final List<String> FORBIDDEN = List.of("allerg", "medication", "prescription", "pregnan", "stock", "slot", "appointment",
            "invoice", "break_glass", "breakglass", "total");

    @Test
    void phiDefaultDenyRegistryRefusesForbiddenDataClasses() {
        for (ConfigCache.Domain d : ConfigCache.Domain.values()) {
            assertThat(d.sourceColumns()).as("domain %s declares its source columns", d).isNotEmpty();
            for (String col : d.sourceColumns()) {
                assertThat(keys.tierOf(col)).as("%s.%s must be classified and not PHI", d, col).isNotEqualTo(DataTier.PHI);
                assertThat(FORBIDDEN).as("%s.%s is a forbidden data class", d, col).noneMatch(col::contains);
            }
            keys.key("cfg", UUID.randomUUID(), d.sourceColumns(), false, d.name().toLowerCase(), "x"); // must not throw
        }
        // the guard itself: forbidden/unclassified sources are PHI by default and refused without an explicit allow
        for (String col : List.of("allergies.substance", "prescription_items.drug_name", "patients.pregnancy_status",
                "appointments.cancel_reason", "break_glass_grants.reason", "charge_catalogue.stock_qty_at_dispense")) {
            assertThatThrownBy(() -> keys.key("cfg", UUID.randomUUID(), List.of(col), false, "d", "x")).as(col)
                    .isInstanceOf(CacheKeyPolicy.CacheRefusedException.class);
        }
        // invoice totals are classified financial (cacheable by tier), so the registry scan above is what keeps them out
        assertThat(FORBIDDEN).anyMatch("invoices.total"::contains);
        assertThat(FORBIDDEN).anyMatch("appointments.start_time"::contains);
        assertThat(FORBIDDEN).anyMatch("charge_catalogue.stock_qty"::contains);
    }

    // ---------- AC5: a pre-commit value written to L2 by a race is unreachable after the bump ----------

    @Test
    void staleValueRepopulatedByARaceIsUnreachableAfterTheVersionBump() throws Exception {
        UUID tenant = UUID.randomUUID();
        StringRedisTemplate tA = template(VALKEY);
        ConfigCache c = instance(tA, 300_000);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        // reader read the OLD row from the DB (pre-commit), is slow ...
        Future<Map> slow = pool.submit(() -> c.get(tenant, CAT, "RACE", Map.class, () -> {
            loaded.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return row("old");
        }));
        assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
        // ... the change commits and the consumer bumps the version ...
        c.invalidate(tenant, CAT);
        // ... then the slow reader writes its stale value to L2 (under the version it started with)
        release.countDown();
        assertThat(slow.get(5, TimeUnit.SECONDS)).containsEntry("v", "old");
        pool.shutdownNow();
        String prefix = "nabd:cfg:t:" + tenant + ":catalogue:RACE:";
        assertThat(valkey.opsForValue().get(prefix + "v0")).as("the race did repopulate L2 under the old version").contains("old");
        assertThat(valkey.opsForValue().get("nabd:cfg:t:" + tenant + ":catalogue:ver")).isEqualTo("1");
        // the stale value is unreachable: neither L1 (epoch) nor L2 (version) serves it
        assertThat(c.get(tenant, CAT, "RACE", Map.class, () -> row("new"))).containsEntry("v", "new");
        // another instance reaches the same answer through L2 v1
        ConfigCache other = instance(template(VALKEY), 300_000);
        assertThat(other.get(tenant, CAT, "RACE", Map.class, () -> row("must-not-load"))).containsEntry("v", "new");
    }

    // ---------- NFR: jitter, soft/hard TTL, L1 speed, Valkey down ----------

    @Test
    void ttlJitterStaysWithinTenPercentAndVaries() {
        Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < 2000; i++) {
            long n = ConfigCache.jitterNanos(1000);
            assertThat(n).isBetween(900_000_000L, 1_100_000_000L);
            seen.add(n);
        }
        assertThat(seen.size()).isGreaterThan(100);
    }

    @Test
    void softAgeServesTheCachedValueAndRefreshesInTheBackgroundHardAgeReloadsInline() throws Exception {
        SeededTenant t = seedTenant();
        ConfigCache c = instance(template(VALKEY), 1000, 300); // L1 soft ~500 ms, hard ~1000 ms; L2 gone after ~300 ms (+/-10%)
        AtomicInteger n = new AtomicInteger();
        java.util.function.Supplier<Map> loader = () -> row("gen" + n.incrementAndGet());
        assertThat(c.get(t.id(), CAT, "TTL", Map.class, loader)).containsEntry("v", "gen1");
        Thread.sleep(650); // past soft (<= 550), before hard (>= 900)
        assertThat(c.get(t.id(), CAT, "TTL", Map.class, loader)).as("soft age: still the cached value, no inline load").containsEntry("v", "gen1");
        long end = System.nanoTime() + 5_000_000_000L;
        while (n.get() < 2 && System.nanoTime() < end) {
            Thread.sleep(20);
        }
        assertThat(n.get()).as("background refresh ran once").isEqualTo(2);
        assertThat(c.get(t.id(), CAT, "TTL", Map.class, loader)).as("refreshed value is served").containsEntry("v", "gen2");
        Thread.sleep(1300); // past hard
        assertThat(c.get(t.id(), CAT, "TTL", Map.class, loader)).as("hard age: loaded inline").containsEntry("v", "gen3");
    }

    @Test
    void l1HitP99IsUnderOneMillisecond() {
        UUID tenant = UUID.randomUUID();
        ConfigCache c = instance(template(VALKEY), 300_000);
        c.get(tenant, CAT, "FAST", Map.class, () -> row("x"));
        long[] ns = new long[20_000];
        for (int i = 0; i < ns.length; i++) {
            long s = System.nanoTime();
            c.get(tenant, CAT, "FAST", Map.class, () -> row("x"));
            ns[i] = System.nanoTime() - s;
        }
        java.util.Arrays.sort(ns);
        assertThat(ns[(int) (ns.length * 0.99)]).as("p99 ns of an L1 hit").isLessThan(1_000_000L);
    }

    @Test
    void valkeyDownAnswersFromTheLoaderAndCachesNothing() {
        ValkeyTestSupport own = ValkeyTestSupport.start();
        try {
            ConfigCache c = instance(template(own), 300_000);
            UUID tenant = UUID.randomUUID();
            AtomicInteger loads = new AtomicInteger();
            assertThat(c.get(tenant, CAT, "DOWN", Map.class, () -> row("a" + loads.incrementAndGet()))).containsEntry("v", "a1");
            own.stop();
            assertThat(c.get(tenant, CAT, "DOWN2", Map.class, () -> row("b" + loads.incrementAndGet()))).containsEntry("v", "b2");
            assertThat(c.get(tenant, CAT, "DOWN2", Map.class, () -> row("b" + loads.incrementAndGet()))).as("not cached while Valkey is down").containsEntry("v", "b3");
            assertThatThrownBy(() -> c.get(tenant, CAT, "DOWN3", Map.class, () -> {
                throw new IllegalStateException("db failed");
            })).as("a loader failure is the loader's, not hidden as a Valkey fallback").isInstanceOf(IllegalStateException.class);
        } finally {
            own.close();
        }
    }

    @Test
    void namesThatAreNotPlainIdsAreNeverCached() {
        UUID tenant = UUID.randomUUID();
        AtomicInteger loads = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            cache.get(tenant, CAT, "has space:and colon", Map.class, () -> row("x" + loads.incrementAndGet()));
        }
        assertThat(loads.get()).isEqualTo(2);
    }

    // ---------- pharmacy price/active edits are catalogue changes too ----------

    @Test
    void pharmacyItemAddPriceEditAndDeactivateEachPublishCatalogueChanged() {
        SeededTenant t = seedTenant();
        String token = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "ph" + t.id() + "@cfg.test", phone(), false));
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "OPS-021 test")), Map.class);
        ResponseEntity<Map> item = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Cfg cleanser", "isRx", false, "hsnCode", "3304", "price", 350.00, "taxRatePercent", 12.00, "stockQty", 0)), Map.class);
        assertThat(item.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) item.getBody().get("id");
        assertThat(exchange("/v1/pharmacy/items/" + id, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "name", "Cfg cleanser", "isRx", false, "hsnCode", "3304", "price", 375.00, "taxRatePercent", 12.00)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/pharmacy/items/" + id + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "active", false, "reason", "discontinued")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> statuses = inTenantTx(t.id(), () -> jdbc.queryForList(
                "SELECT payload->>'status' FROM outbox_events WHERE tenant_id = ? AND event_type = 'billing.catalogue.changed.v1' ORDER BY id",
                String.class, t.id()));
        assertThat(statuses).containsExactly("added", "updated", "updated");
    }
}

package com.nabd.hms.shared.ratelimit;

import com.nabd.hms.common.valkey.ValkeyConfig;
import com.nabd.hms.common.valkey.ValkeyProperties;
import com.nabd.hms.support.ValkeyTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S12 QA-2, PLT-008 AC2 sandbox approximation (the k6 run on two real replicas behind a load balancer is NOT-RUNNABLE
 * here): two independent limiter instances with their own Lettuce connections ("replicas") share one Redis 6.2.
 * A sustained, rate-limited load over several seconds checks refill accuracy, not just the burst. Plus static checks
 * of the shipped config (AC4 alert timing, AC5 refresh window <= 60 s).
 */
@SuppressWarnings("unchecked")
class S12QaRateLimitReplicaTest {

    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();
    static final List<LettuceConnectionFactory> FACTORIES = new ArrayList<>();
    static TokenBuckets replicaA;
    static TokenBuckets replicaB;

    @BeforeAll
    static void up() {
        replicaA = replica();
        replicaB = replica();
    }

    static TokenBuckets replica() {
        LettuceConnectionFactory f = new ValkeyConfig().valkeyConnectionFactory(new ValkeyProperties(VALKEY.url(), 500, 250), new MockEnvironment());
        f.afterPropertiesSet();
        f.start();
        FACTORIES.add(f);
        return new TokenBuckets(new StaticListableBeanFactory(Map.of("t", new StringRedisTemplate(f))).getBeanProvider(StringRedisTemplate.class));
    }

    @AfterAll
    static void down() {
        FACTORIES.forEach(LettuceConnectionFactory::destroy);
        VALKEY.close();
    }

    /** Time-compressed AC2: the story's 120/min user limit driven at 2.5x (300/min) split over two replicas. Here 1,200/min
     * (20/s) with burst 20, driven at 50/s for 6 s across both: expected allowed = 20 + 20 * 6 = 140, within 5 %. */
    @Test
    void ac2_userLimitHoldsWithin5PercentUnderSustainedLoadSplitAcrossTwoReplicas() throws Exception {
        String key = "rl:user:{" + UUID.randomUUID() + "}:qa2";
        TokenBuckets.Bucket b = new TokenBuckets.Bucket(key, 1200, 20);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        long t0 = System.nanoTime();
        List<Future<int[]>> parts = new ArrayList<>();
        for (TokenBuckets replica : List.of(replicaA, replicaB)) {
            parts.add(pool.submit(() -> {
                int ok = 0, sent = 0;
                for (int i = 0; i < 150; i++) { // 25/s per replica for 6 s
                    long due = t0 + i * 40_000_000L;
                    long wait = due - System.nanoTime();
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                    if (replica.take(List.of(b)).allowed()) ok++;
                    sent++;
                }
                return new int[] {ok, sent};
            }));
        }
        int allowed = 0, sent = 0;
        for (Future<int[]> f : parts) {
            int[] r = f.get();
            allowed += r[0];
            sent += r[1];
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        pool.shutdown();
        double expected = 20 + 20 * seconds;
        System.out.printf("QA2 PLT-008 two replicas: sent=%d allowed=%d expected=%.1f over %.2f s%n", sent, allowed, expected, seconds);
        assertThat(sent).isEqualTo(300);
        assertThat((double) allowed).isBetween(expected * 0.95, expected * 1.05);
    }

    @Test
    void ac1_fullScaleTenantBucketAllowsExactly1200InABurstAcrossReplicasThenRetryAfter() {
        String key = "rl:tenant:{" + UUID.randomUUID() + "}";
        TokenBuckets.Bucket b = new TokenBuckets.Bucket(key, 600, 1200);
        long t0 = System.nanoTime();
        int allowed = 0;
        TokenBuckets.Decision d;
        while ((d = (allowed % 2 == 0 ? replicaA : replicaB).take(List.of(b))).allowed()) {
            allowed++;
            assertThat(allowed).isLessThan(1500);
        }
        double s = (System.nanoTime() - t0) / 1e9;
        assertThat(allowed).isBetween(1200, 1200 + (int) Math.ceil(s * 10) + 1);
        assertThat(d.retryAfterMs()).isBetween(1L, 100L); // 600/min = one token every 100 ms
        // another tenant is untouched by that flood
        assertThat(replicaB.take(List.of(new TokenBuckets.Bucket("rl:tenant:{" + UUID.randomUUID() + "}", 600, 1200))).allowed()).isTrue();
    }

    @Test
    void ac4ac5_shippedConfigRefreshesPlansWithin60sAndTheFailOpenAlertFiresWithin2Minutes() throws Exception {
        Map<String, Object> yml = new Yaml().load(Files.readString(Path.of("src/main/resources/application.yml")));
        Map<String, Object> rl = (Map<String, Object>) ((Map<String, Object>) yml.get("app")).get("rate-limit");
        assertThat(((Number) rl.get("plan-refresh-seconds")).intValue()).isBetween(1, 60);
        assertThat(rl).containsEntry("tenant-per-minute", 600).containsEntry("tenant-burst", 1200)
                .containsEntry("user-per-minute", 120).containsEntry("ip-per-minute", 20);

        Map<String, Object> rules = new Yaml().load(Files.readString(Path.of("../infra/observability/rate-limit.rules.yml")));
        List<Map<String, Object>> groups = (List<Map<String, Object>>) rules.get("groups");
        Map<String, Object> failOpen = groups.stream().flatMap(g -> ((List<Map<String, Object>>) g.get("rules")).stream())
                .filter(r -> "NabdRateLimitFailOpen".equals(r.get("alert"))).findFirst().orElseThrow();
        assertThat(String.valueOf(failOpen.get("expr"))).contains("nabd_rate_limit_fail_open_total");
        String forClause = String.valueOf(failOpen.get("for"));
        assertThat(forClause).matches("0m|0s|[0-5]?[0-9]s|1m");
        assertThat(String.valueOf(failOpen.get("expr"))).matches(".*\\[(1m|[0-9]{1,2}s)\\].*"); // window short enough to fire < 2 min
    }
}

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-008: the Valkey token buckets themselves, against a real Redis 6.2 server. Two TokenBuckets on two separate
 * connections stand in for two API replicas behind the load balancer (one shared Valkey).
 */
class TokenBucketsTest {

    static final ValkeyTestSupport VALKEY = ValkeyTestSupport.start();
    static final List<LettuceConnectionFactory> FACTORIES = new ArrayList<>();
    static TokenBuckets replicaA;
    static TokenBuckets replicaB;

    @BeforeAll
    static void replicas() {
        replicaA = replica();
        replicaB = replica();
    }

    static TokenBuckets replica() {
        // the production connection settings (short timeouts, reject while disconnected)
        LettuceConnectionFactory f = new ValkeyConfig().valkeyConnectionFactory(new ValkeyProperties(VALKEY.url(), 500, 250), new MockEnvironment());
        f.afterPropertiesSet();
        f.start();
        FACTORIES.add(f);
        StringRedisTemplate t = new StringRedisTemplate(f);
        return new TokenBuckets(new StaticListableBeanFactory(Map.of("t", t)).getBeanProvider(StringRedisTemplate.class));
    }

    @AfterAll
    static void down() {
        FACTORIES.forEach(LettuceConnectionFactory::destroy);
        VALKEY.close();
    }

    private static String key() {
        return "rl:test:{" + UUID.randomUUID() + "}";
    }

    @Test
    void twoReplicasSharingOneUserHoldTheUserLimitWithin5Percent() throws Exception {
        // 300 requests from one user split across both replicas, concurrently; the user limit is 120/min (bucket 120)
        String key = key();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        long t0 = System.nanoTime();
        List<Future<Integer>> parts = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            TokenBuckets replica = w % 2 == 0 ? replicaA : replicaB;
            parts.add(pool.submit((Callable<Integer>) () -> {
                int ok = 0;
                for (int i = 0; i < 75; i++) {
                    if (replica.take(List.of(new TokenBuckets.Bucket(key, 120, 120))).allowed()) ok++;
                }
                return ok;
            }));
        }
        int allowed = 0;
        for (Future<Integer> f : parts) allowed += f.get();
        pool.shutdown();
        double seconds = (System.nanoTime() - t0) / 1e9;
        System.out.printf("two replicas: %d of 300 allowed in %.2f s (limit 120, refill 2/s)%n", allowed, seconds);
        assertThat(seconds).isLessThan(3.0); // the refill during the run stays inside the 5 % band
        assertThat(allowed).isBetween(120, 126);
    }

    @Test
    void tenantBurstOf1200ThenDeniedWithRetryAndOtherTenantsUnaffected() {
        String tenantA = key();
        String tenantB = key();
        long t0 = System.nanoTime();
        int allowed = 0;
        TokenBuckets.Decision d;
        while ((d = (allowed % 2 == 0 ? replicaA : replicaB).take(List.of(new TokenBuckets.Bucket(tenantA, 600, 1200)))).allowed()) {
            allowed++;
            assertThat(allowed).isLessThan(2000);
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        assertThat(allowed).isBetween(1200, 1200 + (int) Math.ceil(seconds * 10) + 1); // 600/min = 10/s refill
        assertThat(d.denied()).isZero();
        assertThat(d.retryAfterMs()).isBetween(1L, 100L); // one token at 600/min is due within 100 ms
        assertThat(replicaA.take(List.of(new TokenBuckets.Bucket(tenantB, 600, 1200))).allowed()).isTrue();
    }

    @Test
    void allOrNothingAnEmptyUserBucketTakesNoTenantToken() {
        String tenant = key();
        String user = key();
        for (int i = 0; i < 2; i++) {
            assertThat(replicaA.take(List.of(new TokenBuckets.Bucket(tenant, 1, 5), new TokenBuckets.Bucket(user, 1, 2))).allowed()).isTrue();
        }
        TokenBuckets.Decision d = replicaB.take(List.of(new TokenBuckets.Bucket(tenant, 1, 5), new TokenBuckets.Bucket(user, 1, 2)));
        assertThat(d.denied()).isEqualTo(1); // the second bucket (user) was empty
        assertThat(d.retryAfterMs()).isGreaterThan(50_000L); // 1/min: the next token is ~60 s away
        // the tenant still has 3 of 5: three other users get through, the fourth does not
        for (int i = 0; i < 3; i++) {
            assertThat(replicaA.take(List.of(new TokenBuckets.Bucket(tenant, 1, 5), new TokenBuckets.Bucket(key(), 1, 2))).allowed()).isTrue();
        }
        assertThat(replicaA.take(List.of(new TokenBuckets.Bucket(tenant, 1, 5), new TokenBuckets.Bucket(key(), 1, 2))).denied()).isZero();
    }

    @Test
    void valkeyDownReturnsNullFastAndRecoversAfterRestart() throws Exception {
        TokenBuckets fresh = replica();
        String k = key();
        assertThat(fresh.take(List.of(new TokenBuckets.Bucket(k, 60, 60)))).isNotNull();
        VALKEY.stop();
        try {
            long t0 = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                assertThat(fresh.take(List.of(new TokenBuckets.Bucket(k, 60, 60)))).isNull();
            }
            assertThat((System.nanoTime() - t0) / 1e6).as("20 calls while down, ms").isLessThan(2000);
        } finally {
            VALKEY.restart();
        }
        TokenBuckets.Decision back = null;
        for (int i = 0; i < 60 && back == null; i++) {
            Thread.sleep(500);
            back = fresh.take(List.of(new TokenBuckets.Bucket(k, 60, 60)));
        }
        assertThat(back).as("limiter works again after Valkey restarts").isNotNull();
        assertThat(back.allowed()).isTrue();
    }
}

package com.nabd.hms.shared.cache;

import com.nabd.hms.common.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * OPS-021 (ADR-04 class C/D): tenant configuration (prices, ...) in a per-instance L1 map and Valkey L2 under VERSIONED keys
 * {@code nabd:cfg:t:<tenant>:<domain>:<name>:v<version>}. A change never edits a cached value: the cache-invalidator
 * consumer ({@code TenantConfigEvictions}), only after the change committed, INCRs the domain's version and broadcasts an
 * eviction, so a value a racing reader wrote under the old version is unreachable (new reads look under the new version).
 *
 * <ul>
 *   <li>L1 hit: no Valkey call. Soft age (half the L1 TTL): served, refreshed in the background. Hard age: reloaded inline.</li>
 *   <li>Stampede: one load per key per instance (single-flight future) and, through a SET NX lock on L2, one across instances.</li>
 *   <li>TTLs carry +/-10% jitter. Keys are built by {@link CacheKeyPolicy} (tenant id always in the key; PHI refused).</li>
 *   <li>Values are never logged. Valkey down: the loader (the DB) answers and nothing is cached.</li>
 * </ul>
 * Loaders and cached values must be immutable and free of request state: the soft refresh runs them on another thread.
 */
@Component
public class ConfigCache {

    /** A cached configuration domain with the columns its values are built from (checked against db/classification.yaml). */
    public enum Domain {
        CATALOGUE("billing.catalogue.changed.v1", List.of("charge_catalogue.code", "charge_catalogue.name", "charge_catalogue.category",
                "charge_catalogue.base_amount", "charge_catalogue.follow_up_amount", "charge_catalogue.tax_rate_percent",
                "charge_catalogue.active", "charge_catalogue.doctor_override", "charge_catalogue.is_rx"));

        private final String eventType;
        private final List<String> sourceColumns;

        Domain(String eventType, List<String> sourceColumns) {
            this.eventType = eventType;
            this.sourceColumns = sourceColumns;
        }

        public List<String> sourceColumns() {
            return sourceColumns;
        }

        /** The domain an event type invalidates, or null. */
        public static Domain forEvent(String type) {
            for (Domain d : values()) {
                if (d.eventType.equals(type)) {
                    return d;
                }
            }
            return null;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ConfigCache.class);
    private static final long LOCK_MS = 5_000;
    private static final long LOCK_WAIT_MS = 2_000;

    private record Entry(Object value, long softAt, long hardAt) {
    }

    /** The loader failed (its exception is the cause): not a Valkey problem, so it must not trigger the fallback. */
    private static final class LoaderFailed extends RuntimeException {
        LoaderFailed(RuntimeException cause) {
            super(cause);
        }
    }

    private final ObjectProvider<StringRedisTemplate> valkey;
    private final CacheKeyPolicy keys;
    private final ObjectMapper json;
    private final TenantScope tenants;
    private final long l1Ms;
    private final long l2Ms;
    /** shortcut: unbounded by entry count (one entry per existing charge code per tenant); a size bound if catalogues grow past ~10k codes. */
    private final Map<String, Map<String, Entry>> l1 = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Object>> inflight = new ConcurrentHashMap<>();
    /** Bumped on every eviction: a load that started before one never fills L1 (it may hold the pre-change value). */
    private final AtomicLong epoch = new AtomicLong();

    @Autowired
    ConfigCache(ObjectProvider<StringRedisTemplate> valkey, CacheKeyPolicy keys, ObjectMapper json, TenantScope tenants,
                @Value("${app.cache.config-ttl-seconds:300}") long l1Seconds,
                @Value("${app.cache.config-l2-ttl-seconds:3600}") long l2Seconds) {
        this(valkey, keys, json, tenants, Duration.ofSeconds(l1Seconds), Duration.ofSeconds(l2Seconds));
    }

    ConfigCache(ObjectProvider<StringRedisTemplate> valkey, CacheKeyPolicy keys, ObjectMapper json, TenantScope tenants, Duration l1, Duration l2) {
        this.valkey = valkey;
        this.keys = keys;
        this.json = json;
        this.tenants = tenants;
        this.l1Ms = l1.toMillis();
        this.l2Ms = l2.toMillis();
    }

    /** False when app.cache.config-ttl-seconds is 0 (tests whose fixtures edit config with plain SQL): every get() is a plain load. */
    public boolean enabled() {
        return l1Ms > 0;
    }

    /**
     * The value for {@code name} in {@code domain} of {@code tenantId}; {@code loader} runs in the caller's tenant transaction on a miss.
     * A null result is returned and not cached. A name that is not a plain id ([A-Za-z0-9._-], at most 64) is never cached.
     */
    public <T> T get(UUID tenantId, Domain domain, String name, Class<T> type, Supplier<T> loader) {
        if (!enabled() || name == null || !name.matches("[A-Za-z0-9._-]{1,64}")) {
            return loader.get();
        }
        String group = tenantId + "|" + domain;
        Entry e = entry(group, name);
        long now = System.nanoTime();
        if (e != null && now - e.hardAt < 0) {
            if (now - e.softAt >= 0) {
                refreshInBackground(tenantId, domain, name, type, loader, group);
            }
            return type.cast(e.value);
        }
        return singleFlight(group, name, type, () -> load(tenantId, domain, name, type, loader, group));
    }

    /** Called by the cache-invalidator consumer after the change committed: new reads look under the next version, L1 is dropped. */
    public void invalidate(UUID tenantId, Domain domain) {
        StringRedisTemplate t = valkey.getIfAvailable();
        if (t != null) {
            t.opsForValue().increment(versionKey(tenantId, domain)); // throws when Valkey is down: the consumer retries
        }
        evictLocal(tenantId, domain);
    }

    /** Drops this instance's L1 entries of the tenant's domain (the pub/sub broadcast of an invalidation). */
    public void evictLocal(UUID tenantId, Domain domain) {
        epoch.incrementAndGet();
        l1.remove(tenantId + "|" + domain);
    }

    // ---- internals ----

    private Entry entry(String group, String name) {
        Map<String, Entry> m = l1.get(group);
        return m == null ? null : m.get(name);
    }

    @SuppressWarnings("unchecked")
    private <T> T singleFlight(String group, String name, Class<T> type, Supplier<T> work) {
        String fk = group + "|" + name;
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> other = inflight.putIfAbsent(fk, mine);
        if (other != null) {
            try {
                return type.cast(other.join());
            } catch (CompletionException ex) {
                throw ex.getCause() instanceof RuntimeException r ? r : ex;
            }
        }
        try {
            Entry again = entry(group, name); // filled between our L1 miss and winning the flight
            T v = again != null && System.nanoTime() - again.hardAt < 0 ? type.cast(again.value) : work.get();
            mine.complete(v);
            return v;
        } catch (RuntimeException ex) {
            mine.completeExceptionally(ex);
            throw ex;
        } finally {
            inflight.remove(fk, mine);
        }
    }

    private <T> void refreshInBackground(UUID tenantId, Domain domain, String name, Class<T> type, Supplier<T> loader, String group) {
        String fk = group + "|" + name;
        CompletableFuture<Object> mine = new CompletableFuture<>();
        if (inflight.putIfAbsent(fk, mine) != null) {
            return;
        }
        Thread.ofPlatform().daemon().name("config-cache-refresh").start(() -> {
            try {
                mine.complete(tenants.run(tenantId, () -> load(tenantId, domain, name, type, loader, group)));
            } catch (RuntimeException ex) {
                mine.completeExceptionally(ex);
                log.debug("config refresh of domain {} failed; the cached value stays until its hard TTL", domain);
            } finally {
                inflight.remove(fk, mine);
            }
        });
    }

    private <T> T load(UUID tenantId, Domain domain, String name, Class<T> type, Supplier<T> loader, String group) {
        long startEpoch = epoch.get();
        StringRedisTemplate t = valkey.getIfAvailable();
        Supplier<T> guarded = () -> {
            try {
                return loader.get();
            } catch (RuntimeException ex) {
                throw new LoaderFailed(ex);
            }
        };
        T value;
        boolean cacheable = true;
        try {
            value = t == null ? guarded.get() : viaValkey(t, tenantId, domain, name, type, guarded);
        } catch (LoaderFailed lf) {
            throw (RuntimeException) lf.getCause();
        } catch (DataAccessException ex) {
            log.warn("config cache: Valkey unavailable for domain {}; answering from the database without caching", domain);
            value = loader.get();
            cacheable = false;
        }
        if (value != null && cacheable && epoch.get() == startEpoch) {
            long now = System.nanoTime();
            l1.computeIfAbsent(group, g -> new ConcurrentHashMap<>()).put(name,
                    new Entry(value, now + jitterNanos(l1Ms / 2), now + jitterNanos(l1Ms)));
        }
        return value;
    }

    private <T> T viaValkey(StringRedisTemplate t, UUID tenantId, Domain domain, String name, Class<T> type, Supplier<T> loader) {
        String ver = t.opsForValue().get(versionKey(tenantId, domain));
        String v = "v" + (ver == null ? "0" : ver);
        String l2 = key(tenantId, domain, name, v);
        String hit = t.opsForValue().get(l2);
        if (hit != null) {
            return json.readValue(hit, type);
        }
        String lock = key(tenantId, domain, name, v, "lock");
        boolean owner = Boolean.TRUE.equals(t.opsForValue().setIfAbsent(lock, "1", Duration.ofMillis(LOCK_MS)));
        if (!owner) { // another instance is loading this key: wait for its value instead of querying too
            long until = System.nanoTime() + LOCK_WAIT_MS * 1_000_000;
            while (System.nanoTime() < until) {
                try {
                    Thread.sleep(15);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                hit = t.opsForValue().get(l2);
                if (hit != null) {
                    return json.readValue(hit, type);
                }
            }
            // holder slow or gone: availability beats the at-most-one rule
        }
        if (owner && (hit = t.opsForValue().get(l2)) != null) { // the previous holder finished between our miss and our lock
            t.delete(lock);
            return json.readValue(hit, type);
        }
        T value = loader.get();
        try {
            if (value != null) { // under the version read BEFORE the load: a bump during the load makes this entry unreachable
                t.opsForValue().set(l2, json.writeValueAsString(value), Duration.ofNanos(jitterNanos(l2Ms)));
            }
            if (owner) {
                t.delete(lock); // shortcut: not owner-checked; the lock expires in 5 s, so a holder slower than that can free another's lock
            }
        } catch (DataAccessException ex) {
            log.warn("config cache: could not write L2 for domain {}", domain);
        }
        return value;
    }

    private String versionKey(UUID tenantId, Domain domain) {
        return key(tenantId, domain, "ver");
    }

    private String key(UUID tenantId, Domain domain, String... parts) {
        String[] all = new String[parts.length + 1];
        all[0] = domain.name().toLowerCase(java.util.Locale.ROOT);
        System.arraycopy(parts, 0, all, 1, parts.length);
        return keys.key("cfg", tenantId, domain.sourceColumns(), false, all);
    }

    /** +/-10%. */
    static long jitterNanos(long ms) {
        return Math.round(ms * 1_000_000d * (0.9 + 0.2 * ThreadLocalRandom.current().nextDouble()));
    }
}

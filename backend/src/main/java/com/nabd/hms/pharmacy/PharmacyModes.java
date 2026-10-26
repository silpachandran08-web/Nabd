package com.nabd.hms.pharmacy;

import com.nabd.hms.common.TenantScope;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PHL-004: the clinic's pharmacy mode for the request guard. Cached per clinic for 5 s, so a mode change made on another
 * replica reaches every session within 5 s; the replica that made it evicts at once. Cheap, so no event is needed.
 */
@Component
class PharmacyModes {

    static final long TTL_MILLIS = 5_000;

    private record Entry(PharmacyMode mode, long loadedAt) {
    }

    private final PharmacyRepository repo;
    private final TenantScope scope;
    private final Clock clock;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    PharmacyModes(PharmacyRepository repo, TenantScope scope, Clock clock) {
        this.repo = repo;
        this.scope = scope;
        this.clock = clock;
    }

    PharmacyMode of(UUID tenant) {
        Entry e = cache.get(tenant);
        if (e != null && clock.millis() - e.loadedAt() < TTL_MILLIS) {
            return e.mode();
        }
        PharmacyMode m = scope.run(tenant, () -> PharmacyMode.of(repo.findMode(tenant).orElse("external")));
        cache.put(tenant, new Entry(m, clock.millis()));
        return m;
    }

    void evict(UUID tenant) {
        cache.remove(tenant);
    }
}

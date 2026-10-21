package com.nabd.hms.organization.dpa;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.api.DataGatePort;
import com.nabd.hms.shared.cache.CacheKeyPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
class DataGate implements DataGatePort {

    private static final long TTL_NANOS = 5_000_000_000L; // "live within 5 s" on every instance, with no cross-instance push

    private record Entry(boolean live, long loadedAt) {
    }

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final CacheKeyPolicy keys;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    DataGate(JdbcTemplate jdbc, TenantContext tenantContext, CacheKeyPolicy keys) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.keys = keys;
    }

    @Override
    public boolean isLive() {
        UUID tenantId = tenantContext.current();
        String key = key(tenantId);
        Entry e = cache.get(key);
        long now = System.nanoTime();
        if (e != null && now - e.loadedAt() < TTL_NANOS) {
            return e.live();
        }
        boolean live = "live".equals(jdbc.queryForObject("SELECT data_gate FROM tenants WHERE id = ?", String.class, tenantId));
        if (!live && eligible(tenantId)) {
            // a plan chosen after the DPA was signed opens the gate here; the DB trigger re-checks both conditions
            live = jdbc.update("UPDATE tenants SET data_gate = 'live', updated_at = now() WHERE id = ? AND data_gate = 'demo_only'", tenantId) >= 0;
        }
        cache.put(key, new Entry(live, now));
        return live;
    }

    @Override
    public void requireRealData() {
        if (!isLive()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DPA_REQUIRED", "Data processing agreement required",
                    "Sign the data processing agreement and choose a plan before registering real patients.");
        }
    }

    /** Both conditions of the gate: a DPA acceptance (this tenant's rows, under RLS) and a subscription. */
    boolean eligible(UUID tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM dpa_acceptances WHERE tenant_id = ?) " +
                "AND EXISTS (SELECT 1 FROM master.subscriptions WHERE tenant_id = ?)", Boolean.class, tenantId, tenantId));
    }

    void evict(UUID tenantId) {
        cache.remove(key(tenantId));
    }

    private String key(UUID tenantId) {
        return keys.key("data-gate", tenantId, List.of("tenants.data_gate"), false, "row");
    }
}

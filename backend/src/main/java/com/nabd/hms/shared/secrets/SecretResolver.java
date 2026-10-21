package com.nabd.hms.shared.secrets;

import com.nabd.hms.common.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** OPS-058: the only way application code reads a secret. Tenant secrets are resolved for the tenant bound to the current
 * transaction (never a caller-supplied id), so one tenant's context cannot even name another tenant's path. Every call is
 * audited (path, no value). Values are cached for a short TTL (rotation is picked up without a restart; {@link #evict}
 * forces a re-read after an auth failure). The cache key is the full path, so tenants never share an entry. */
@Component
public class SecretResolver {

    private record Entry(Optional<SecretStore.Secret> secret, Instant loadedAt) {
    }

    private final ObjectProvider<SecretStore> store;
    private final Supplier<UUID> tenant;
    private final Clock clock;
    private final SecretReadAudit audit;
    private final String cell;
    private final String principal;
    private final Duration ttl;
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    @Autowired
    SecretResolver(ObjectProvider<SecretStore> store, TenantContext tenantContext, Clock clock, SecretReadAudit audit,
                   @Value("${nabd.secrets.cell:}") String cell,
                   @Value("${nabd.secrets.principal:nabd-api}") String principal,
                   @Value("${nabd.secrets.cache-ttl:PT5M}") Duration ttl) {
        this(store, tenantContext::current, clock, audit, cell, principal, ttl);
    }

    SecretResolver(ObjectProvider<SecretStore> store, Supplier<UUID> tenant, Clock clock, SecretReadAudit audit,
                   String cell, String principal, Duration ttl) {
        this.store = store;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
        this.cell = cell;
        this.principal = principal;
        this.ttl = ttl;
    }

    /** Installation secret (DB credentials, JWT keys, Meta app secret). */
    public Optional<String> platform(String name) {
        return read(SecretPaths.platform(cell, name)).map(SecretStore.Secret::value);
    }

    /** The calling tenant's integration secret (WhatsApp, payment gateway, insurer). */
    public Optional<String> tenant(String name) {
        return read(SecretPaths.tenant(cell, tenant.get(), name)).map(SecretStore.Secret::value);
    }

    /** For the owner screen ("key stored", last rotated): metadata only, never the value. */
    public Optional<Instant> tenantLastRotated(String name) {
        return read(SecretPaths.tenant(cell, tenant.get(), name)).map(SecretStore.Secret::lastRotated);
    }

    public void evictTenant(String name) {
        cache.remove(SecretPaths.tenant(cell, tenant.get(), name));
    }

    private Optional<SecretStore.Secret> read(String path) {
        Instant now = clock.instant();
        Entry e = cache.get(path);
        boolean cached = e != null && Duration.between(e.loadedAt(), now).compareTo(ttl) < 0;
        if (!cached) {
            SecretStore s = store.getIfAvailable();
            if (s == null) {
                throw new IllegalStateException("no secret store is configured");
            }
            e = new Entry(s.get(path), now);
            cache.put(path, e);
        }
        audit.read(principal, path, now, e.secret().isPresent(), cached);
        return e.secret();
    }
}

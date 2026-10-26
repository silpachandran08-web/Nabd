package com.nabd.hms.common.tenantkey;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantScope;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PLT-045: per-tenant data-encryption keys (DEKs) wrapped by the region KMS. A DEK is created with the tenant
 * (provisioning step create_tenant) before any patient row exists, stored only wrapped in tenant_data_keys, and cached
 * unwrapped in this JVM's memory for at most 15 minutes (never in Valkey, never logged). When the KMS is unreachable
 * a cached DEK keeps working until it expires; after that callers get 503 TENANT_KEY_UNAVAILABLE and an alert is raised
 * (ERROR log + nabd_tenant_key_unavailable_total). Tenants without a DEK (created before PLT-045, or KMS off) stay on the
 * global FieldCipher keyring until CryptoReencryptJob moves them. app.kms.provider unset = feature off.
 */
@Component
public class TenantKeyService {

    private static final Logger log = LoggerFactory.getLogger(TenantKeyService.class);
    static final Duration CACHE_TTL = Duration.ofMinutes(15);
    private static final Duration NO_KEY_TTL = Duration.ofSeconds(30);

    /** The key FieldCipher encrypts with: the DEK version (kid) and the unwrapped key. */
    public record ActiveKey(int version, SecretKeySpec key) {
    }

    private record Cached<T>(T value, Instant expires) {
    }

    private record Row(String kid, byte[] wrapped, String ref, String status) {
    }

    /** Platform regions screen: key age and last rotation per region. */
    public record RegionKeyInfo(String region, String keyRef, Instant createdAt, Instant lastRotatedAt) {
    }

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final ObjectProvider<KmsClient> kmsProvider;
    private final ObjectProvider<MeterRegistry> meters;
    private final Clock clock;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Cached<SecretKeySpec>> keys = new ConcurrentHashMap<>();
    private final Map<UUID, Cached<Integer>> active = new ConcurrentHashMap<>(); // 0 = tenant has no active DEK

    @Autowired
    public TenantKeyService(JdbcTemplate jdbc, TenantScope tenantScope, ObjectProvider<KmsClient> kms,
                            ObjectProvider<MeterRegistry> meters, Clock clock, AuditService audit) {
        this.audit = audit;
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.kmsProvider = kms;
        this.meters = meters;
        this.clock = clock;
    }

    public boolean enabled() {
        return kmsProvider.getIfAvailable() != null;
    }

    private KmsClient kms() {
        return kmsProvider.getObject();
    }

    private static byte[] ctx(UUID tenant) {
        return tenant.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    /** Creates the tenant's first DEK (idempotent). Call before the tenant's first encrypted write. */
    public void provision(UUID tenant, String region) {
        if (!enabled()) return;
        tenantScope.run(tenant, () -> {
            if (!jdbc.queryForList("SELECT 1 FROM tenant_data_keys WHERE tenant_id = ? LIMIT 1", tenant).isEmpty()) return;
            insertKey(tenant, "1", "active", regionKeyRef(region));
        });
        active.remove(tenant);
    }

    private void insertKey(UUID tenant, String kid, String status, String ref) {
        byte[] dek = new byte[32];
        random.nextBytes(dek);
        jdbc.update("INSERT INTO tenant_data_keys (tenant_id, kid, wrapped_dek, kms_key_ref, status) VALUES (?,?,?,?,?)",
                tenant, kid, kms().wrap(ref, dek, ctx(tenant)), ref, status);
    }

    private String regionKeyRef(String region) {
        List<String> ref = jdbc.queryForList("SELECT current_key_ref FROM kms_region_keys WHERE region_code = ?", String.class, region);
        if (!ref.isEmpty()) return ref.get(0);
        String created = kms().createKey(region);
        jdbc.update("INSERT INTO kms_region_keys (region_code, current_key_ref) VALUES (?,?) ON CONFLICT DO NOTHING", region, created);
        return jdbc.queryForObject("SELECT current_key_ref FROM kms_region_keys WHERE region_code = ?", String.class, region);
    }

    /** Starts a new DEK version: new writes use it; the old one is retired but still decrypts until the re-encrypt job has moved the rows. */
    public int rotateDek(UUID tenant) {
        int version = tenantScope.run(tenant, () -> {
            Integer current = jdbc.queryForObject("SELECT COALESCE(max(kid::int), 0) FROM tenant_data_keys WHERE tenant_id = ?", Integer.class, tenant);
            if (current == 0) throw new IllegalStateException("tenant has no data key");
            if (current >= 255) throw new IllegalStateException("tenant data key versions exhausted");
            String region = jdbc.queryForObject("SELECT region_code FROM tenants WHERE id = ?", String.class, tenant);
            jdbc.update("UPDATE tenant_data_keys SET status = 'retired' WHERE tenant_id = ? AND status = 'active'", tenant);
            insertKey(tenant, String.valueOf(current + 1), "active", regionKeyRef(region));
            return current + 1;
        });
        active.remove(tenant);
        // finished columns restart so CryptoReencryptJob moves rows from the retired DEK to the new one
        jdbc.update("UPDATE crypto_reencrypt_progress SET last_id = NULL, finished_at = NULL WHERE finished_at IS NOT NULL");
        return version;
    }

    /** Rotates the region's KMS key (new version) and re-wraps every DEK of that region. Returns the DEKs re-wrapped. */
    public int rotateRegionKey(String region) {
        String current = regionKeyRef(region);
        String next = kms().rotateKey(region, current);
        jdbc.update("UPDATE kms_region_keys SET current_key_ref = ?, last_rotated_at = ? WHERE region_code = ?", next,
                java.sql.Timestamp.from(clock.instant()), region);
        return rewrapAll();
    }

    /** Re-wraps every non-destroyed DEK that a retired region key version still wraps. A tenant that fails does not stop the others. */
    public int rewrapAll() {
        int done = 0;
        for (Map<String, Object> t : jdbc.queryForList("SELECT id, region_code FROM tenants ORDER BY id")) {
            UUID tenant = (UUID) t.get("id");
            try {
                done += tenantScope.run(tenant, () -> rewrap(tenant, (String) t.get("region_code")));
            } catch (RuntimeException e) {
                log.warn("tenant key re-wrap failed for tenant {}: {}", tenant, e.getClass().getSimpleName()); // log-phi-ok: ids and exception class
            }
        }
        return done;
    }

    private int rewrap(UUID tenant, String region) {
        String target = regionKeyRef(region);
        int n = 0;
        for (Row r : rows(tenant)) {
            if (r.status().equals("destroyed") || r.ref().equals(target)) continue;
            byte[] dek = kms().unwrap(r.ref(), r.wrapped(), ctx(tenant));
            jdbc.update("UPDATE tenant_data_keys SET wrapped_dek = ?, kms_key_ref = ?, rotated_at = ? WHERE tenant_id = ? AND kid = ?",
                    kms().wrap(target, dek, ctx(tenant)), target, java.sql.Timestamp.from(clock.instant()), tenant, r.kid());
            n++;
        }
        return n;
    }

    /**
     * Crypto-shred (offboarded tenants only, two distinct approvers). Empties every wrapped DEK, tells the KMS to refuse
     * the tenant from now on (so a restored backup copy stays unreadable) and drops the in-memory keys of this replica
     * (other replicas lose theirs within the 15-minute cache TTL). Ciphertext still under the global keyring is NOT covered.
     */
    public int shred(UUID tenant, UUID approverA, UUID approverB) {
        if (approverA == null || approverB == null || approverA.equals(approverB)) {
            throw new IllegalArgumentException("crypto-shred needs two distinct approvers");
        }
        String status = jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenant);
        if (!"offboarded".equals(status)) throw new IllegalStateException("only an offboarded tenant can be crypto-shredded");
        kms().destroyTenant(tenant.toString());
        int n = tenantScope.run(tenant, () -> {
            int destroyed = jdbc.update(
                    "UPDATE tenant_data_keys SET status = 'destroyed', wrapped_dek = ''::bytea, destroyed_at = ? WHERE tenant_id = ? AND status <> 'destroyed'",
                    java.sql.Timestamp.from(clock.instant()), tenant);
            audit.record(tenant, "operator", approverA, "Platform operator", "approver", null, "tenant_key.destroyed", "tenant_data_key", tenant,
                    null, Map.of("keysDestroyed", destroyed, "approvers", List.of(approverA.toString(), approverB.toString())));
            return destroyed;
        });
        evict(tenant);
        return n;
    }

    private void evict(UUID tenant) {
        active.remove(tenant);
        keys.keySet().removeIf(k -> k.startsWith(tenant + "/"));
    }

    // ---- hot path (FieldCipher) -----------------------------------------------------------------------------

    /** The key new writes use for this tenant, or empty (platform rows, tenants without a DEK, KMS feature off). */
    public Optional<ActiveKey> activeKey(String tenant) {
        UUID id = uuid(tenant);
        if (id == null || !enabled()) return Optional.empty();
        Cached<Integer> c = active.get(id);
        if (c == null || !c.expires().isAfter(clock.instant())) {
            Integer v = tenantScope.run(id, () -> jdbc.queryForList("SELECT kid::int FROM tenant_data_keys WHERE tenant_id = ? AND status = 'active'",
                    Integer.class, id).stream().findFirst().orElse(0));
            c = new Cached<>(v, clock.instant().plus(v == 0 ? NO_KEY_TTL : CACHE_TTL));
            active.put(id, c);
        }
        return c.value() == 0 ? Optional.empty() : Optional.of(new ActiveKey(c.value(), key(id, c.value())));
    }

    /**
     * The DEK of one version for reading. Empty = this tenant has no such usable key (never created or destroyed): the
     * value is unreadable. Throws TenantKeyUnavailableException when the KMS is down and the DEK is not cached.
     */
    public Optional<SecretKeySpec> keyFor(String tenant, int version) {
        UUID id = uuid(tenant);
        if (id == null || !enabled()) return Optional.empty();
        try {
            return Optional.of(key(id, version));
        } catch (KmsClient.KmsKeyDestroyedException | NoKey e) {
            return Optional.empty();
        }
    }

    private static final class NoKey extends RuntimeException {
        NoKey() {
            super(null, null, false, false);
        }
    }

    private SecretKeySpec key(UUID tenant, int version) {
        String ck = tenant + "/" + version;
        Cached<SecretKeySpec> c = keys.get(ck);
        if (c != null && c.expires().isAfter(clock.instant())) return c.value();
        Row row = tenantScope.run(tenant, () -> rows(tenant).stream().filter(r -> r.kid().equals(String.valueOf(version))).findFirst().orElse(null));
        if (row == null || row.status().equals("destroyed")) throw new NoKey();
        byte[] dek;
        try {
            dek = kms().unwrap(row.ref(), row.wrapped(), ctx(tenant));
        } catch (KmsClient.KmsUnavailableException e) {
            log.error("ALERT tenant key unavailable: region KMS unreachable and DEK for tenant {} not cached", tenant); // log-phi-ok: tenant id
            meters.ifAvailable(m -> m.counter("nabd_tenant_key_unavailable_total").increment());
            throw new TenantKeyUnavailableException();
        }
        SecretKeySpec k = new SecretKeySpec(dek, "AES");
        keys.put(ck, new Cached<>(k, clock.instant().plus(CACHE_TTL)));
        return k;
    }

    private List<Row> rows(UUID tenant) {
        return jdbc.query("SELECT kid, wrapped_dek, kms_key_ref, status FROM tenant_data_keys WHERE tenant_id = ? ORDER BY kid::int",
                (rs, i) -> new Row(rs.getString(1), rs.getBytes(2), rs.getString(3), rs.getString(4)), tenant);
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null; // "platform" rows
        }
    }

    // ---- regions screen -------------------------------------------------------------------------------------

    public List<RegionKeyInfo> regionKeys() {
        return jdbc.query("SELECT region_code, current_key_ref, created_at, last_rotated_at FROM kms_region_keys ORDER BY region_code",
                (rs, i) -> new RegionKeyInfo(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()));
    }
}

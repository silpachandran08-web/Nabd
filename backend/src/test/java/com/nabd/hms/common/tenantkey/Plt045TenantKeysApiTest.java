package com.nabd.hms.common.tenantkey;

import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PLT-045: per-tenant DEKs wrapped by the region KMS (software double): provisioning, isolation, rotation, crypto-shred, outage. */
class Plt045TenantKeysApiTest extends ApiTestBase {

    @Autowired FieldCipher cipher;
    @Autowired TenantKeyService keys;
    @Autowired TenantKeyRotationJob rotation;
    @Autowired LocalKmsClient kms;
    @Autowired ApplicationContext ctx;
    @Autowired TenantScope tenantScope;
    @Autowired com.nabd.hms.common.AuditService audit;

    private static final byte[] SECRET = "national-id-fixture".getBytes(StandardCharsets.UTF_8);

    private FieldCipher.Aad aad(UUID tenant) {
        return FieldCipher.Aad.of(tenant, "patients", "national_id_enc", "row-1");
    }

    private UUID keyedTenant() {
        UUID t = seedTenant().id();
        keys.provision(t, "IN");
        return t;
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    @Test
    void provisioningCreatesTheDekInTheCreateTenantStepBeforeAnyPatientRow() {
        SeededOperator op = seedOperator("super-keys-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        String token = platformLoginAndGetAccessToken(op);
        String slug = "keys" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ResponseEntity<Map> job = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(token, Map.of(
                "tenantSlug", slug, "tenantName", "Keys " + slug, "region", "IN", "ownerEmail", "owner-" + slug + "@nabd.health",
                "ownerName", "Test Owner", "ownerMobile", "+919876512345", "brandName", "Brand " + slug, "path", "self_serve")), Map.class);
        UUID jobId = UUID.fromString((String) job.getBody().get("id"));
        ResponseEntity<Map> first = exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance", HttpMethod.POST, authed(token), Map.class);
        UUID tenant = UUID.fromString((String) first.getBody().get("createdTenantId"));

        Map<String, Object> row = su().queryForMap("SELECT status, kms_key_ref, length(wrapped_dek) AS len FROM tenant_data_keys WHERE tenant_id = ?", tenant);
        assertThat(row.get("status")).isEqualTo("active");
        assertThat((String) row.get("kms_key_ref")).startsWith("local:IN:");
        assertThat(((Number) row.get("len")).intValue()).isEqualTo(12 + 32 + 16); // IV + DEK + GCM tag: stored wrapped, not raw
        assertThat(su().queryForObject("SELECT count(*) FROM patients WHERE tenant_id = ?", Integer.class, tenant)).isZero();
        assertThat(cipher.encrypt(SECRET, aad(tenant))[0]).isEqualTo((byte) 2); // writes already use the tenant key
    }

    @Test
    void twoTenantsUseDifferentDeksAndCiphertextDoesNotOpenAcrossThem() throws Exception {
        UUID a = keyedTenant();
        UUID b = keyedTenant();
        byte[] blobA = cipher.encrypt(SECRET, aad(a));
        assertThat(cipher.decrypt(blobA, aad(a))).isEqualTo(SECRET);
        assertThat(keys.keyFor(a.toString(), 1).orElseThrow().getEncoded()).isNotEqualTo(keys.keyFor(b.toString(), 1).orElseThrow().getEncoded());
        // wrong DEK: tenant B's key over tenant A's ciphertext (same AAD) does not open
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, keys.keyFor(b.toString(), 1).orElseThrow(), new GCMParameterSpec(128, blobA, 2, 12));
        c.updateAAD((a + ":patients:national_id_enc:row-1").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> c.doFinal(blobA, 14, blobA.length - 14)).isInstanceOf(javax.crypto.AEADBadTagException.class);
        // and the cipher refuses a copied value under the other tenant's identity
        assertThatThrownBy(() -> cipher.decrypt(blobA, aad(b))).isInstanceOf(FieldCipher.DecryptException.class);
    }

    @Test
    void tenantsWithoutADekKeepTheGlobalKeyring() {
        UUID legacy = seedTenant().id(); // seeded directly: no provisioning, no DEK
        byte[] blob = cipher.encrypt(SECRET, aad(legacy));
        assertThat(blob[0]).isEqualTo((byte) 1);
        assertThat(cipher.decrypt(blob, aad(legacy))).isEqualTo(SECRET);
    }

    @Test
    void anOnboardedKeyedTenantsOldGlobalValuesAreMovedToTheDekByTheReencryptStatus() {
        UUID t = seedTenant().id();
        byte[] before = cipher.encrypt(SECRET, aad(t)); // v1 under the global key
        keys.provision(t, "IN");
        assertThat(cipher.status(before, aad(t))).isEqualTo(FieldCipher.Status.OLD_KID); // CryptoReencryptJob rewrites it
        byte[] after = cipher.encrypt(cipher.decrypt(before, aad(t)), aad(t));
        assertThat(after[0]).isEqualTo((byte) 2);
        assertThat(cipher.status(after, aad(t))).isEqualTo(FieldCipher.Status.CURRENT);
    }

    @Test
    void regionKeyRotationRewrapsEveryDekWithoutBreakingReadsAndRecordsRotatedAt() {
        UUID t = keyedTenant();
        byte[] blob = cipher.encrypt(SECRET, aad(t));
        String refBefore = su().queryForObject("SELECT kms_key_ref FROM tenant_data_keys WHERE tenant_id = ?", String.class, t);
        int n = keys.rotateRegionKey("IN");
        assertThat(n).isGreaterThanOrEqualTo(1);
        Map<String, Object> row = su().queryForMap("SELECT kms_key_ref, rotated_at FROM tenant_data_keys WHERE tenant_id = ?", t);
        assertThat(row.get("kms_key_ref")).isNotEqualTo(refBefore);
        assertThat(row.get("rotated_at")).isNotNull();
        assertThat(su().queryForObject("SELECT last_rotated_at FROM kms_region_keys WHERE region_code = 'IN'", Object.class)).isNotNull();
        assertThat(cipher.decrypt(blob, aad(t))).isEqualTo(SECRET); // same DEK, new wrapper
        assertThat(rotation.runOnce()).isZero(); // nothing left to re-wrap
        assertThat(keys.regionKeys()).anyMatch(k -> k.region().equals("IN") && k.lastRotatedAt() != null);
    }

    @Test
    void dekRotationKeepsOldValuesReadableAndNewWritesUseTheNewVersion() {
        UUID t = keyedTenant();
        byte[] old = cipher.encrypt(SECRET, aad(t));
        assertThat(keys.rotateDek(t)).isEqualTo(2);
        assertThat(cipher.decrypt(old, aad(t))).isEqualTo(SECRET);
        assertThat(cipher.status(old, aad(t))).isEqualTo(FieldCipher.Status.OLD_KID);
        byte[] fresh = cipher.encrypt(SECRET, aad(t));
        assertThat(fresh[1]).isEqualTo((byte) 2);
        assertThat(su().queryForList("SELECT status FROM tenant_data_keys WHERE tenant_id = ? ORDER BY kid::int", String.class, t))
                .containsExactly("retired", "active");
    }

    @Test
    void cryptoShredMakesTheTenantsFieldsAndARestoredBackupUnreadableAndLeavesOthersAlone() {
        UUID victim = keyedTenant();
        UUID other = keyedTenant();
        byte[] victimBlob = cipher.encrypt(SECRET, aad(victim));
        byte[] otherBlob = cipher.encrypt(SECRET, aad(other));
        Map<String, Object> backup = su().queryForMap("SELECT kid, wrapped_dek, kms_key_ref, status FROM tenant_data_keys WHERE tenant_id = ?", victim);

        UUID a = UUID.randomUUID();
        assertThatThrownBy(() -> keys.shred(victim, a, a)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> keys.shred(victim, a, UUID.randomUUID())).isInstanceOf(IllegalStateException.class); // not offboarded
        su().update("UPDATE tenants SET status = 'offboarded' WHERE id = ?", victim);
        assertThat(keys.shred(victim, a, UUID.randomUUID())).isEqualTo(1);

        assertThatThrownBy(() -> cipher.decrypt(victimBlob, aad(victim))).isInstanceOf(FieldCipher.DecryptException.class);
        assertThat(cipher.tryDecrypt(victimBlob, aad(victim))).isEmpty();
        assertThat(su().queryForObject("SELECT status FROM tenant_data_keys WHERE tenant_id = ?", String.class, victim)).isEqualTo("destroyed");
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'tenant_key.destroyed'", Integer.class, victim)).isEqualTo(1);
        assertThat(cipher.decrypt(otherBlob, aad(other))).isEqualTo(SECRET);

        // a restored backup brings the wrapped DEK row back; the KMS still refuses the shredded tenant
        su().update("UPDATE tenant_data_keys SET wrapped_dek = ?, status = ?, destroyed_at = NULL WHERE tenant_id = ?",
                backup.get("wrapped_dek"), backup.get("status"), victim);
        assertThat(cipher.tryDecrypt(victimBlob, aad(victim))).isEmpty();
    }

    @Test
    void kmsOutageKeepsCachedDeksForFifteenMinutesThenFailsWith503AndAnAlert() {
        UUID t = keyedTenant();
        MutableClock clock = new MutableClock();
        TenantKeyService svc = new TenantKeyService(jdbc, tenantScope, ctx.getBeanProvider(KmsClient.class), ctx.getBeanProvider(MeterRegistry.class), clock, audit);
        assertThat(svc.keyFor(t.toString(), 1)).isPresent(); // cached now
        MeterRegistry meters = ctx.getBean(MeterRegistry.class);
        double alertsBefore = meters.counter("nabd_tenant_key_unavailable_total").count();
        kms.simulateOutage(true);
        try {
            clock.advance(Duration.ofMinutes(14));
            assertThat(svc.keyFor(t.toString(), 1)).isPresent(); // still served from memory
            clock.advance(Duration.ofMinutes(2));
            assertThatThrownBy(() -> svc.keyFor(t.toString(), 1)).isInstanceOfSatisfying(TenantKeyUnavailableException.class, e -> {
                assertThat(e.status().value()).isEqualTo(503);
                assertThat(e.typeSlug()).isEqualTo("TENANT_KEY_UNAVAILABLE");
            });
            assertThat(meters.counter("nabd_tenant_key_unavailable_total").count()).isGreaterThan(alertsBefore);
        } finally {
            kms.simulateOutage(false);
        }
        assertThat(svc.keyFor(t.toString(), 1)).isPresent(); // recovers when the KMS is back
    }

    @Test
    void tenantDataKeysAreRowLevelIsolated() {
        UUID a = keyedTenant();
        UUID b = keyedTenant();
        assertThat(inTenantTx(a, () -> jdbc.queryForList("SELECT tenant_id FROM tenant_data_keys", UUID.class))).containsOnly(a);
        assertThat(inTenantTx(b, () -> jdbc.update("UPDATE tenant_data_keys SET status = 'destroyed' WHERE tenant_id = ?", a))).isZero();
    }
}

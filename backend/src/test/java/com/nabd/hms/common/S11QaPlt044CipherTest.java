package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S11 QA (independent) PLT-044. Legacy values are produced by a verbatim copy of the Sprint 10 AesGcmCipher (develop-initial
 * 89dc679, the APP_CRYPTO_KEY format: [IV][ciphertext+tag], no AAD) so the merged FieldCipher is checked against what
 * production rows really hold, through the real API paths.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class S11QaPlt044CipherTest extends ApiTestBase {

    /** Verbatim algorithm of the deleted com.nabd.hms.common.AesGcmCipher.encrypt (Sprint 10). */
    static final class Sprint10AesGcmCipher {
        private final SecretKeySpec key;
        private final SecureRandom random = new SecureRandom();

        Sprint10AesGcmCipher(String base64Key) {
            this.key = new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
        }

        byte[] encrypt(byte[] plaintext) throws Exception {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] out = new byte[12 + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(ciphertext, 0, out, 12, ciphertext.length);
            return out;
        }
    }

    private final Sprint10AesGcmCipher old = new Sprint10AesGcmCipher(CRYPTO_KEY_B64); // the APP_CRYPTO_KEY this test context runs with
    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private static String phone() {
        return "+9195" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
    }

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String staffMfaSignIn(SeededStaff s) {
        su.update("UPDATE staff SET mfa_last_used_step = NULL WHERE id = ?", s.id());
        ResponseEntity<Map> c = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", s.slug(), "email", s.email(), "pin", STAFF_PIN)), Map.class);
        assertThat(c.getBody()).containsKey("challengeId");
        ResponseEntity<Map> v = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of("challengeId", c.getBody().get("challengeId"), "code", currentTotpCode())), Map.class);
        return v.getStatusCode().value() + "";
    }

    // ── (c) legacy APP_CRYPTO_KEY values written before the merge still work ──

    @Test
    void legacyStaffMfaSecretStillSignsInAfterTheMerge() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff s = seedStaff(t, seedFullAccessRole(t.id()), "l" + uniq() + "@qa.test", phone(), true);
        su.update("UPDATE staff SET mfa_secret_enc = ? WHERE id = ?", old.encrypt(TOTP_SECRET), s.id());
        assertThat(staffMfaSignIn(s)).isEqualTo("200");
        // wrong code with a legacy secret is still refused (legacy read is not a bypass)
        su.update("UPDATE staff SET mfa_last_used_step = NULL WHERE id = ?", s.id());
        ResponseEntity<Map> c = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", s.slug(), "email", s.email(), "pin", STAFF_PIN)), Map.class);
        ResponseEntity<Map> bad = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of("challengeId", c.getBody().get("challengeId"), "code", "000000")), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        su.update("UPDATE staff SET mfa_secret_enc = ? WHERE id = ?", cipher.encrypt(TOTP_SECRET, FieldCipher.Aad.of(t.id(), "staff", "mfa_secret", s.id())), s.id());
    }

    @Test
    void legacyOperatorMfaSecretStillSignsIn() throws Exception {
        SeededOperator o = seedOperator("qa44-" + uniq() + "@nabd.test", "super_admin", true);
        su.update("UPDATE master.operators SET mfa_secret_enc = ?, mfa_last_used_step = NULL WHERE id = ?", old.encrypt(TOTP_SECRET), o.id());
        ResponseEntity<Map> c = http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of("email", o.email(), "pin", STAFF_PIN)), Map.class);
        ResponseEntity<Map> v = http.postForEntity(url("/v1/platform/auth/mfa/verify"), jsonBody(Map.of("challengeId", c.getBody().get("challengeId"), "code", currentTotpCode())), Map.class);
        assertThat(v.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(v.getBody()).containsKey("accessToken");
        su.update("UPDATE master.operators SET mfa_secret_enc = ? WHERE id = ?", cipher.encrypt(TOTP_SECRET, FieldCipher.Aad.of("platform", "master.operators", "mfa_secret", o.id())), o.id());
    }

    @Test
    void legacyIdempotencyBodyIsStillReplayed() throws Exception {
        SeededTenant t = seedTenant();
        String token = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "i" + uniq() + "@qa.test", phone(), false));
        UUID key = UUID.randomUUID();
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        h.set("Idempotency-Key", key.toString());
        String body = "{\"name\":\"QA44 Legacy\",\"phone\":\"" + phone() + "\",\"dob\":\"1990-01-01\",\"gender\":\"male\",\"confirmedNotDuplicate\":true}";
        ResponseEntity<String> first = http.exchange(url("/v1/patients"), HttpMethod.POST, new HttpEntity<>(body, h), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String marker = "{\"id\":\"qa44-legacy-replay\"}";
        int rows = inTenantTx(t.id(), () -> jdbc.update("UPDATE idempotency_keys SET response_body_enc = ? WHERE tenant_id = ? AND idem_key = ?",
                uncheckedOld(marker.getBytes(StandardCharsets.UTF_8)), t.id(), key));
        assertThat(rows).isEqualTo(1);
        ResponseEntity<String> replay = http.exchange(url("/v1/patients"), HttpMethod.POST, new HttpEntity<>(body, h), String.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(marker);
    }

    private byte[] uncheckedOld(byte[] plain) {
        try {
            return old.encrypt(plain);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void legacyNationalIdIsRewrittenByTheJobAndThenRowBound() throws Exception {
        SeededTenant t = seedTenant();
        String token = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "n" + uniq() + "@qa.test", phone(), false));
        Map<String, Object> req = new HashMap<>(Map.of("name", "QA44 Nid", "phone", phone(), "dob", "1990-01-01", "gender", "female", "confirmedNotDuplicate", true));
        String id = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, req), Map.class).getBody().get("id");
        su.update("UPDATE patients SET national_id_enc = ? WHERE id = ?::uuid", old.encrypt("424242424242".getBytes(StandardCharsets.UTF_8)), id);
        FieldCipher.Aad aad = FieldCipher.Aad.of(t.id(), "patients", "national_id_enc", id);
        byte[] before = su.queryForObject("SELECT national_id_enc FROM patients WHERE id = ?::uuid", byte[].class, id);
        assertThat(cipher.status(before, aad)).isEqualTo(FieldCipher.Status.LEGACY);
        assertThat(exchange("/v1/patients/" + id, HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        // an earlier class may have finished a pass under this kid; start a fresh pass as an operator would after a restore
        su.update("UPDATE crypto_reencrypt_progress SET finished_at = NULL, last_id = NULL, rows_done = 0");
        CryptoReencryptJob job = new CryptoReencryptJob(jdbc, txManager, tenantContext, cipher, 500, false);
        for (int i = 0; i < 200 && !job.run(50).finished(); i++) { /* resumable: keep stepping */ }
        byte[] after = su.queryForObject("SELECT national_id_enc FROM patients WHERE id = ?::uuid", byte[].class, id);
        assertThat(after[0]).isEqualTo((byte) 1);
        assertThat(new String(cipher.decrypt(after, aad), StandardCharsets.UTF_8)).isEqualTo("424242424242");
        assertThat(cipher.status(after, aad)).isEqualTo(FieldCipher.Status.CURRENT);
        assertThatThrownBy(() -> cipher.decrypt(after, FieldCipher.Aad.of(t.id(), "patients", "national_id_enc", UUID.randomUUID())))
                .isInstanceOf(FieldCipher.DecryptException.class);
        assertThat(job.verify()).allSatisfy(c -> assertThat(c.counts().getOrDefault(FieldCipher.Status.LEGACY, 0L)).as(c.table() + "." + c.column()).isZero());
    }

    @Test
    void prodShapedConfigReadsLegacyOnlyWhileAppCryptoKeyIsKept() throws Exception {
        byte[] legacy = old.encrypt("x".getBytes(StandardCharsets.UTF_8));
        FieldCipher.Aad a = FieldCipher.Aad.of(UUID.randomUUID(), "staff", "mfa_secret", UUID.randomUUID());
        // the deploy note: APP_CRYPTO_KEYRING=1:<APP_CRYPTO_KEY>, APP_CRYPTO_KEY kept
        FieldCipher kept = new FieldCipher("1:" + CRYPTO_KEY_B64, "1", CRYPTO_KEY_B64, true);
        assertThat(kept.decrypt(legacy, a)).isEqualTo("x".getBytes(StandardCharsets.UTF_8));
        // APP_CRYPTO_KEY removed before the job finished: legacy rows fail closed (documented), never decrypt with a wrong key
        FieldCipher dropped = new FieldCipher("1:" + CRYPTO_KEY_B64, "1", "", true);
        assertThat(dropped.tryDecrypt(legacy, a)).isEmpty();
        // a legacy value encrypted under a DIFFERENT old key does not open
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        byte[] foreign = new Sprint10AesGcmCipher(Base64.getEncoder().encodeToString(k)).encrypt("x".getBytes(StandardCharsets.UTF_8));
        assertThat(kept.tryDecrypt(foreign, a)).isEmpty();
    }

    // ── AC1 / AC2: format and row binding ──

    @Test
    void newPatientNationalIdIsVersionedAndBoundToItsRow() {
        SeededTenant t = seedTenant();
        String token = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "v" + uniq() + "@qa.test", phone(), false));
        Map<String, Object> req = new HashMap<>(Map.of("name", "QA44 V1", "phone", phone(), "dob", "1990-01-01", "gender", "male", "confirmedNotDuplicate", true, "nationalId", "135791357913"));
        String id = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, req), Map.class).getBody().get("id");
        byte[] blob = su.queryForObject("SELECT national_id_enc FROM patients WHERE id = ?::uuid", byte[].class, id);
        assertThat(blob[0]).as("format version").isEqualTo((byte) 1);
        assertThat(blob[1] & 0xff).as("key id").isEqualTo(cipher.activeKid());
        assertThat(blob.length).isEqualTo(2 + 12 + "135791357913".length() + 16);
        assertThat(cipher.decrypt(blob, FieldCipher.Aad.of(t.id(), "patients", "national_id_enc", id))).isEqualTo("135791357913".getBytes(StandardCharsets.UTF_8));
        assertThat(cipher.tryDecrypt(blob, FieldCipher.Aad.of(t.id(), "patients", "national_id", id))).as("other column").isEmpty();
        assertThat(cipher.tryDecrypt(blob, FieldCipher.Aad.of(UUID.randomUUID(), "patients", "national_id_enc", id))).as("other tenant").isEmpty();
    }

    @Test
    @org.junit.jupiter.api.Tag("known-defect-PLT-044") // OwnerService.verifyMfa uses cipher.decrypt (throws) not tryDecrypt: 500
    void ownerMfaSecretCopiedFromAnotherOwnerFailsClosedWithoutA500() {
        SeededOwner a = seedOwner("qa44a-" + uniq() + "@qa.test");
        SeededOwner b = seedOwner("qa44b-" + uniq() + "@qa.test");
        byte[] secretOfA = cipher.encrypt(TOTP_SECRET, FieldCipher.Aad.of("platform", "owners", "mfa_secret", a.id()));
        su.update("UPDATE owners SET mfa_enabled = true, mfa_secret_enc = ? WHERE id IN (?, ?)", secretOfA, a.id(), b.id());
        try {
            ResponseEntity<Map> c = http.postForEntity(url("/v1/owners/auth/login"), jsonBody(Map.of("email", b.email(), "pin", STAFF_PIN)), Map.class);
            assertThat(c.getBody()).containsKey("challengeId");
            ResponseEntity<Map> v = http.postForEntity(url("/v1/owners/auth/mfa/verify"),
                    jsonBody(Map.of("challengeToken", c.getBody().get("challengeId"), "code", currentTotpCode())), Map.class);
            assertThat(v.getStatusCode()).as("copied owner secret: refused as MFA failure, not a 500. body=" + v.getBody()).isEqualTo(HttpStatus.UNAUTHORIZED);
        } finally {
            su.update("UPDATE owners SET mfa_enabled = false, mfa_secret_enc = NULL WHERE id IN (?, ?)", a.id(), b.id());
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("known-defect-PLT-044") // CryptoReencryptJob.TARGETS lacks owners.mfa_secret_enc / mfa_pending_secret_enc (PLT-005 merge)
    void reencryptJobCoversEveryEncryptedColumnInTheMergedSchema() {
        // every bytea *_enc column the merged schema holds must be in the job (or rotation leaves it on the old kid forever)
        java.util.List<String> cols = su.queryForList("SELECT table_schema || '.' || table_name || '.' || column_name FROM information_schema.columns "
                + "WHERE data_type = 'bytea' AND column_name LIKE '%\\_enc' AND table_schema IN ('public','master') ORDER BY 1", String.class);
        java.util.Set<String> covered = new java.util.HashSet<>();
        for (CryptoReencryptJob.Target tg : CryptoReencryptJob.TARGETS) {
            covered.add((tg.table().contains(".") ? tg.table() : "public." + tg.table()) + "." + tg.column());
        }
        java.util.Set<String> transientOk = java.util.Set.of("public.otp_requests.code_enc"); // 5-minute OTP codes, dropped once sent
        assertThat(cols.stream().filter(c -> !covered.contains(c) && !transientOk.contains(c)).toList()).as("encrypted columns the job never rewrites").isEmpty();
    }
}

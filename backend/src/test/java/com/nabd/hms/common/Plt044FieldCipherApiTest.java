package com.nabd.hms.common;

import com.nabd.hms.NabdHmsApplication;
import com.nabd.hms.config.SigningKeyProvider;
import com.nabd.hms.support.ApiTestBase;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PLT-044 integration: v1 writes bound to their row, copied ciphertext fails closed, legacy reads, resumable job, rotation, prod guard. */
@ExtendWith(OutputCaptureExtension.class)
class Plt044FieldCipherApiTest extends ApiTestBase {

    @Autowired
    Environment env;

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private static String phone() {
        return "+9198" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));
    }

    private String register(String token, String nationalId) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", "P44 " + UUID.randomUUID().toString().substring(0, 6),
                "phone", phone(), "dob", "1990-01-01", "gender", "male", "confirmedNotDuplicate", true));
        if (nationalId != null) body.put("nationalId", nationalId);
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, body), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private byte[] nid(String patientId) {
        return su.queryForObject("SELECT national_id_enc FROM patients WHERE id = ?::uuid", byte[].class, patientId);
    }

    private static FieldCipher.Aad patientAad(UUID tenant, String patientId) {
        return FieldCipher.Aad.of(tenant, "patients", "national_id_enc", patientId);
    }

    private static FieldCipher.Aad staffAad(SeededStaff s) {
        return FieldCipher.Aad.of(s.tenantId(), "staff", "mfa_secret", s.id());
    }

    private static FieldCipher.Aad operatorAad(SeededOperator o) {
        return FieldCipher.Aad.of("platform", "master.operators", "mfa_secret", o.id());
    }

    private HttpStatus staffMfa(SeededStaff s) {
        su.update("UPDATE staff SET mfa_last_used_step = NULL WHERE id = ?", s.id());
        ResponseEntity<Map> login = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", s.slug(), "email", s.email(), "pin", STAFF_PIN)), Map.class);
        ResponseEntity<Map> v = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", login.getBody().get("challengeId"), "code", currentTotpCode())), Map.class);
        return HttpStatus.valueOf(v.getStatusCode().value());
    }

    private HttpStatus operatorMfa(SeededOperator o) {
        su.update("UPDATE master.operators SET mfa_last_used_step = NULL WHERE id = ?", o.id());
        ResponseEntity<Map> login = http.postForEntity(url("/v1/platform/auth/login"), jsonBody(Map.of(
                "email", o.email(), "pin", STAFF_PIN)), Map.class);
        ResponseEntity<Map> v = http.postForEntity(url("/v1/platform/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", login.getBody().get("challengeId"), "code", currentTotpCode())), Map.class);
        return HttpStatus.valueOf(v.getStatusCode().value());
    }

    private static byte[] legacy(byte[] plain) throws Exception {
        return FieldCipherTest.legacyEncrypt(CRYPTO_KEY_B64, plain, FieldCipherTest.randomIv());
    }

    private final Map<UUID, UUID> roles = new java.util.HashMap<>(); // one full-access role per tenant (role names are unique)

    private UUID role(SeededTenant t) {
        return roles.computeIfAbsent(t.id(), this::seedFullAccessRole);
    }

    private SeededStaff mfaStaff(SeededTenant t) {
        return seedStaff(t, role(t), "p44-" + UUID.randomUUID() + "@a.com", phone(), true);
    }

    @Test
    void newValuesCarryVersionAndKidAndOpenOnlyForTheirOwnRow() {
        SeededTenant t = seedTenant();
        String token = loginAndGetAccessToken(seedStaff(t, role(t), "p44n@a.com", phone(), false));
        String a = register(token, "123456789012");
        String b = register(token, null);
        byte[] blob = nid(a);
        assertThat(blob[0]).isEqualTo((byte) 1);
        assertThat(blob[1] & 0xff).isEqualTo(cipher.activeKid());
        assertThat(new String(cipher.decrypt(blob, patientAad(t.id(), a)), StandardCharsets.UTF_8)).isEqualTo("123456789012");
        assertThatThrownBy(() -> cipher.decrypt(blob, patientAad(t.id(), b))).isInstanceOf(FieldCipher.DecryptException.class);
        assertThatThrownBy(() -> cipher.decrypt(blob, patientAad(UUID.randomUUID(), a))).isInstanceOf(FieldCipher.DecryptException.class);

        SeededStaff s = mfaStaff(t); // seeded through FieldCipher with the staff AAD; sign-in decrypts it
        assertThat(staffMfa(s)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ciphertextCopiedToAnotherRowOrTenantFailsClosedWithout500AndLogsNoValues(CapturedOutput out) {
        SeededTenant t1 = seedTenant();
        SeededTenant t2 = seedTenant();
        String tok1 = loginAndGetAccessToken(seedStaff(t1, role(t1), "p44c1@a.com", phone(), false));
        String tok2 = loginAndGetAccessToken(seedStaff(t2, role(t2), "p44c2@a.com", phone(), false));
        String pa = register(tok1, "999988887777");
        String pb = register(tok1, null);
        String pc = register(tok2, null);
        SeededStaff sa = mfaStaff(t1), sb = mfaStaff(t1), sc = mfaStaff(t2);
        SeededOperator oa = seedOperator("p44a-" + UUID.randomUUID() + "@nabd.health", "super_admin", true);
        SeededOperator ob = seedOperator("p44b-" + UUID.randomUUID() + "@nabd.health", "super_admin", true);
        byte[] secretA = su.queryForObject("SELECT mfa_secret_enc FROM staff WHERE id = ?", byte[].class, sa.id());
        try {
            su.update("UPDATE patients SET national_id_enc = ? WHERE id IN (?::uuid, ?::uuid)", nid(pa), pb, pc);
            su.update("UPDATE staff SET mfa_secret_enc = ? WHERE id IN (?, ?)", secretA, sb.id(), sc.id());
            su.update("UPDATE master.operators SET mfa_secret_enc = (SELECT mfa_secret_enc FROM master.operators WHERE id = ?) WHERE id = ?",
                    oa.id(), ob.id());

            // patient records still load (the national ID is never returned; the bad value is just unusable)
            assertThat(exchange("/v1/patients/" + pb, HttpMethod.GET, authed(tok1), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(exchange("/v1/patients/" + pc, HttpMethod.GET, authed(tok2), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(cipher.tryDecrypt(nid(pb), patientAad(t1.id(), pb))).isEmpty();
            assertThat(cipher.tryDecrypt(nid(pc), patientAad(t2.id(), pc))).isEmpty();
            // same TOTP seed everywhere, so without AAD the copies would sign in: now a generic 401, never a 500
            assertThat(staffMfa(sa)).isEqualTo(HttpStatus.OK);
            assertThat(staffMfa(sb)).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(staffMfa(sc)).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(operatorMfa(oa)).isEqualTo(HttpStatus.OK);
            assertThat(operatorMfa(ob)).isEqualTo(HttpStatus.UNAUTHORIZED);

            assertThat(out.getAll()).contains("encrypted field staff.mfa_secret for row " + sb.id() + " did not decrypt")
                    .contains("encrypted field master.operators.mfa_secret for row " + ob.id() + " did not decrypt")
                    .doesNotContain(Base64.getEncoder().encodeToString(secretA))
                    .doesNotContain(Base64.getEncoder().encodeToString(TOTP_SECRET))
                    .doesNotContain(new String(TOTP_SECRET, StandardCharsets.US_ASCII))
                    .doesNotContain("999988887777")
                    .doesNotContain(CRYPTO_KEY_B64);
        } finally { // leave every row decryptable for the global verify pass
            su.update("UPDATE patients SET national_id_enc = NULL WHERE id IN (?::uuid, ?::uuid)", pb, pc);
            su.update("UPDATE staff SET mfa_secret_enc = ? WHERE id = ?", cipher.encrypt(TOTP_SECRET, staffAad(sb)), sb.id());
            su.update("UPDATE staff SET mfa_secret_enc = ? WHERE id = ?", cipher.encrypt(TOTP_SECRET, staffAad(sc)), sc.id());
            su.update("UPDATE master.operators SET mfa_secret_enc = ? WHERE id = ?", cipher.encrypt(TOTP_SECRET, operatorAad(ob)), ob.id());
        }
    }

    @Test
    void legacyValuesStillWorkThenTheJobStopsResumesRotatesAndVerifies() throws Exception {
        su.update("DELETE FROM crypto_reencrypt_progress");
        SeededTenant t1 = seedTenant();
        SeededTenant t2 = seedTenant();
        String tok1 = loginAndGetAccessToken(seedStaff(t1, role(t1), "p44j1@a.com", phone(), false));
        String tok2 = loginAndGetAccessToken(seedStaff(t2, role(t2), "p44j2@a.com", phone(), false));
        List<String[]> patients = new ArrayList<>(); // {tenant, patientId, plaintext}
        for (int i = 0; i < 5; i++) {
            SeededTenant t = i < 3 ? t1 : t2;
            String nidPlain = "55500000000" + i;
            String id = register(i < 3 ? tok1 : tok2, "placeholder");
            su.update("UPDATE patients SET national_id_enc = ? WHERE id = ?::uuid", legacy(nidPlain.getBytes(StandardCharsets.UTF_8)), id);
            patients.add(new String[]{t.id().toString(), id, nidPlain});
        }
        SeededStaff s = mfaStaff(t1);
        SeededOperator o = seedOperator("p44j-" + UUID.randomUUID() + "@nabd.health", "super_admin", true);
        su.update("UPDATE staff SET mfa_secret_enc = ?, mfa_pending_secret_enc = ? WHERE id = ?", legacy(TOTP_SECRET), legacy(TOTP_SECRET), s.id());
        su.update("UPDATE master.operators SET mfa_secret_enc = ? WHERE id = ?", legacy(TOTP_SECRET), o.id());

        // legacy-format read compatibility during the migration (APP_CRYPTO_KEY format, no AAD)
        assertThat(staffMfa(s)).isEqualTo(HttpStatus.OK);
        assertThat(operatorMfa(o)).isEqualTo(HttpStatus.OK);
        for (String[] p : patients) {
            byte[] blob = nid(p[1]);
            assertThat(cipher.status(blob, patientAad(UUID.fromString(p[0]), p[1]))).isEqualTo(FieldCipher.Status.LEGACY);
        }

        // stop after 2 batches of 2 rows, then a NEW instance (a restart) resumes from the checkpoint
        CryptoReencryptJob.Result first = new CryptoReencryptJob(jdbc, txManager, tenantContext, cipher, 2, false).run(2);
        assertThat(first.finished()).isFalse();
        Map<String, Object> cp = su.queryForMap("SELECT last_id, finished_at FROM crypto_reencrypt_progress WHERE table_name = 'staff' AND column_name = 'mfa_secret_enc'");
        assertThat(cp.get("last_id")).isNotNull();
        assertThat(cp.get("finished_at")).isNull();
        assertThat(cipher.status(nid(patients.get(0)[1]), patientAad(UUID.fromString(patients.get(0)[0]), patients.get(0)[1])))
                .isEqualTo(FieldCipher.Status.LEGACY); // later table not reached yet

        CryptoReencryptJob job = new CryptoReencryptJob(jdbc, txManager, tenantContext, cipher, 2, false);
        assertThat(job.run(Integer.MAX_VALUE).finished()).isTrue();
        assertThat(su.queryForObject("SELECT count(*) FROM crypto_reencrypt_progress WHERE finished_at IS NULL", Integer.class)).isZero();
        assertThat(su.queryForObject("SELECT count(*) FROM crypto_reencrypt_progress", Integer.class)).isEqualTo(CryptoReencryptJob.TARGETS.size());
        assertThat(su.queryForObject("SELECT sum(rows_done) FROM crypto_reencrypt_progress", Long.class)).isGreaterThanOrEqualTo(8L);
        for (String[] p : patients) {
            FieldCipher.Aad aad = patientAad(UUID.fromString(p[0]), p[1]);
            assertThat(cipher.status(nid(p[1]), aad)).isEqualTo(FieldCipher.Status.CURRENT);
            assertThat(new String(cipher.decrypt(nid(p[1]), aad), StandardCharsets.UTF_8)).isEqualTo(p[2]);
        }
        Map<String, Object> staffRow = su.queryForMap("SELECT mfa_secret_enc, mfa_pending_secret_enc FROM staff WHERE id = ?", s.id());
        assertThat(cipher.status((byte[]) staffRow.get("mfa_secret_enc"), staffAad(s))).isEqualTo(FieldCipher.Status.CURRENT);
        assertThat(cipher.status((byte[]) staffRow.get("mfa_pending_secret_enc"), staffAad(s))).isEqualTo(FieldCipher.Status.CURRENT);
        assertThat(job.verify()).allSatisfy(c -> assertThat(c.clean()).as(c.table() + "." + c.column() + " " + c.counts()).isTrue());
        assertThat(job.run(Integer.MAX_VALUE).batches()).isZero(); // finished for the active kid: nothing to redo
        assertThat(staffMfa(s)).isEqualTo(HttpStatus.OK);
        assertThat(operatorMfa(o)).isEqualTo(HttpStatus.OK);

        // rotation: kid 2 becomes active, old kid-1 values still decrypt, re-running the job rewrites them to kid 2
        String k2 = FieldCipherTest.newKey();
        FieldCipher rotated = new FieldCipher("1:" + CRYPTO_KEY_B64 + ",2:" + k2, "2", CRYPTO_KEY_B64, false);
        FieldCipher.Aad a0 = patientAad(UUID.fromString(patients.get(0)[0]), patients.get(0)[1]);
        assertThat(rotated.status(nid(patients.get(0)[1]), a0)).isEqualTo(FieldCipher.Status.OLD_KID);
        assertThat(rotated.encrypt(TOTP_SECRET, staffAad(s))[1]).isEqualTo((byte) 2);
        CryptoReencryptJob rotateJob = new CryptoReencryptJob(jdbc, txManager, tenantContext, rotated, 1000, false);
        try {
            assertThat(rotateJob.run(Integer.MAX_VALUE).finished()).isTrue();
            assertThat(su.queryForObject("SELECT count(*) FROM crypto_reencrypt_progress WHERE target_kid <> 2", Integer.class)).isZero();
            for (String[] p : patients) assertThat(nid(p[1])[1]).isEqualTo((byte) 2);
            assertThat(rotateJob.verify()).allSatisfy(c -> {
                assertThat(c.clean()).isTrue();
                assertThat(c.counts().get(FieldCipher.Status.OLD_KID)).isZero();
            });
        } finally { // rotate back so the app context (kid 1 only) can read every row again
            FieldCipher back = new FieldCipher("1:" + CRYPTO_KEY_B64 + ",2:" + k2, "1", CRYPTO_KEY_B64, false);
            assertThat(new CryptoReencryptJob(jdbc, txManager, tenantContext, back, 1000, false).run(Integer.MAX_VALUE).finished()).isTrue();
        }
        assertThat(nid(patients.get(0)[1])[1]).isEqualTo((byte) 1);
        assertThat(staffMfa(s)).isEqualTo(HttpStatus.OK);
        assertThat(job.verify()).allSatisfy(c -> assertThat(c.clean()).isTrue());
    }

    @Test
    void prodProfileWithoutKeyringRefusesToBoot() throws Exception {
        String jwks = new JWKSet(new ArrayList<JWK>(List.of(SigningKeyProvider.gen(), SigningKeyProvider.gen()))).toString(false);
        assertThatThrownBy(() -> new SpringApplicationBuilder(NabdHmsApplication.class).run(
                "--spring.profiles.active=prod", "--server.port=0", "--management.server.port=0", "--spring.flyway.enabled=false",
                "--spring.datasource.url=" + env.getProperty("spring.datasource.url"),
                "--spring.datasource.username=" + env.getProperty("spring.datasource.username"),
                "--spring.datasource.password=" + env.getProperty("spring.datasource.password"),
                "--app.crypto.key=" + CRYPTO_KEY_B64, "--app.jwt.keys-json=" + jwks,
                "--app.outbox.poll-interval-ms=" + Integer.MAX_VALUE))
                .hasStackTraceContaining("Refusing to boot under the prod profile without a keyring");
    }
}

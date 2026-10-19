package com.nabd.hms.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PLT-044 unit: v1 format, AAD binding, legacy/v1 version dispatch, keyring rotation, prod keyring guard, speed. */
class FieldCipherTest {

    private static final SecureRandom RND = new SecureRandom();
    private static final String K1 = newKey();
    private static final String K2 = newKey();
    private static final byte[] PLAIN = "123456789012".getBytes(StandardCharsets.UTF_8);
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ROW_A = UUID.randomUUID();
    private static final FieldCipher.Aad A = FieldCipher.Aad.of(TENANT, "patients", "national_id_enc", ROW_A);

    static String newKey() {
        byte[] k = new byte[32];
        RND.nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    /** The pre-PLT-044 AesGcmCipher format: [IV 12][ciphertext+tag], no AAD. */
    static byte[] legacyEncrypt(String keyB64, byte[] plain, byte[] iv) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(keyB64), "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain);
        byte[] out = new byte[12 + ct.length];
        System.arraycopy(iv, 0, out, 0, 12);
        System.arraycopy(ct, 0, out, 12, ct.length);
        return out;
    }

    static byte[] randomIv() {
        byte[] iv = new byte[12];
        RND.nextBytes(iv);
        return iv;
    }

    private static FieldCipher ring1() {
        return new FieldCipher("1:" + K1, "1", K1, false);
    }

    @Test
    void newValueStartsWithVersionAndKidAndOpensOnlyWithItsAad() {
        FieldCipher c = new FieldCipher("7:" + K1, "7", "", false);
        byte[] blob = c.encrypt(PLAIN, A);
        assertThat(blob[0]).isEqualTo((byte) 1);
        assertThat(blob[1] & 0xff).isEqualTo(7);
        assertThat(blob).hasSize(2 + 12 + PLAIN.length + 16);
        assertThat(c.decrypt(blob, A)).isEqualTo(PLAIN);
        assertThat(c.encrypt(PLAIN, A)).isNotEqualTo(blob); // fresh IV every time

        for (FieldCipher.Aad other : new FieldCipher.Aad[]{
                FieldCipher.Aad.of(TENANT, "patients", "national_id_enc", UUID.randomUUID()),  // another row, same tenant
                FieldCipher.Aad.of(UUID.randomUUID(), "patients", "national_id_enc", ROW_A), // another tenant
                FieldCipher.Aad.of(TENANT, "staff", "national_id_enc", ROW_A),               // another table
                FieldCipher.Aad.of(TENANT, "patients", "mfa_secret", ROW_A)}) {              // another column
            assertThatThrownBy(() -> c.decrypt(blob, other)).isInstanceOf(FieldCipher.DecryptException.class)
                    .hasMessageNotContaining("123456789012");
            assertThat(c.tryDecrypt(blob, other)).isEmpty();
            assertThat(c.status(blob, other)).isEqualTo(FieldCipher.Status.FAILED);
        }
    }

    @Test
    void legacyFormatStillDecryptsAndVersionDispatchHandlesALegacyIvThatLooksLikeV1() throws Exception {
        FieldCipher c = ring1();
        byte[] legacy = legacyEncrypt(K1, PLAIN, randomIv());
        assertThat(c.decrypt(legacy, A)).isEqualTo(PLAIN);
        assertThat(c.status(legacy, A)).isEqualTo(FieldCipher.Status.LEGACY);

        byte[] iv = randomIv();
        iv[0] = 1;  // legacy IV that happens to carry the v1 version byte
        iv[1] = 1;  // and the active kid
        byte[] lookalike = legacyEncrypt(K1, PLAIN, iv);
        assertThat(c.decrypt(lookalike, A)).isEqualTo(PLAIN);
        assertThat(c.status(lookalike, A)).isEqualTo(FieldCipher.Status.LEGACY);

        FieldCipher noLegacy = new FieldCipher("1:" + K1, "1", "", false); // APP_CRYPTO_KEY removed after migration
        assertThat(noLegacy.tryDecrypt(legacy, A)).isEmpty();
        assertThat(noLegacy.decrypt(c.encrypt(PLAIN, A), A)).isEqualTo(PLAIN);
    }

    @Test
    void newActiveKidWritesWithItAndOldValuesStillDecrypt() {
        byte[] old = ring1().encrypt(PLAIN, A);
        FieldCipher rotated = new FieldCipher("1:" + K1 + ",2:" + K2, "2", K1, false);
        byte[] fresh = rotated.encrypt(PLAIN, A);
        assertThat(fresh[1] & 0xff).isEqualTo(2);
        assertThat(rotated.decrypt(old, A)).isEqualTo(PLAIN);
        assertThat(rotated.status(old, A)).isEqualTo(FieldCipher.Status.OLD_KID);
        assertThat(rotated.status(fresh, A)).isEqualTo(FieldCipher.Status.CURRENT);
        assertThat(ring1().tryDecrypt(fresh, A)).isEmpty(); // kid 2 unknown to a kid-1-only ring
    }

    @Test
    void prodProfileWithoutKeyringRefusesToBoot() {
        MockEnvironment prod = new MockEnvironment().withProperty("app.crypto.key", K1);
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new FieldCipher(prod)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_CRYPTO_KEYRING").hasMessageNotContaining(K1);

        prod.setProperty("app.crypto.keyring", "1:" + K1);
        assertThat(new FieldCipher(prod).activeKid()).isEqualTo(1);

        MockEnvironment dev = new MockEnvironment().withProperty("app.crypto.key", K1); // non-prod: legacy key doubles as kid 1
        assertThat(new FieldCipher(dev).activeKid()).isEqualTo(1);
        assertThatThrownBy(() -> new FieldCipher(new MockEnvironment())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyringIsValidatedWithoutEchoingKeys() {
        assertThatThrownBy(() -> new FieldCipher("1:" + K1 + ",2:" + K2, "", "", false)).hasMessageContaining("active-kid");
        assertThatThrownBy(() -> new FieldCipher("1:" + K1, "3", "", false)).hasMessageContaining("not in the keyring");
        assertThatThrownBy(() -> new FieldCipher("1:" + K1 + ",1:" + K2, "1", "", false)).hasMessageNotContaining(K1);
        assertThatThrownBy(() -> new FieldCipher("0:" + K1, "", "", false)).hasMessageContaining("1-255");
        assertThatThrownBy(() -> new FieldCipher("1:" + Base64.getEncoder().encodeToString(new byte[16]), "", "", false))
                .hasMessageContaining("32 bytes");
    }

    @Test
    void encryptPlusDecryptStaysUnderPointTwoMsPerField() {
        FieldCipher c = ring1();
        for (int i = 0; i < 2000; i++) c.decrypt(c.encrypt(PLAIN, A), A); // warm-up (JIT)
        int n = 5000;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) c.decrypt(c.encrypt(PLAIN, A), A);
        double msPerOp = (System.nanoTime() - t0) / 1e6 / (2.0 * n);
        assertThat(msPerOp).isLessThan(0.2);
    }
}

package com.nabd.hms.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * PLT-044: AES-256-GCM for fields at rest (MFA secrets, national IDs, idempotency replay bodies).
 * Format v1 = [0x01][kid 1 byte][96-bit IV][ciphertext+tag]; AAD = tenantId:table:column:rowId, so a
 * ciphertext copied to another row or tenant does not decrypt. Keys: app.crypto.keyring ("kid:base64,...",
 * kid 1-255) with one app.crypto.active-kid for writes. Reads also accept the pre-PLT-044 format
 * ([IV][ciphertext+tag], no AAD) under app.crypto.key until CryptoReencryptJob has rewritten every row.
 * PLT-045: with per-tenant keys on (app.kms.provider) a tenant that has a DEK writes format v2 =
 * [0x02][DEK version 1 byte][96-bit IV][ciphertext+tag], same AAD; the DEK comes from TenantKeyService (wrapped by the
 * region KMS). Tenants without a DEK, and platform rows, stay on the global keyring (v1); v1 and legacy values keep opening.
 * Key material is never logged or put in exception messages.
 */
@Component
public class FieldCipher {

    private static final Logger log = LoggerFactory.getLogger(FieldCipher.class);
    static final byte VERSION = 1;
    static final byte VERSION_TENANT = 2;
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int HEADER = 2;

    /** Binds a ciphertext to its tenant ("platform" for master.* rows), table, logical column and row key. */
    public record Aad(String tenant, String table, String column, String rowId) {
        public static Aad of(Object tenant, String table, String column, Object rowId) {
            return new Aad(String.valueOf(tenant), table, column, String.valueOf(rowId));
        }

        byte[] bytes() {
            return (tenant + ":" + table + ":" + column + ":" + rowId).getBytes(StandardCharsets.UTF_8);
        }
    }

    public static class DecryptException extends RuntimeException {
        DecryptException(String message) {
            super(message);
        }
    }

    private final Map<Integer, SecretKeySpec> keys;
    private final int activeKid;
    private final SecretKeySpec legacyKey; // null = legacy reads off (every row re-encrypted, APP_CRYPTO_KEY removed)
    private final SecureRandom random = new SecureRandom();
    private com.nabd.hms.common.tenantkey.TenantKeyService tenantKeys; // PLT-045; null in unit tests that build the cipher by hand

    @Autowired(required = false)
    void tenantKeys(com.nabd.hms.common.tenantkey.TenantKeyService tenantKeys) {
        this.tenantKeys = tenantKeys;
    }

    @Autowired
    public FieldCipher(Environment env) {
        this(env.getProperty("app.crypto.keyring", ""), env.getProperty("app.crypto.active-kid", ""),
                env.getProperty("app.crypto.key", ""), env.acceptsProfiles(Profiles.of("prod")));
    }

    public FieldCipher(String keyring, String activeKid, String legacyKeyB64, boolean prod) {
        this.legacyKey = legacyKeyB64 == null || legacyKeyB64.isBlank() ? null : key(legacyKeyB64, "app.crypto.key");
        Map<Integer, SecretKeySpec> ring = new TreeMap<>();
        if (keyring != null && !keyring.isBlank()) {
            for (String entry : keyring.split(",")) {
                String[] kv = entry.trim().split(":", 2);
                int kid = kid(kv[0]);
                if (kv.length != 2 || ring.put(kid, key(kv[1], "keyring kid " + kid)) != null) {
                    throw new IllegalStateException("app.crypto.keyring entries must be distinct kid:base64 pairs");
                }
            }
        } else if (prod) {
            throw new IllegalStateException("app.crypto.keyring (APP_CRYPTO_KEYRING) is not configured. Refusing to boot under the prod profile without a keyring.");
        } else if (legacyKey != null) {
            ring.put(1, legacyKey); // dev/test: the single legacy key doubles as kid 1
        } else {
            throw new IllegalStateException("Neither app.crypto.keyring nor app.crypto.key is configured");
        }
        if (activeKid == null || activeKid.isBlank()) {
            if (ring.size() != 1) throw new IllegalStateException("app.crypto.active-kid is required when the keyring holds several keys");
            this.activeKid = ring.keySet().iterator().next();
        } else {
            this.activeKid = kid(activeKid);
            if (!ring.containsKey(this.activeKid)) throw new IllegalStateException("app.crypto.active-kid is not in the keyring");
        }
        this.keys = Map.copyOf(ring);
    }

    private static int kid(String s) {
        try {
            int kid = Integer.parseInt(s.trim());
            if (kid >= 1 && kid <= 255) return kid;
        } catch (NumberFormatException ignored) {
            // fall through
        }
        throw new IllegalStateException("crypto key ids must be integers 1-255");
    }

    private static SecretKeySpec key(String b64, String name) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(b64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " is not valid base64");
        }
        if (raw.length != 32) throw new IllegalStateException(name + " must be 32 bytes (AES-256)");
        return new SecretKeySpec(raw, "AES");
    }

    public int activeKid() {
        return activeKid;
    }

    /** CURRENT = v1 under the active kid; OLD_KID = v1 under another keyring kid; LEGACY = only the pre-PLT-044 format opens. */
    public enum Status { CURRENT, OLD_KID, LEGACY, FAILED }

    public Status status(byte[] blob, Aad aad) {
        try {
            decryptNew(blob, aad);
            if (blob[0] == VERSION_TENANT) return tenantKeys.activeKey(aad.tenant()).filter(k -> k.version() == (blob[1] & 0xff)).isPresent() ? Status.CURRENT : Status.OLD_KID;
            // a tenant with its own DEK is only CURRENT once its values are v2 under the active DEK
            return tenantKeys != null && tenantKeys.activeKey(aad.tenant()).isPresent() ? Status.OLD_KID
                    : (blob[1] & 0xff) == activeKid ? Status.CURRENT : Status.OLD_KID;
        } catch (DecryptException e) {
            return decryptLegacy(blob) != null ? Status.LEGACY : Status.FAILED;
        }
    }

    public byte[] encrypt(byte[] plaintext, Aad aad) {
        try {
            var tenantKey = tenantKeys == null ? java.util.Optional.<com.nabd.hms.common.tenantkey.TenantKeyService.ActiveKey>empty() : tenantKeys.activeKey(aad.tenant());
            byte[] out = new byte[HEADER + IV_LENGTH + plaintext.length + TAG_LENGTH_BITS / 8];
            out[0] = tenantKey.isPresent() ? VERSION_TENANT : VERSION;
            out[1] = (byte) (tenantKey.isPresent() ? tenantKey.get().version() : activeKid);
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            System.arraycopy(iv, 0, out, HEADER, IV_LENGTH);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, tenantKey.isPresent() ? tenantKey.get().key() : keys.get(activeKid), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            c.updateAAD(aad.bytes());
            c.doFinal(plaintext, 0, plaintext.length, out, HEADER + IV_LENGTH);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("field encryption failed");
        }
    }

    /** Throws DecryptException (message names no values) on wrong row/tenant, unknown kid or corruption. */
    public byte[] decrypt(byte[] blob, Aad aad) {
        try {
            return decryptNew(blob, aad);
        } catch (DecryptException e) {
            byte[] legacy = decryptLegacy(blob); // a legacy IV may start with 0x01 by chance: v1 is tried first, then this
            if (legacy != null) return legacy;
            throw new DecryptException("encrypted field " + aad.table() + "." + aad.column() + " did not decrypt");
        }
    }

    byte[] decryptNew(byte[] blob, Aad aad) {
        if (blob != null && blob.length >= HEADER + IV_LENGTH + 16 && (blob[0] == VERSION || blob[0] == VERSION_TENANT && tenantKeys != null)) {
            SecretKeySpec k = blob[0] == VERSION ? keys.get(blob[1] & 0xff) : tenantKeys.keyFor(aad.tenant(), blob[1] & 0xff).orElse(null);
            byte[] plain = k == null ? null : open(k, blob, HEADER, aad.bytes());
            if (plain != null) return plain;
        }
        throw new DecryptException("not a v1 value for " + aad.table() + "." + aad.column());
    }

    /** API read paths: an undecryptable value is reported as unavailable (logged without values), never a 500. */
    public Optional<byte[]> tryDecrypt(byte[] blob, Aad aad) {
        try {
            return Optional.of(decrypt(blob, aad));
        } catch (DecryptException e) {
            log.warn("encrypted field {}.{} for row {} did not decrypt (copied from another row, unknown kid or corrupt)",
                    aad.table(), aad.column(), aad.rowId());
            return Optional.empty();
        }
    }

    private byte[] decryptLegacy(byte[] blob) {
        return legacyKey == null || blob == null || blob.length < IV_LENGTH + 16 ? null : open(legacyKey, blob, 0, null);
    }

    private static byte[] open(SecretKeySpec k, byte[] blob, int ivAt, byte[] aad) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_LENGTH_BITS, blob, ivAt, IV_LENGTH));
            if (aad != null) c.updateAAD(aad);
            int at = ivAt + IV_LENGTH;
            return c.doFinal(blob, at, blob.length - at);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }
}

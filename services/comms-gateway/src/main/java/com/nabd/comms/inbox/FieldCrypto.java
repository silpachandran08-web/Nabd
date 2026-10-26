package com.nabd.comms.inbox;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/** COM-030: AES-256-GCM for message text and phone numbers at rest (iv || ciphertext), HMAC-SHA256 for the searchable phone hash. */
public class FieldCrypto {

    private final SecretKeySpec aes;
    private final SecretKeySpec hmac;
    private final SecureRandom random = new SecureRandom();

    public FieldCrypto(byte[] key32) {
        if (key32.length != 32) {
            throw new IllegalArgumentException("field key must be 32 bytes");
        }
        this.aes = new SecretKeySpec(key32, "AES");
        this.hmac = new SecretKeySpec(mac(key32, "phone-hash"), "HmacSHA256"); // separate key for hashing
    }

    public byte[] encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, aes, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public String decrypt(byte[] enc) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, aes, new GCMParameterSpec(128, enc, 0, 12));
            return new String(c.doFinal(enc, 12, enc.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed", e);
        }
    }

    public byte[] hash(String normalizedPhone) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(hmac);
            return m.doFinal(normalizedPhone.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] mac(byte[] key, String label) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(label.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

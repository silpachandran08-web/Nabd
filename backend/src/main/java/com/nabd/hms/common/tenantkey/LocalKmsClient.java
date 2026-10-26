package com.nabd.hms.common.tenantkey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PLT-045 software KMS double for dev and tests (app.kms.provider=local). Key material per ref = HMAC-SHA256(master, ref),
 * so refs survive a restart. The destroyed-tenant set lives in memory only (a real KMS holds it outside the database,
 * which is what makes a restored backup useless); never use this adapter in prod. The real KMS adapter is Needs external action.
 */
@Component
@ConditionalOnProperty(name = "app.kms.provider", havingValue = "local")
public class LocalKmsClient implements KmsClient {

    private final byte[] master;
    private final SecureRandom random = new SecureRandom();
    private final Set<String> destroyed = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean outage = new AtomicBoolean();

    public LocalKmsClient(@Value("${app.kms.local-master-key:}") String masterB64) {
        if (masterB64.isBlank()) throw new IllegalStateException("app.kms.local-master-key is required when app.kms.provider=local");
        this.master = Base64.getDecoder().decode(masterB64.trim());
        if (master.length < 32) throw new IllegalStateException("app.kms.local-master-key must be at least 32 bytes");
    }

    /** Test hook: simulates the regional KMS being unreachable. */
    public void simulateOutage(boolean down) {
        outage.set(down);
    }

    @Override
    public String createKey(String region) {
        return "local:" + region + ":1";
    }

    @Override
    public String rotateKey(String region, String currentRef) {
        return "local:" + region + ":" + (Integer.parseInt(currentRef.substring(currentRef.lastIndexOf(':') + 1)) + 1);
    }

    @Override
    public byte[] wrap(String keyRef, byte[] dek, byte[] context) {
        check();
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(keyRef), new GCMParameterSpec(128, iv));
            c.updateAAD(context);
            byte[] ct = c.doFinal(dek);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("kms wrap failed");
        }
    }

    @Override
    public byte[] unwrap(String keyRef, byte[] wrapped, byte[] context) {
        check();
        if (destroyed.contains(new String(context, StandardCharsets.UTF_8))) throw new KmsKeyDestroyedException("tenant key destroyed");
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(keyRef), new GCMParameterSpec(128, wrapped, 0, 12));
            c.updateAAD(context);
            return c.doFinal(wrapped, 12, wrapped.length - 12);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new KmsKeyDestroyedException("wrapped key does not unwrap for this tenant"); // wrong tenant, ref or corrupt
        }
    }

    @Override
    public void destroyTenant(String tenantContext) {
        destroyed.add(tenantContext);
    }

    private void check() {
        if (outage.get()) throw new KmsUnavailableException("kms unreachable");
    }

    private SecretKeySpec key(String ref) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(master, "HmacSHA256"));
        return new SecretKeySpec(mac.doFinal(ref.getBytes(StandardCharsets.UTF_8)), "AES");
    }
}

package com.nabd.comms.sender;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * COM-011: opaque, tenant-bound, HMAC-SHA256-signed button payload "v1.<b64url(tenant|action|expiryEpoch)>.<b64url(mac)>",
 * 30-day expiry. The tenant is read from the verified token, never from anything the patient can type.
 * The key comes from the secret store (key id in config); rotating it invalidates outstanding buttons by design.
 */
public final class ButtonTokens {

    public record Verified(UUID tenantId, String action) {}

    public static final Duration TTL = Duration.ofDays(30);
    private final byte[] key;
    private final Clock clock;

    public ButtonTokens(byte[] key, Clock clock) {
        if (key.length < 32) {
            throw new IllegalArgumentException("button token key must be at least 32 bytes");
        }
        this.key = key.clone();
        this.clock = clock;
    }

    public String issue(UUID tenantId, String action) {
        String body = b64(tenantId + "|" + action + "|" + clock.instant().plus(TTL).getEpochSecond());
        return "v1." + body + "." + b64(mac(body));
    }

    /** Empty for a forged, truncated, tampered or expired token. */
    public Optional<Verified> verify(String token) {
        try {
            String[] p = token.split("\\.", -1);
            if (p.length != 3 || !p[0].equals("v1") || !MessageDigest.isEqual(mac(p[1]), Base64.getUrlDecoder().decode(p[2]))) {
                return Optional.empty();
            }
            String[] f = new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8).split("\\|", 3);
            if (Instant.ofEpochSecond(Long.parseLong(f[2])).isBefore(clock.instant())) {
                return Optional.empty();
            }
            return Optional.of(new Verified(UUID.fromString(f[0]), f[1]));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private byte[] mac(String body) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String s) {
        return b64(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}

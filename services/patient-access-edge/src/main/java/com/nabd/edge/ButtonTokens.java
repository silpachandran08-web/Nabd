package com.nabd.edge;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Signed, tenant-bound, expiring button payloads (COM-011 scheme: HMAC-SHA256, 30-day expiry).
 * Form: base64url("v1|tenant|action|expiryEpochSeconds") "." base64url(hmac). Forged, expired, other-tenant or
 * unkeyed tokens verify to empty.
 */
@Component
public class ButtonTokens {

    private static final Pattern ACTION = Pattern.compile("[A-Za-z0-9_:.-]{1,40}");
    private final byte[] key;
    private final Clock clock;

    ButtonTokens(EdgeProperties p, Clock clock) {
        this.clock = clock;
        byte[] k = p.buttonKey().isBlank() ? new byte[0] : Base64.getDecoder().decode(p.buttonKey());
        if (k.length != 0 && k.length < 32) {
            throw new IllegalStateException("edge.button-key must be at least 32 bytes");
        }
        this.key = k;
    }

    public String issue(UUID tenantId, String action, Duration ttl) {
        if (key.length == 0 || !ACTION.matcher(action).matches()) {
            throw new IllegalStateException("cannot issue button token");
        }
        String body = b64("v1|" + tenantId + "|" + action + "|" + (clock.instant().plus(ttl).getEpochSecond()));
        return body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(body));
    }

    /** The action when the token is genuine, unexpired and issued for this tenant. */
    public Optional<String> verify(String token, UUID tenantId) {
        try {
            int dot = token == null ? -1 : token.indexOf('.');
            if (key.length == 0 || dot < 1) {
                return Optional.empty();
            }
            String body = token.substring(0, dot);
            byte[] given = Base64.getUrlDecoder().decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(given, mac(body))) {
                return Optional.empty();
            }
            String[] f = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8).split("\\|", -1);
            boolean ok = f.length == 4 && f[0].equals("v1") && f[1].equals(tenantId.toString()) && ACTION.matcher(f[2]).matches()
                    && Long.parseLong(f[3]) > clock.instant().getEpochSecond();
            return ok ? Optional.of(f[2]) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] mac(String body) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

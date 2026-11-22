package com.nabd.hms.organization.offboarding;

import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.shared.rxsign.RxSigningKeys;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * ADM-016: signs a deletion certificate. The certificate is put in RFC 8785 (JCS) form and signed with ES256 (raw r||s,
 * base64url) by Nabd's deletion-certificate key: the same JWKS pattern as the Rx QR keys ({@link RxSigningKeys}), under
 * app.deletion-cert.keys-file / keys-json (DELETION_CERT_KEYS_FILE / DELETION_CERT_KEYS_JSON). No key lives in code.
 * The keys load on first use, so the prod profile refuses to SIGN without them rather than to boot; the erasure worker asks
 * for them before it erases anything. Outside prod a fresh key per boot is used (certificates stop verifying after a restart).
 */
@Component
public class DeletionCertificateSigner {

    static final String MISSING = "Deletion-certificate signing keys are not configured: set DELETION_CERT_KEYS_FILE (path of the "
            + "mounted secret) or DELETION_CERT_KEYS_JSON (a JWKS with 1+ EC P-256 private keys with kids).";

    public record Signed(String canonical, String sha256, String signature, String kid) {
    }

    private final Environment env;
    private volatile RxSigningKeys keys;

    DeletionCertificateSigner(Environment env) {
        this.env = env;
    }

    /** Throws IllegalStateException under the prod profile when no key is configured. */
    RxSigningKeys keys() {
        RxSigningKeys k = keys;
        if (k == null) {
            synchronized (this) {
                if (keys == null) keys = new RxSigningKeys(env, "app.deletion-cert", MISSING);
                k = keys;
            }
        }
        return k;
    }

    Signed sign(Map<String, Object> certificate) {
        String canonical = canonical(certificate);
        byte[] signature = keys().sign(canonical.getBytes(StandardCharsets.UTF_8));
        return new Signed(canonical, OpaqueTokens.sha256Hex(canonical), Base64.getUrlEncoder().withoutPadding().encodeToString(signature), keys().activeKid());
    }

    boolean verify(String kid, Map<String, Object> certificate, String signature) {
        try {
            return keys().verify(kid, canonical(certificate).getBytes(StandardCharsets.UTF_8), Base64.getUrlDecoder().decode(signature));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    Map<String, Object> publicJwks() {
        return keys().publicJwks();
    }

    /**
     * RFC 8785 for the values a certificate holds: objects (member names sorted by UTF-16 code unit, which is String's own
     * order), arrays, strings, integers, booleans and null. Anything else (a fraction would need the ES6 number form) is refused.
     */
    static String canonical(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null || v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof String s) {
            string(sb, s);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : new TreeMap<String, Object>(cast(m)).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                string(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("a deletion certificate cannot hold a " + v.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}

package com.nabd.hms.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * COM-002: phone-level opt-outs (messaging_suppressions, V1851), read at send time next to the per-patient consent check.
 * tenant null = every clinic on the shared number. Phones are stored as HMAC-SHA256 of the digits, keyed with
 * app.whatsapp.phone-pepper, or app.whatsapp.app-secret when that is blank (the webhook cannot work without it).
 * shortcut: rotating that key orphans existing hashes; add a pepper id column before the first rotation.
 * Call inside a transaction; the functions are SECURITY DEFINER, so no tenant needs to be bound for them.
 */
@Component
public class OptOutRegistry {

    public static final String MESSAGING = "messaging";
    public static final String MARKETING = "marketing";

    private final JdbcTemplate jdbc;
    private final byte[] pepper;

    OptOutRegistry(JdbcTemplate jdbc, WhatsAppProperties props, @org.springframework.beans.factory.annotation.Value("${app.whatsapp.phone-pepper:}") String pepper) {
        this.jdbc = jdbc;
        String key = pepper != null && !pepper.isBlank() ? pepper : props.appSecret();
        this.pepper = key == null || key.isBlank() ? null : key.getBytes(StandardCharsets.UTF_8);
    }

    /** True when a new suppression was recorded, false when one was already active. */
    boolean suppress(UUID tenantOrNull, String phone, String purpose, String source) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT add_messaging_suppression(?, ?, ?, ?)", Boolean.class,
                tenantOrNull, hash(phone), purpose, source));
    }

    int lift(UUID tenantOrNull, String phone, String purpose) {
        return jdbc.queryForObject("SELECT lift_messaging_suppression(?, ?, ?)", Integer.class, tenantOrNull, hash(phone), purpose);
    }

    /** Fails open when no key is configured: nothing could have been recorded without one either. */
    public boolean isSuppressed(UUID tenantId, String phone, String purpose) {
        return pepper != null && Boolean.TRUE.equals(jdbc.queryForObject("SELECT messaging_suppressed(?, ?, ?)", Boolean.class,
                tenantId, hash(phone), purpose));
    }

    static String digits(String phone) {
        return phone == null ? "" : phone.replaceAll("\\D", "");
    }

    private byte[] hash(String phone) {
        if (pepper == null) {
            throw new IllegalStateException("app.whatsapp.app-secret (or phone-pepper) is not configured: cannot record an opt-out");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return mac.doFinal(digits(phone).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

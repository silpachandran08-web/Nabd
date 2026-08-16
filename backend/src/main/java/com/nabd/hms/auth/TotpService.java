package com.nabd.hms.auth;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;

/**
 * RFC 6238 TOTP over the JDK's own HMAC (no third-party TOTP library —
 * javax.crypto.Mac already provides the one primitive this needs).
 */
@Component
public class TotpService {

    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int DRIFT_STEPS = 1; // tolerate ±30s clock drift

    public boolean verify(byte[] secret, String code, Instant at) {
        long counter = at.getEpochSecond() / STEP_SECONDS;
        for (long i = -DRIFT_STEPS; i <= DRIFT_STEPS; i++) {
            if (generate(secret, counter + i).equals(code)) {
                return true;
            }
        }
        return false;
    }

    String generate(byte[] secret, long counter) {
        byte[] data = ByteBuffer.allocate(8).putLong(counter).array();
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0xF;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = (int) (binary % Math.pow(10, DIGITS));
            return String.format("%0" + DIGITS + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }
}

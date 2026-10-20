package com.nabd.hms.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Instant;

/** PLT-005: RFC 6238 codes for a secret the server just generated (base32 from an enrol response). */
public final class TotpTestCodes {

    private TotpTestCodes() {
    }

    public static byte[] base32(String s) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : s.replace("=", "").toUpperCase().toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    public static String code(byte[] secret, int stepOffset) {
        long counter = Instant.now().getEpochSecond() / 30 + stepOffset;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int o = hash[hash.length - 1] & 0xF;
            int bin = ((hash[o] & 0x7f) << 24) | ((hash[o + 1] & 0xff) << 16) | ((hash[o + 2] & 0xff) << 8) | (hash[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

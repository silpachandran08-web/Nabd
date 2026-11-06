package com.nabd.hms.shared.rxsign;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * PHL-013: the Rx QR payload, {@code NRX1.<tenant>.<rx>.<signedAt>.<validUntil>.<kid>.<regNo>.<sig>}. Ids are base64url of the
 * 16 UUID bytes, times are epoch seconds (validUntil empty = never expires), kid and regNo are base64url text, sig is ES256
 * (raw r||s) over the UTF-8 bytes of everything before the last dot. Compact on purpose: the in-house QR encoder takes up to
 * 213 bytes. No names and no medicines: the pharmacy reads those from the server after the signature checks out.
 */
public final class RxQr {

    public static final String PREFIX = "NRX1";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    public record Claims(UUID tenantId, UUID prescriptionId, Instant signedAt, Instant validUntil, String kid, String regNo) {
    }

    /** What a scanned payload parses to; {@link #signatureValid} says whether it was signed by one of our keys. */
    public record Parsed(Claims claims, boolean signatureValid) {
    }

    private RxQr() {
    }

    public static String encode(Claims c, RxSigningKeys keys) {
        String head = String.join(".", PREFIX, uuid(c.tenantId()), uuid(c.prescriptionId()), Long.toString(c.signedAt().getEpochSecond()),
                c.validUntil() == null ? "" : Long.toString(c.validUntil().getEpochSecond()), text(c.kid()), text(c.regNo()));
        return head + "." + B64.encodeToString(keys.sign(head.getBytes(StandardCharsets.UTF_8)));
    }

    /** Empty when the text is not an Rx QR at all (wrong prefix, wrong field count, undecodable field). */
    public static Optional<Parsed> decode(String payload, RxSigningKeys keys) {
        if (payload == null) return Optional.empty();
        String[] f = payload.trim().split("\\.", -1);
        if (f.length != 8 || !PREFIX.equals(f[0])) return Optional.empty();
        try {
            Claims c = new Claims(uuid(f[1]), uuid(f[2]), Instant.ofEpochSecond(Long.parseLong(f[3])),
                    f[4].isEmpty() ? null : Instant.ofEpochSecond(Long.parseLong(f[4])), textOrNull(f[5]), textOrNull(f[6]));
            String head = payload.trim().substring(0, payload.trim().lastIndexOf('.'));
            return Optional.of(new Parsed(c, keys.verify(c.kid(), head.getBytes(StandardCharsets.UTF_8), B64D.decode(f[7]))));
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            return Optional.empty();
        }
    }

    private static String uuid(UUID u) {
        return B64.encodeToString(ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array());
    }

    private static UUID uuid(String s) {
        ByteBuffer b = ByteBuffer.wrap(B64D.decode(s));
        if (b.remaining() != 16) throw new IllegalArgumentException("not a uuid");
        return new UUID(b.getLong(), b.getLong());
    }

    private static String text(String s) {
        return s == null ? "" : B64.encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String textOrNull(String s) {
        return s.isEmpty() ? null : new String(B64D.decode(s), StandardCharsets.UTF_8);
    }
}

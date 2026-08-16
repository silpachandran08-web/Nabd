package com.nabd.hms.common;

import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Opaque (createdAt, id) cursor for stable-order "list" endpoints — every cursor-paginated resource shares this. */
public record Cursor(Instant createdAt, UUID id) {

    public String encode() {
        // Instant.toString(), not epochMilli — Postgres timestamptz has microsecond precision and
        // truncating to millis lets the reconstructed cursor land before the real row, re-matching it.
        String raw = createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 2);
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid cursor",
                    "The pagination cursor is malformed.");
        }
    }
}

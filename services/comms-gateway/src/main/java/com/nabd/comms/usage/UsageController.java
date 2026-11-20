package com.nabd.comms.usage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * COM-015: {@code GET /internal/v1/usage?month=YYYY-MM}, the clinic's message counts and cost for one calendar month. Core is the
 * only caller: it has checked the staff permission and sends the clinic it resolved (same service token as /internal/v1/senders).
 */
@RestController
class UsageController {

    private final UsageService usage;
    private final byte[] token;

    UsageController(UsageService usage, @Value("${comms.internal-token:}") String token) {
        this.usage = usage;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/internal/v1/usage")
    UsageService.Month month(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant,
                             @RequestParam String month) {
        if (token.length == 0 || t == null || !MessageDigest.isEqual(token, t.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        try {
            return usage.month(tenant, YearMonth.parse(month));
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_MONTH");
        }
    }
}

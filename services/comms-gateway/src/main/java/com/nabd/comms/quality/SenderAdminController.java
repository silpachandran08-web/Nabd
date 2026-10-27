package com.nabd.comms.quality;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

/**
 * COM-022: Core is the only caller of /internal/v1/senders. It has already checked the staff or operator permission and sends the clinic it
 * resolved. shortcut: a shared service token (comms.internal-token, blank = API closed) stands in for the mTLS aud=internal identity (PLT-009).
 */
@RestController
@RequestMapping("/internal/v1/senders")
class SenderAdminController {

    private final SenderHealthService health;
    private final byte[] token;

    SenderAdminController(SenderHealthService health, @Value("${comms.internal-token:}") String token) {
        this.health = health;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/health")
    SenderHealthService.Health health(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant) {
        auth(t);
        return health.health(tenant);
    }

    /** Header label only (High / Medium / Low / null), for roles that may not open the health page. */
    @GetMapping("/quality")
    Map<String, String> quality(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant) {
        auth(t);
        String label = health.qualityLabel(tenant);
        return label == null ? Map.of() : Map.of("quality", label);
    }

    @GetMapping
    SenderHealthService.FleetPage fleet(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestParam(required = false) String rating,
                                        @RequestParam(required = false) String region, @RequestParam(required = false) String cursor,
                                        @RequestParam(defaultValue = "50") int limit) {
        auth(t);
        if (rating != null && !rating.isBlank() && !java.util.Set.of("GREEN", "YELLOW", "RED", "UNKNOWN").contains(rating.toUpperCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_RATING");
        }
        try {
            return health.fleet(rating, region, cursor, limit);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR");
        }
    }

    private void auth(String presented) {
        if (token.length == 0 || presented == null || !MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }
}

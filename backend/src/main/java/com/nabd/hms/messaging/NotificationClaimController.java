package com.nabd.hms.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import com.nabd.hms.common.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * COM-004 claim check, for the Comms Gateway. shortcut: a shared service token (app.internal.comms-token, from the secret
 * store; blank = endpoint closed) stands in for the internal mTLS identity "comms-gateway"; swap it when the gateway is
 * extracted (S5). The tenant comes from the request row, not from the caller.
 */
@RestController
class NotificationClaimController {

    private final NotificationService service;
    private final byte[] token;

    NotificationClaimController(NotificationService service, @Value("${app.internal.comms-token:}") String token) {
        this.service = service;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/internal/v1/notification-requests/{id}")
    NotificationClaim claim(@PathVariable UUID id, @RequestHeader(value = "X-Internal-Token", required = false) String presented) {
        if (token.length == 0 || presented == null || !MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Unauthorized", "Internal service identity required.");
        }
        return service.claim(id);
    }
}

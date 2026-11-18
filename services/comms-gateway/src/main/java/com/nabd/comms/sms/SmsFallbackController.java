package com.nabd.comms.sms;

import com.nabd.comms.inbox.InboxException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * COM-027.3: {@code POST /internal/v1/inbox/messages/{id}/sms}, reception's 'Send as SMS' on a WhatsApp message that was not
 * delivered. Same contract as the other inbox actions: Core has checked inbox:reply and sends the tenant and the acting staff member.
 * No SMS channel configured (no BSP, or not India) = no route (404).
 */
@RestController
@ConditionalOnProperty({"comms.field-key", "comms.sms.bsp.url"})
class SmsFallbackController {

    private final SmsFallback fallback;
    private final byte[] token;

    SmsFallbackController(SmsFallback fallback, @Value("${comms.internal-token:}") String token) {
        this.fallback = fallback;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/internal/v1/inbox/messages/{id}/sms")
    @ResponseStatus(HttpStatus.CREATED)
    SmsFallback.Sent sendAsSms(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant,
                               @RequestHeader("X-Staff-Id") UUID staff, @PathVariable UUID id) {
        if (token.length == 0 || t == null || !MessageDigest.isEqual(token, t.getBytes(StandardCharsets.UTF_8))) {
            throw new InboxException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return fallback.sendAsSms(tenant, id, staff);
    }

    @ExceptionHandler(InboxException.class)
    ResponseEntity<ProblemDetail> handle(InboxException e) {
        ProblemDetail p = ProblemDetail.forStatus(e.status());
        p.setTitle(e.code());
        p.setProperty("code", e.code());
        return ResponseEntity.status(e.status()).body(p);
    }
}

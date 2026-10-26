package com.nabd.comms.inbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * COM-030.3: the Comms side of /v1/inbox/**. Core is the only caller: it has already checked the staff member's permission
 * (inbox:view), written the PHI read log and sends the tenant it resolved. Comms never sees a staff JWT.
 * shortcut: a shared service token (comms.internal-token, blank = API closed) stands in for the mTLS aud=internal identity, like
 * Core's claim endpoint; swap with PLT-009.
 */
@RestController
@RequestMapping("/internal/v1/inbox")
@ConditionalOnProperty("comms.field-key")
class InboxInternalController {

    record ReadRequest(UUID upToMessageId) {}

    record PickRequest(UUID patientRef) {}

    private final InboxService inbox;
    private final byte[] token;

    InboxInternalController(InboxService inbox, @Value("${comms.internal-token:}") String token) {
        this.inbox = inbox;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/threads")
    InboxService.ThreadPage threads(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant,
                                    @RequestParam(defaultValue = "all") String filter, @RequestParam(required = false) String cursor,
                                    @RequestParam(defaultValue = "50") int limit) {
        auth(t);
        return inbox.threads(tenant, filter, cursor, limit);
    }

    @GetMapping("/threads/{id}/messages")
    InboxService.MessagePage messages(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant,
                                      @PathVariable UUID id, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        auth(t);
        return inbox.messages(tenant, id, cursor, limit);
    }

    @PostMapping("/threads/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant,
              @PathVariable UUID id, @RequestBody ReadRequest body) {
        auth(t);
        if (body.upToMessageId() == null) {
            throw new InboxException(HttpStatus.BAD_REQUEST, "UP_TO_MESSAGE_REQUIRED");
        }
        inbox.markRead(tenant, id, body.upToMessageId());
    }

    @PutMapping("/threads/{id}/patient")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void pick(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant,
              @PathVariable UUID id, @RequestBody PickRequest body) {
        auth(t);
        if (body.patientRef() == null) {
            throw new InboxException(HttpStatus.BAD_REQUEST, "PATIENT_REQUIRED");
        }
        inbox.pickPatient(tenant, id, body.patientRef());
    }

    private void auth(String presented) {
        if (token.length == 0 || presented == null || !MessageDigest.isEqual(token, presented.getBytes(StandardCharsets.UTF_8))) {
            throw new InboxException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }
}

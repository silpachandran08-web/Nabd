package com.nabd.hms.messaging;

import com.nabd.hms.messaging.api.SenderDirectory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** COM-022: the owner's WhatsApp health page (setup:view, the integrations permission) and the one-word quality label every role sees in the inbox header. */
@RestController
@RequestMapping("/v1")
public class OwnerMessagingController {

    private final SenderDirectory senders;

    OwnerMessagingController(SenderDirectory senders) {
        this.senders = senders;
    }

    @GetMapping("/owner/messaging/health")
    @PreAuthorize("hasAuthority('setup:view')")
    public SenderDirectory.Health health(@AuthenticationPrincipal Jwt jwt) {
        return senders.health(UUID.fromString(jwt.getClaimAsString("tenantId")));
    }

    @GetMapping("/messaging/quality")
    @PreAuthorize("isAuthenticated()")
    public Map<String, String> quality(@AuthenticationPrincipal Jwt jwt) {
        String q = senders.quality(UUID.fromString(jwt.getClaimAsString("tenantId")));
        return q == null ? Map.of() : Map.of("quality", q);
    }
}

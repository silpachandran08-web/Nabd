package com.nabd.hms.organization.setup;

import com.nabd.hms.organization.setup.dto.MessagingSettingsRequest;
import com.nabd.hms.organization.setup.dto.MessagingSettingsResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** COM-024: quiet hours and prayer windows for patient messages (owner.setup). */
@RestController
@RequestMapping("/v1/setup/messaging-settings")
public class MessagingSettingsController {

    private final MessagingSettingsService service;

    MessagingSettingsController(MessagingSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('setup:view')")
    public MessagingSettingsResponse get() {
        return service.get();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('setup:edit')")
    public MessagingSettingsResponse put(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody MessagingSettingsRequest req) {
        return service.put(UUID.fromString(jwt.getSubject()), req);
    }
}

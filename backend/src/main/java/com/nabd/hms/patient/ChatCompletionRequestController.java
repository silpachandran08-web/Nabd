package com.nabd.hms.patient;

import com.nabd.hms.shared.Idempotent;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** ACC-043: the desk's "Complete in chat" button on a minimal registration. */
@RestController
class ChatCompletionRequestController {

    private final ChatRegistrationService service;

    ChatCompletionRequestController(ChatRegistrationService service) {
        this.service = service;
    }

    @Idempotent
    @PostMapping("/v1/patients/{id}/complete-in-chat")
    @PreAuthorize("hasAuthority('patients:edit')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Boolean> request(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.requestCompletion(UUID.fromString(jwt.getSubject()), id);
        return Map.of("requested", true);
    }
}

package com.nabd.hms.queue;

import jakarta.validation.Valid;
import com.nabd.hms.shared.Idempotent;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ACC-032: the exceptions inbox. Items carry ids and codes only, no patient names (the UI resolves names it is allowed to see). */
@RestController
@RequestMapping("/v1/front-desk/exceptions")
class DeskExceptionController {

    record ActionRequest(String note, Integer snoozeMinutes) {
    }

    private final DeskExceptions service;

    DeskExceptionController(DeskExceptions service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('queue:view')")
    DeskExceptions.Page list(@RequestParam(required = false) String kind, @RequestParam(required = false) String cursor,
                             @RequestParam(defaultValue = "50") int limit) {
        return service.list(kind, cursor, limit);
    }

    /** note is accepted for the client's convenience and deliberately not stored (a row never holds free text). */
    @Idempotent
    @PostMapping("/{id}/{action}")
    @PreAuthorize("hasAuthority('queue:edit')")
    DeskExceptions.Done act(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable String action,
                            @Valid @RequestBody(required = false) ActionRequest req) {
        return service.act(UUID.fromString(jwt.getSubject()), id, action, req == null ? null : req.snoozeMinutes());
    }
}

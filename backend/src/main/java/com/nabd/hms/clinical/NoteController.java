package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.NoteResponse;
import com.nabd.hms.clinical.dto.NoteUpsertRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/clinical/notes")
public class NoteController {

    private final NoteService service;

    NoteController(NoteService service) {
        this.service = service;
    }

    @GetMapping("/{queueEntryId}")
    @PreAuthorize("hasAuthority('clinical:view')")
    public NoteResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId) {
        return service.get(tenantId(jwt), queueEntryId);
    }

    @PatchMapping("/{queueEntryId}")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public NoteResponse upsert(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId,
                                @Valid @RequestBody NoteUpsertRequest req) {
        return service.upsert(tenantId(jwt), queueEntryId, req);
    }

    @PostMapping("/{queueEntryId}/sign")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public NoteResponse sign(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId) {
        return service.sign(tenantId(jwt), queueEntryId);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }
}

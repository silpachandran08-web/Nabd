package com.nabd.hms.queue;

import com.nabd.hms.queue.dto.WaitlistEntryResponse;
import com.nabd.hms.queue.dto.WaitlistJoinRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/waitlist")
public class WaitlistController {

    private final WaitlistService service;

    WaitlistController(WaitlistService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('queue:create')")
    public WaitlistEntryResponse join(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WaitlistJoinRequest req) {
        return service.join(tenantId(jwt), req.doctorId(), req.patientId());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('queue:view')")
    public List<WaitlistEntryResponse> list(@AuthenticationPrincipal Jwt jwt, @RequestParam UUID doctorId) {
        return service.list(tenantId(jwt), doctorId);
    }

    @PostMapping("/{id}/accept")
    @PreAuthorize("hasAuthority('queue:edit')")
    public WaitlistEntryResponse accept(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.accept(tenantId(jwt), staffId(jwt), id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('queue:edit')")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.cancelMembership(tenantId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}

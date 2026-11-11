package com.nabd.hms.queue;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.queue.dto.WaitlistAcceptRequest;
import com.nabd.hms.queue.dto.WaitlistEntryResponse;
import com.nabd.hms.queue.dto.WaitlistJoinRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    public WaitlistEntryResponse join(@CurrentTenant UUID tenantId, @Valid @RequestBody WaitlistJoinRequest req) {
        return service.join(tenantId, req.doctorId(), req.patientId());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('queue:view')")
    public List<WaitlistEntryResponse> list(@CurrentTenant UUID tenantId, @RequestParam UUID doctorId) {
        return service.list(tenantId, doctorId);
    }

    /** Body optional (REC-028): without slotStart this accepts the offered slot; with it, books that chosen slot. */
    @PostMapping("/{id}/accept")
    @PreAuthorize("hasAuthority('queue:edit')")
    public WaitlistEntryResponse accept(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                        @Valid @RequestBody(required = false) WaitlistAcceptRequest req) {
        return service.accept(tenantId, staffId, id, req == null ? null : req.slotStart());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('queue:edit')")
    public ResponseEntity<Void> cancel(@CurrentTenant UUID tenantId, @PathVariable UUID id) {
        service.cancelMembership(tenantId, id);
        return ResponseEntity.noContent().build();
    }
}

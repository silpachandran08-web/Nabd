package com.nabd.hms.queue;

import com.nabd.hms.queue.dto.AppointmentResponse;
import com.nabd.hms.queue.dto.QueueEntryResponse;
import com.nabd.hms.shared.Idempotent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ACC-025: manual no-show, undo (same clinic day) and the late-arrival check-in. */
@RestController
class NoShowController {

    @Schema(name = "NoShowRequest")
    record MarkRequest(String reasonCode) {
    }

    @Schema(name = "LateArrivalRequest")
    record LateRequest(@NotNull UUID appointmentId) {
    }

    private final NoShowService service;

    NoShowController(NoShowService service) {
        this.service = service;
    }

    @Idempotent
    @PostMapping("/v1/appointments/{id}/no-show")
    @PreAuthorize("hasAuthority('queue:edit')")
    AppointmentResponse mark(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody(required = false) MarkRequest req) {
        return service.mark(UUID.fromString(jwt.getSubject()), id, req == null ? null : req.reasonCode());
    }

    @Idempotent
    @PostMapping("/v1/appointments/{id}/no-show/undo")
    @PreAuthorize("hasAuthority('queue:edit')")
    AppointmentResponse undo(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.undo(UUID.fromString(jwt.getSubject()), id);
    }

    @Idempotent
    @PostMapping("/v1/queue/late-arrival")
    @PreAuthorize("hasAuthority('queue:create')")
    ResponseEntity<QueueEntryResponse> lateArrival(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.lateArrival(UUID.fromString(jwt.getSubject()), req.appointmentId()));
    }
}

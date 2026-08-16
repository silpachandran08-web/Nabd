package com.nabd.hms.queue;

import com.nabd.hms.queue.dto.AppointmentPage;
import com.nabd.hms.queue.dto.AppointmentResponse;
import com.nabd.hms.queue.dto.AppointmentWriteRequest;
import com.nabd.hms.queue.dto.CancelRequest;
import com.nabd.hms.queue.dto.RescheduleRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/v1/appointments")
public class AppointmentController {

    private final AppointmentService service;

    AppointmentController(AppointmentService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('queue:view')")
    public AppointmentPage list(@AuthenticationPrincipal Jwt jwt,
                                 @RequestParam(required = false) UUID doctorId,
                                 @RequestParam(required = false) UUID patientId,
                                 @RequestParam(required = false) LocalDate date,
                                 @RequestParam(defaultValue = "20") int limit,
                                 @RequestParam(required = false) String cursor) {
        return service.list(tenantId(jwt), doctorId, patientId, date, limit, cursor);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('queue:create')")
    public ResponseEntity<AppointmentResponse> book(@AuthenticationPrincipal Jwt jwt,
                                                      @Valid @RequestBody AppointmentWriteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.book(tenantId(jwt), staffId(jwt), req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('queue:view')")
    public AppointmentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(tenantId(jwt), id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('queue:edit')")
    public AppointmentResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                       @Valid @RequestBody CancelRequest req) {
        return service.cancel(tenantId(jwt), staffId(jwt), id, req);
    }

    @PostMapping("/{id}/reschedule")
    @PreAuthorize("hasAuthority('queue:edit')")
    public AppointmentResponse reschedule(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                           @Valid @RequestBody RescheduleRequest req) {
        return service.reschedule(tenantId(jwt), staffId(jwt), id, req);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}

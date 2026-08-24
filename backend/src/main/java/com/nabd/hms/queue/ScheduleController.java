package com.nabd.hms.queue;

import com.nabd.hms.queue.dto.DelayAnnounceRequest;
import com.nabd.hms.queue.dto.DoctorDelayResponse;
import com.nabd.hms.queue.dto.DoctorLeaveResponse;
import com.nabd.hms.queue.dto.DoctorLeaveWriteRequest;
import com.nabd.hms.queue.dto.WorkingHoursResponse;
import com.nabd.hms.queue.dto.WorkingHoursWriteRequest;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/doctors/{id}")
public class ScheduleController {

    private final ScheduleService service;

    ScheduleController(ScheduleService service) {
        this.service = service;
    }

    @GetMapping("/working-hours")
    @PreAuthorize("hasAuthority('queue:view')")
    public List<WorkingHoursResponse> listWorkingHours(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.listWorkingHours(tenantId(jwt), id);
    }

    @PostMapping("/working-hours")
    @PreAuthorize("hasAuthority('queue:edit')")
    public ResponseEntity<WorkingHoursResponse> addWorkingHours(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                                  @Valid @RequestBody WorkingHoursWriteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addWorkingHours(tenantId(jwt), staffId(jwt), id, req));
    }

    @GetMapping("/leave")
    @PreAuthorize("hasAuthority('queue:view')")
    public List<DoctorLeaveResponse> listLeave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.listLeave(tenantId(jwt), id);
    }

    @PostMapping("/leave")
    @PreAuthorize("hasAuthority('queue:edit')")
    public ResponseEntity<DoctorLeaveResponse> addLeave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                          @Valid @RequestBody DoctorLeaveWriteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addLeave(tenantId(jwt), staffId(jwt), id, req));
    }

    @GetMapping("/availability")
    @PreAuthorize("hasAuthority('queue:view')")
    public List<Instant> availability(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                       @RequestParam LocalDate date) {
        return service.availability(tenantId(jwt), id, date);
    }

    @PostMapping("/delay")
    @PreAuthorize("hasAuthority('queue:edit')")
    public ResponseEntity<DoctorDelayResponse> announceDelay(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                              @Valid @RequestBody DelayAnnounceRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.announceDelay(tenantId(jwt), staffId(jwt), id, req));
    }

    @PostMapping("/delay/clear")
    @PreAuthorize("hasAuthority('queue:edit')")
    public ResponseEntity<Void> clearDelay(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.clearDelay(tenantId(jwt), staffId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/delay")
    @PreAuthorize("hasAuthority('queue:view')")
    public List<DoctorDelayResponse> delayHistory(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.delayHistory(tenantId(jwt), id);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}

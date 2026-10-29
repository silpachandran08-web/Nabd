package com.nabd.hms.queue;

import com.nabd.hms.queue.dto.DelayAnnounceRequest;
import com.nabd.hms.queue.dto.DelayChangeRequest;
import com.nabd.hms.queue.dto.DelayResponsesResponse;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    private final SessionAvailabilityService sessionAvailability;

    private final DelayBroadcastService broadcast;

    ScheduleController(ScheduleService service, SessionAvailabilityService sessionAvailability, DelayBroadcastService broadcast) {
        this.broadcast = broadcast;
        this.service = service;
        this.sessionAvailability = sessionAvailability;
    }

    @GetMapping("/working-hours")
    @PreAuthorize("hasAuthority('queue:view')")
    public List<WorkingHoursResponse> listWorkingHours(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.listWorkingHours(tenantId(jwt), id);
    }

    // DOC-R7: a doctor writes only their own hours; anyone else needs setup:edit (queue:edit is not enough).
    private static final String CAN_WRITE_HOURS = "hasAuthority('setup:edit') or authentication.name == #id.toString()";

    @PostMapping("/working-hours")
    @PreAuthorize(CAN_WRITE_HOURS)
    public ResponseEntity<WorkingHoursResponse> addWorkingHours(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                                  @Valid @RequestBody WorkingHoursWriteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addWorkingHours(tenantId(jwt), staffId(jwt), id, req));
    }

    @PutMapping("/working-hours/{hoursId}")
    @PreAuthorize(CAN_WRITE_HOURS)
    public WorkingHoursResponse updateWorkingHours(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                   @PathVariable UUID hoursId,
                                                   @Valid @RequestBody WorkingHoursWriteRequest req) {
        return service.updateWorkingHours(tenantId(jwt), staffId(jwt), id, hoursId, req);
    }

    @DeleteMapping("/working-hours/{hoursId}")
    @PreAuthorize(CAN_WRITE_HOURS)
    public ResponseEntity<Void> deleteWorkingHours(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                   @PathVariable UUID hoursId) {
        service.deleteWorkingHours(tenantId(jwt), staffId(jwt), id, hoursId);
        return ResponseEntity.noContent().build();
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

    /** ACC-011: availability by session mode (slots, tokens left, expected range). */
    @GetMapping("/availability/sessions")
    @PreAuthorize("hasAuthority('queue:view')")
    public SessionAvailabilityService.SessionsResponse sessionAvailability(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                                           @RequestParam LocalDate date) {
        return sessionAvailability.sessions(id, date);
    }

    /** ACC-024: announce, then tell everyone still to be seen (the delay stays even if a message cannot be sent). */
    @com.nabd.hms.shared.Idempotent
    @PostMapping("/delay")
    @PreAuthorize("hasAuthority('queue:edit')")
    public ResponseEntity<DoctorDelayResponse> announceDelay(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                              @Valid @RequestBody DelayAnnounceRequest req) {
        DoctorDelayResponse d = service.announceDelay(tenantId(jwt), staffId(jwt), id, req);
        int sent = Boolean.FALSE.equals(req.broadcast()) ? 0 : broadcast.onDelay(tenantId(jwt), id, d.id(), d.delayMinutes(), d.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(d.withRecipients(sent));
    }

    @com.nabd.hms.shared.Idempotent
    @PatchMapping("/delay/{delayId}")
    @PreAuthorize("hasAuthority('queue:edit')")
    public DoctorDelayResponse changeDelay(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID delayId,
                                           @Valid @RequestBody DelayChangeRequest req) {
        DoctorDelayResponse d = service.changeDelay(tenantId(jwt), staffId(jwt), id, delayId, req.minutes());
        return d.withRecipients(broadcast.onDelay(tenantId(jwt), id, d.id(), d.delayMinutes(), d.expiresAt()));
    }

    @GetMapping("/delay/{delayId}/responses")
    @PreAuthorize("hasAuthority('queue:view')")
    public DelayResponsesResponse delayResponses(@PathVariable UUID id, @PathVariable UUID delayId) {
        return broadcast.responses(id, delayId);
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

package com.nabd.hms.queue;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.queue.dto.AppointmentPage;
import com.nabd.hms.queue.dto.AppointmentResponse;
import com.nabd.hms.queue.dto.AppointmentWriteRequest;
import com.nabd.hms.queue.dto.CallbackEntryResponse;
import com.nabd.hms.queue.dto.CancelRequest;
import com.nabd.hms.queue.dto.RescheduleRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
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
    public AppointmentPage list(@CurrentTenant UUID tenantId,
                                 @RequestParam(required = false) UUID doctorId,
                                 @RequestParam(required = false) UUID patientId,
                                 @RequestParam(required = false) LocalDate date,
                                 @RequestParam(required = false) Integer limit,
                                 @RequestParam(required = false) String cursor) {
        return service.list(tenantId, doctorId, patientId, date, com.nabd.hms.common.PageRequest.limit(limit), cursor);
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping
    @PreAuthorize("hasAuthority('queue:create')")
    public ResponseEntity<AppointmentResponse> book(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId,
                                                      @Valid @RequestBody AppointmentWriteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.book(tenantId, staffId, req));
    }

    @GetMapping("/callback-list")
    @PreAuthorize("hasAuthority('queue:view')")
    public List<CallbackEntryResponse> callbackList(@CurrentTenant UUID tenantId) {
        return service.callbackList(tenantId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('queue:view')")
    public ResponseEntity<AppointmentResponse> get(@CurrentTenant UUID tenantId, @PathVariable UUID id,
                                                   @org.springframework.web.bind.annotation.RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        java.util.List<Long> seq = service.changeSeq(id); // OPS-006: 304 without loading the appointment
        return com.nabd.hms.shared.ETags.respond(!seq.isEmpty(), seq.isEmpty() ? null : seq.get(0), ifNoneMatch, () -> service.get(tenantId, id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('queue:edit')")
    public AppointmentResponse cancel(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                       @Valid @RequestBody CancelRequest req) {
        return service.cancel(tenantId, staffId, id, req);
    }

    @PostMapping("/{id}/reschedule")
    @PreAuthorize("hasAuthority('queue:edit')")
    public AppointmentResponse reschedule(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                           @Valid @RequestBody RescheduleRequest req) {
        return service.reschedule(tenantId, staffId, id, req);
    }
}

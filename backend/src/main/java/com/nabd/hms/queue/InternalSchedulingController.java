package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.iam.api.InternalCaller;
import com.nabd.hms.iam.api.ServiceIdentity;
import com.nabd.hms.shared.Idempotent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** ACC-038: availability, booking and appointment actions for the Patient Access Edge (service identity, tenant in the path). */
@RestController
class InternalSchedulingController {

    @Schema(name = "InternalBookRequest")
    record BookRequest(@NotBlank String patientRef, @NotNull UUID doctorId, @NotNull Instant startTime, @jakarta.validation.constraints.Size(max = 64) String actedByRef) {
    }

    @Schema(name = "InternalAppointmentActionRequest")
    record ActionRequest(@NotBlank String patientRef, Instant newStartTime, String reasonCode, String note, @jakarta.validation.constraints.Size(max = 64) String actedByRef) {
    }

    private final InternalSchedulingService service;

    InternalSchedulingController(InternalSchedulingService service) {
        this.service = service;
    }

    @GetMapping("/internal/v1/tenants/{tid}/availability")
    InternalSchedulingService.Availability availability(@PathVariable UUID tid, @RequestParam(required = false) UUID doctorId,
            @RequestParam(required = false) String specialtyCode, // shortcut: Core has no specialty data yet, so this filter is accepted and not applied
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        caller(tid);
        return service.availability(tid, doctorId, from, to);
    }

    @Idempotent
    @PostMapping("/internal/v1/tenants/{tid}/appointments")
    ResponseEntity<Map<String, Object>> book(@PathVariable UUID tid, @Valid @RequestBody BookRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.book(tid, caller(tid), req.patientRef(), req.doctorId(), req.startTime(), req.actedByRef()));
    }

    @GetMapping("/internal/v1/tenants/{tid}/appointments/{id}")
    Map<String, Object> detail(@PathVariable UUID tid, @PathVariable UUID id, @RequestParam String patientRef) {
        caller(tid);
        return service.detail(tid, id, patientRef);
    }

    @Idempotent
    @PostMapping("/internal/v1/tenants/{tid}/appointments/{id}/{action}")
    Map<String, Object> act(@PathVariable UUID tid, @PathVariable UUID id, @PathVariable String action, @Valid @RequestBody ActionRequest req) {
        return service.act(tid, caller(tid), id, action, req.patientRef(), req.newStartTime(), req.reasonCode(), req.note(), req.actedByRef());
    }

    /** ACC-007: the same coded list the desk uses, in the patient's language, for the bot's one-tap cancel reasons. */
    @GetMapping("/internal/v1/tenants/{tid}/reason-codes")
    Map<String, Object> reasonCodes(@PathVariable UUID tid, @RequestParam String kind, @RequestParam(required = false) String locale) {
        caller(tid);
        return Map.of("items", service.reasonCodes(tid, kind, locale));
    }

    /** Second line behind InternalCallerFilter: only a service identity, and only for a clinic its token names. */
    private static ServiceIdentity caller(UUID tid) {
        ServiceIdentity edge = InternalCaller.current();
        if (!edge.mayAct(tid)) throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
        return edge;
    }
}

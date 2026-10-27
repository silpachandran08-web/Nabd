package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.iam.api.InternalCaller;
import com.nabd.hms.iam.api.ServiceIdentity;
import com.nabd.hms.shared.Idempotent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ACC-033: self check-in for the Patient Access Edge (service identity, tenant in the path, Idempotency-Key on the POST). */
@RestController
class InternalCheckInController {

    @Schema(name = "SelfCheckInLocation")
    record Location(Double lat, Double lng, Double accuracyM) {
    }

    @Schema(name = "SelfCheckInWalkIn")
    record WalkIn(UUID doctorId) {
    }

    @Schema(name = "SelfCheckInRequest")
    record Request(UUID appointmentId, @Valid WalkIn walkIn, @NotBlank String patientRef,
                   @Pattern(regexp = "qr|whatsapp|geofence") String channel, Location location) {
    }

    private final SelfCheckInService service;

    InternalCheckInController(SelfCheckInService service) {
        this.service = service;
    }

    @Idempotent
    @PostMapping("/internal/v1/tenants/{tid}/check-ins")
    ResponseEntity<SelfCheckInService.Result> checkIn(@PathVariable UUID tid, @Valid @RequestBody Request req) {
        caller(tid);
        if (req.channel() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "channel is required.");
        var l = req.location();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.checkIn(tid, InternalCaller.current(), req.appointmentId(),
                req.walkIn() == null ? null : req.walkIn().doctorId(), req.patientRef(), req.channel(),
                l == null ? null : l.lat(), l == null ? null : l.lng(), l == null ? null : l.accuracyM()));
    }

    /** The facility code, the geofence policy and the household members who have an appointment today. */
    @GetMapping("/internal/v1/tenants/{tid}/check-ins/context")
    SelfCheckInService.Context context(@PathVariable UUID tid, @RequestParam(required = false) String phoneHash) {
        caller(tid);
        return service.context(tid, phoneHash);
    }

    private static ServiceIdentity caller(UUID tid) {
        ServiceIdentity edge = InternalCaller.current();
        if (!edge.mayAct(tid)) throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
        return edge;
    }
}

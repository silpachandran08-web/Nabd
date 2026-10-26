package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.iam.api.InternalCaller;
import com.nabd.hms.iam.api.ServiceIdentity;
import com.nabd.hms.shared.Idempotent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ACC-009/010: a booking from a patient channel (confirmed or held for review) and the patient's answer to offered slots. Service identity, tenant in the path. */
@RestController
class InternalBookingRequestController {

    // shortcut: doctorId + slotStart only; specialtyCode / sessionId need ACC-011 (token sessions) and a specialty model, holdId lives in the Edge
    @Schema(name = "InternalBookingRequest")
    record RouteRequest(@NotBlank String patientRef, @NotNull UUID doctorId, @NotNull Instant slotStart, String visitType, @NotBlank String channel,
                        List<String> triageFlags, Boolean group, @Size(max = 280) String note) {
    }

    @Schema(name = "InternalBookingOfferChoice")
    record ChooseRequest(@NotBlank String patientRef, Instant slotStart) {
    }

    private final BookingRequestService service;

    InternalBookingRequestController(BookingRequestService service) {
        this.service = service;
    }

    @Idempotent
    @PostMapping("/internal/v1/tenants/{tid}/booking-requests")
    ResponseEntity<Map<String, Object>> route(@PathVariable UUID tid, @Valid @RequestBody RouteRequest req) {
        ServiceIdentity edge = caller(tid);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.route(tid, edge, req.patientRef(), req.doctorId(), req.slotStart(), req.visitType(),
                req.channel(), req.triageFlags(), Boolean.TRUE.equals(req.group()), req.note()));
    }

    @Idempotent
    @PostMapping("/internal/v1/tenants/{tid}/booking-offers/{offerId}/choose")
    Map<String, Object> choose(@PathVariable UUID tid, @PathVariable UUID offerId, @Valid @RequestBody ChooseRequest req) {
        return service.choose(tid, caller(tid), offerId, req.patientRef(), req.slotStart());
    }

    private static ServiceIdentity caller(UUID tid) {
        ServiceIdentity edge = InternalCaller.current();
        if (!edge.mayAct(tid)) throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
        return edge;
    }
}

package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.iam.api.InternalCaller;
import com.nabd.hms.iam.api.ServiceIdentity;
import com.nabd.hms.shared.Idempotent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ACC-006: the desk's affected-bookings list and offer, and the Edge's accept. */
@RestController
class ChangeOfferController {

    @Schema(name = "ChangeOfferRequest")
    record OfferRequest(List<Instant> slots) {
    }

    @Schema(name = "ChangeOfferAccept")
    record AcceptRequest(@NotNull Instant slotStart) {
    }

    private final ChangeOfferService service;

    ChangeOfferController(ChangeOfferService service) {
        this.service = service;
    }

    @GetMapping("/v1/doctors/{id}/leave/{leaveId}/affected-appointments")
    @PreAuthorize("hasAuthority('queue:view')")
    ChangeOfferService.AffectedList affected(@PathVariable UUID id, @PathVariable UUID leaveId) {
        return service.affected(id, leaveId);
    }

    @Idempotent
    @PostMapping("/v1/appointments/{id}/offer-alternatives")
    @PreAuthorize("hasAuthority('queue:edit')")
    ResponseEntity<Map<String, Object>> offer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody(required = false) OfferRequest body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.offer(UUID.fromString(jwt.getSubject()), id, body == null ? null : body.slots()));
    }

    @Idempotent
    @PostMapping("/internal/v1/tenants/{tid}/change-offers/{offerId}/accept")
    Map<String, Object> accept(@PathVariable UUID tid, @PathVariable UUID offerId, @Valid @RequestBody AcceptRequest req) {
        ServiceIdentity edge = InternalCaller.current();
        if (!edge.mayAct(tid)) throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", "This service identity may not act for that clinic.");
        return service.accept(tid, edge, offerId, req.slotStart());
    }
}

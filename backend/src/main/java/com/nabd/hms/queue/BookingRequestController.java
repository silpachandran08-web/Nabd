package com.nabd.hms.queue;

import jakarta.validation.Valid;
import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.PageRequest;
import com.nabd.hms.shared.Idempotent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ACC-010: the reviewer's inbox of held booking requests. Needs the booking permission (queue:create); every decision is a compare-and-set. */
@RestController
@RequestMapping("/v1/booking-requests")
class BookingRequestController {

    @Schema(name = "BookingRequestAcceptBody")
    record AcceptBody(Instant slotStart, @Size(max = 280) String note) {
    }

    @Schema(name = "BookingRequestSuggestBody")
    record SuggestBody(List<Instant> slots) {
    }

    @Schema(name = "BookingRequestDeclineBody")
    record DeclineBody(String reasonCode, @Size(max = 280) String note) {
    }

    private final BookingRequestService service;

    BookingRequestController(BookingRequestService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('queue:create')")
    BookingRequestService.Page list(@RequestParam(defaultValue = "requested") String status, @RequestParam(required = false) UUID doctorId,
                                    @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        if (!status.equals("requested") && !status.equals("decided")) {
            throw new com.nabd.hms.common.ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "status must be requested or decided.");
        }
        return service.list(status.equals("requested"), doctorId, PageRequest.limit(limit), cursor);
    }

    @Idempotent
    @PostMapping("/{id}/accept")
    @PreAuthorize("hasAuthority('queue:create')")
    Map<String, Object> accept(@CurrentStaff UUID staffId, @PathVariable UUID id, @Valid @RequestBody(required = false) AcceptBody body) {
        return service.accept(staffId, id, body == null ? null : body.slotStart());
    }

    @Idempotent
    @PostMapping("/{id}/suggest")
    @PreAuthorize("hasAuthority('queue:create')")
    ResponseEntity<Map<String, Object>> suggest(@CurrentStaff UUID staffId, @PathVariable UUID id, @Valid @RequestBody SuggestBody body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.suggest(staffId, id, body.slots()));
    }

    @Idempotent
    @PostMapping("/{id}/decline")
    @PreAuthorize("hasAuthority('queue:create')")
    Map<String, Object> decline(@CurrentStaff UUID staffId, @PathVariable UUID id, @Valid @RequestBody(required = false) DeclineBody body) {
        return service.decline(staffId, id, body == null ? null : body.reasonCode());
    }
}

package com.nabd.hms.queue;

import com.nabd.hms.queue.dto.CheckInRequest;
import com.nabd.hms.queue.dto.QueueEntryResponse;
import com.nabd.hms.queue.dto.QueueReorderRequest;
import com.nabd.hms.queue.dto.QueueStatusUpdateRequest;
import com.nabd.hms.queue.dto.TransferRequest;
import com.nabd.hms.queue.dto.TransferResponse;
import com.nabd.hms.queue.dto.WaitEstimateResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
@RequestMapping("/v1/queue")
public class QueueController {

    private final QueueService service;

    private final CallOrderService callOrder;

    QueueController(QueueService service, CallOrderService callOrder) {
        this.service = service;
        this.callOrder = callOrder;
    }

    /** ACC-012: the next patient by the session's walk-in rule; compare-and-set on the status, 409 when the head changed. */
    @com.nabd.hms.shared.Idempotent
    @PostMapping("/doctors/{doctorId}/call-next")
    @PreAuthorize("hasAuthority('queue:edit')")
    public com.nabd.hms.queue.dto.CallNextResponse callNext(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID doctorId,
                                                            @RequestBody(required = false) com.nabd.hms.queue.dto.CallNextRequest req) {
        return callOrder.callNext(staffId(jwt), doctorId, req == null ? null : req.expectedHeadId());
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/check-in")
    @PreAuthorize("hasAuthority('queue:create')")
    public ResponseEntity<QueueEntryResponse> checkIn(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CheckInRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(callOrder.annotateOne(service.checkIn(tenantId(jwt), staffId(jwt), req)));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('queue:view')")
    public List<QueueEntryResponse> list(@AuthenticationPrincipal Jwt jwt,
                                          @RequestParam(required = false) UUID doctorId,
                                          @RequestParam(required = false) UUID departmentId,
                                          @RequestParam(required = false) LocalDate date,
                                          @RequestParam(defaultValue = "false") boolean priority) {
        var rows = service.list(tenantId(jwt), doctorId, departmentId, date, priority);
        return priority ? rows : callOrder.annotate(rows); // ACC-012: position and why-here per doctor and day
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('queue:edit')")
    public QueueEntryResponse updateStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                            @Valid @RequestBody QueueStatusUpdateRequest req) {
        return service.updateStatus(tenantId(jwt), staffId(jwt), id, req);
    }

    @PostMapping("/{id}/transfer")
    @PreAuthorize("hasAuthority('queue:edit')")
    public TransferResponse transfer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                      @Valid @RequestBody TransferRequest req) {
        return service.transfer(tenantId(jwt), staffId(jwt), id, req);
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/{id}/extend")
    @PreAuthorize("hasAuthority('queue:edit')")
    public com.nabd.hms.queue.dto.ConsultExtendResponse extend(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                         @Valid @RequestBody com.nabd.hms.queue.dto.ConsultExtendRequest req) {
        if (!java.util.Set.of(5, 10, 15).contains(req.minutes())) {
            throw new com.nabd.hms.common.ApiException(HttpStatus.BAD_REQUEST, "invalid-extension", "Invalid extension", "Minutes must be 5, 10 or 15.");
        }
        return service.extend(tenantId(jwt), staffId(jwt), id, req.minutes());
    }

    @PostMapping("/{id}/reorder")
    @PreAuthorize("hasAuthority('queue:edit')")
    public QueueEntryResponse reorder(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                       @Valid @RequestBody QueueReorderRequest req) {
        return service.reorder(tenantId(jwt), staffId(jwt), id, req);
    }

    @PostMapping("/{id}/priority/acknowledge")
    @PreAuthorize("hasAuthority('queue:edit')")
    public QueueEntryResponse acknowledgePriority(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.acknowledgePriority(tenantId(jwt), staffId(jwt), id);
    }

    @GetMapping("/wait-estimate")
    @PreAuthorize("hasAuthority('queue:view')")
    public WaitEstimateResponse waitEstimate(@AuthenticationPrincipal Jwt jwt, @RequestParam UUID doctorId) {
        return service.waitEstimate(tenantId(jwt), doctorId);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}

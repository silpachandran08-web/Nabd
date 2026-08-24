package com.nabd.hms.queue;

import com.nabd.hms.queue.dto.CheckInRequest;
import com.nabd.hms.queue.dto.QueueEntryResponse;
import com.nabd.hms.queue.dto.QueueReorderRequest;
import com.nabd.hms.queue.dto.QueueStatusUpdateRequest;
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

    QueueController(QueueService service) {
        this.service = service;
    }

    @PostMapping("/check-in")
    @PreAuthorize("hasAuthority('queue:create')")
    public ResponseEntity<QueueEntryResponse> checkIn(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CheckInRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.checkIn(tenantId(jwt), staffId(jwt), req));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('queue:view')")
    public List<QueueEntryResponse> list(@AuthenticationPrincipal Jwt jwt,
                                          @RequestParam(required = false) UUID doctorId,
                                          @RequestParam(required = false) LocalDate date,
                                          @RequestParam(defaultValue = "false") boolean priority) {
        return service.list(tenantId(jwt), doctorId, date, priority);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('queue:edit')")
    public QueueEntryResponse updateStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                            @Valid @RequestBody QueueStatusUpdateRequest req) {
        return service.updateStatus(tenantId(jwt), staffId(jwt), id, req);
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

package com.nabd.hms.queue;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import io.swagger.v3.oas.annotations.media.Schema;
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

import java.time.LocalDate;
import java.util.UUID;

/** ACC-022: GET /v1/queue/board (every doctor's queue in one call) and POST /v1/queue/{id}/reassign. */
@RestController
@RequestMapping("/v1/queue")
class QueueBoardController {

    @Schema(name = "QueueReassignRequest")
    record ReassignRequest(@NotNull UUID toDoctorId, @NotBlank String expectedStatus,
                           @NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{1,39}$") String reasonCode) {
    }

    private final QueueBoardService service;

    QueueBoardController(QueueBoardService service) {
        this.service = service;
    }

    @GetMapping("/board")
    @PreAuthorize("hasAuthority('queue:view')")
    QueueBoardService.Board board(@RequestParam(required = false) LocalDate date, @RequestParam(required = false) UUID departmentId) {
        return service.board(date, departmentId);
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/{id}/reassign")
    @PreAuthorize("hasAuthority('queue:edit')")
    QueueBoardService.Reassigned reassign(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ReassignRequest req) {
        return service.reassign(UUID.fromString(jwt.getSubject()), id, req.toDoctorId(), req.expectedStatus(), req.reasonCode());
    }
}

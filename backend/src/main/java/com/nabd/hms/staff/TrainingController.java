package com.nabd.hms.staff;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * PLT-054: /v1/training/completions. POST and /me are for any signed-in staff member and only ever touch their own rows
 * (the staff id is the token subject); the team list needs staff:view, like the staff directory itself.
 */
@RestController
@RequestMapping("/v1/training/completions")
class TrainingController {

    @Schema(name = "TrainingCompletionRequest")
    record CompletionRequest(@NotBlank String moduleId, @NotNull Integer score) {
    }

    private final TrainingService service;

    TrainingController(TrainingService service) {
        this.service = service;
    }

    /** 201 for a new pass, 200 when today's pass was already recorded (idempotent). 422 below the pass mark or for an unknown module. */
    @PostMapping
    ResponseEntity<TrainingService.Completion> record(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CompletionRequest req) {
        TrainingService.Completion c = service.record(UUID.fromString(jwt.getSubject()), req.moduleId(), req.score());
        return ResponseEntity.status(c.created() ? HttpStatus.CREATED : HttpStatus.OK).body(c);
    }

    @GetMapping("/me")
    TrainingService.Status mine(@AuthenticationPrincipal Jwt jwt) {
        return service.mine(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('staff:view')")
    TrainingService.Roster roster() {
        return service.roster();
    }
}

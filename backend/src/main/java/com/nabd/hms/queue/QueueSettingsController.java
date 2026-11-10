package com.nabd.hms.queue;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/** ACC-018: the doctor's ETA display mode (and, with workingHoursId, one session's). A doctor sets their own; anyone else needs setup:edit. */
@RestController
class QueueSettingsController {

    private static final String CAN_WRITE = "hasAuthority('setup:edit') or authentication.name == #id.toString()";

    @Schema(name = "QueueSettingsRequest")
    record Request(@Schema(description = "exact | range | position | delay_only | hidden | null (inherit)", nullable = true) String etaDisplayMode,
                   @Schema(description = "Optional: set this working-hours block's override instead of the doctor's", nullable = true) UUID workingHoursId) {
    }

    @Schema(name = "QueueSettingsResponse")
    record Response(String etaDisplayMode, String effectiveSource) {
    }

    private final EtaDisplayModes modes;

    QueueSettingsController(EtaDisplayModes modes) {
        this.modes = modes;
    }

    @GetMapping("/v1/doctors/{id}/queue-settings")
    @PreAuthorize("hasAuthority('queue:view')")
    EtaDisplayModes.Settings get(@PathVariable UUID id) {
        return modes.settings(id);
    }

    @PutMapping("/v1/doctors/{id}/queue-settings")
    @PreAuthorize(CAN_WRITE)
    Response put(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Request req) {
        var r = modes.put(UUID.fromString(jwt.getSubject()), id, req.workingHoursId(), req.etaDisplayMode());
        return new Response(r.mode(), r.source());
    }

    @Schema(name = "QueueFacilitySettingsRequest")
    record FacilityRequest(@jakarta.validation.constraints.NotBlank String etaDisplayMode) {
    }

    /** The clinic-wide default that doctors and sessions inherit. */
    @PutMapping("/v1/queue-settings/facility")
    @PreAuthorize("hasAuthority('setup:edit')")
    Response putFacility(@AuthenticationPrincipal Jwt jwt, @RequestBody @jakarta.validation.Valid FacilityRequest req) {
        var r = modes.putFacility(UUID.fromString(jwt.getSubject()), req.etaDisplayMode());
        return new Response(r.mode(), r.source());
    }
}

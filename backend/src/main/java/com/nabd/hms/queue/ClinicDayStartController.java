package com.nabd.hms.queue;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ACC-005: where the clinic day starts. Permission setup:edit (the story's scheduling:configure does not exist yet) plus step-up. */
@RestController
class ClinicDayStartController {

    @Schema(name = "ClinicDayStartRequest")
    record Request(@Schema(description = "HH:mm between 00:00 and 05:59") String dayStart) {
    }

    private final ClinicDayStartService service;

    ClinicDayStartController(ClinicDayStartService service) {
        this.service = service;
    }

    @PutMapping("/v1/clinic/settings/day-start")
    @PreAuthorize("hasAuthority('setup:edit')")
    ClinicDayStartService.Result put(@AuthenticationPrincipal Jwt jwt, @RequestBody Request req,
                                     @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        return service.set(UUID.fromString(jwt.getSubject()), req.dayStart(), stepUpToken);
    }
}

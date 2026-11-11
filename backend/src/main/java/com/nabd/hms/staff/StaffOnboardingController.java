package com.nabd.hms.staff;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ACC-035: GET /v1/me/onboarding and PUT /v1/me/onboarding/{key}. Any signed-in staff member, own progress only (token subject). */
@RestController
@RequestMapping("/v1/me/onboarding")
class StaffOnboardingController {

    private final StaffOnboardingService service;

    StaffOnboardingController(StaffOnboardingService service) {
        this.service = service;
    }

    @GetMapping
    StaffOnboardingService.Progress get(@AuthenticationPrincipal Jwt jwt) {
        return service.get(UUID.fromString(jwt.getSubject()));
    }

    /** Marks a tour finished or a competency task done; repeating it is harmless (the first time is kept). */
    @PutMapping("/{key:.+}")
    StaffOnboardingService.Progress complete(@AuthenticationPrincipal Jwt jwt, @PathVariable String key) {
        return service.complete(UUID.fromString(jwt.getSubject()), key);
    }
}

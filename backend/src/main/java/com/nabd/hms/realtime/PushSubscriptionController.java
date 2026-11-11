package com.nabd.hms.realtime;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** FEF-048: this browser's Web Push subscription for "patient ready". Any signed-in staff member, own subscriptions only. */
@RestController
@RequestMapping("/v1/me/push-subscriptions")
class PushSubscriptionController {

    @Schema(name = "PushSubscriptionCreated")
    record Created(UUID id) {
    }

    private final PushSubscriptionService service;

    PushSubscriptionController(PushSubscriptionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Created register(@AuthenticationPrincipal Jwt jwt, @RequestBody PushSubscriptionService.Register req) {
        return new Created(service.register(UUID.fromString(jwt.getSubject()), req));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.delete(UUID.fromString(jwt.getSubject()), id);
    }
}

package com.nabd.hms.queue;

import com.nabd.hms.common.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ACC-023: waiting-room display. Staff manage codes and devices (setup:edit to change, setup:view to list); the TV pairs with
 * POST /v1/display/pair and streams with "Authorization: Display <deviceToken>" (never in a URL). The display is read-only.
 */
@RestController
class DisplayController {

    @Schema(name = "DisplayScope")
    record ScopeBody(List<UUID> doctorIds, UUID departmentId) {
    }

    @Schema(name = "DisplayPairingCodeRequest")
    record CodeRequest(ScopeBody scope, @NotBlank @Size(max = 60) String label) {
    }

    @Schema(name = "DisplayPairingCode")
    record CodeResponse(String code, Instant expiresAt, @Schema(description = "The clinic name to give the TV (the display URL carries it)") String clinic) {
    }

    @Schema(name = "DisplayPairRequest")
    record PairRequest(@NotBlank @Size(max = 80) String clinic, @NotBlank @Pattern(regexp = "^\\d{6}$") String code) {
    }

    @Schema(name = "DisplayPairResponse")
    record PairResponse(String deviceToken, ScopeBody scope) {
    }

    @Schema(name = "DisplayDevice")
    record DeviceItem(UUID id, String label, ScopeBody scope, Instant pairedAt, Instant lastSeenAt) {
    }

    private final DisplayService service;
    private final DisplayStreamHub hub;

    DisplayController(DisplayService service, DisplayStreamHub hub) {
        this.service = service;
        this.hub = hub;
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/v1/display-devices/pairing-codes")
    @PreAuthorize("hasAuthority('setup:edit')")
    ResponseEntity<CodeResponse> createCode(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CodeRequest req) {
        var scope = req.scope() == null ? new DisplayService.Scope(List.of(), null)
                : new DisplayService.Scope(req.scope().doctorIds() == null ? List.of() : req.scope().doctorIds(), req.scope().departmentId());
        var c = service.createCode(UUID.fromString(jwt.getSubject()), scope, req.label().trim());
        return ResponseEntity.status(HttpStatus.CREATED).body(new CodeResponse(c.code(), c.expiresAt(), c.clinic()));
    }

    @GetMapping("/v1/display-devices")
    @PreAuthorize("hasAuthority('setup:view')")
    List<DeviceItem> devices() {
        return service.devices().stream().map(d -> {
            var s = service.scopeOf(d);
            return new DeviceItem(d.id(), d.label(), new ScopeBody(s.doctorIds(), s.departmentId()), d.pairedAt(), d.lastSeenAt());
        }).toList();
    }

    @DeleteMapping("/v1/display-devices/{id}")
    @PreAuthorize("hasAuthority('setup:edit')")
    ResponseEntity<Void> unpair(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.unpair(UUID.fromString(jwt.getSubject()), id);
        return ResponseEntity.noContent().build();
    }

    /** Public (public chain, per-IP rate limit): the clinic slug is in the display URL; wrong codes are counted per clinic. */
    @PostMapping("/v1/display/pair")
    ResponseEntity<PairResponse> pair(@Valid @RequestBody PairRequest req) {
        return switch (service.pair(req.clinic(), req.code())) {
            case DisplayService.PairResult.Paired p -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new PairResponse(p.deviceToken(), new ScopeBody(p.scope().doctorIds(), p.scope().departmentId())));
            case DisplayService.PairResult.Locked l -> throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "locked", "Pairing locked",
                    "Too many wrong codes. Try again later.", Map.of("code", "LOCKED", "retryAfterSeconds", l.retryAfterSeconds()));
            case DisplayService.PairResult.Invalid i -> throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-code", "Invalid code",
                    "That code is wrong or has expired.", Map.of("code", "INVALID_CODE"));
        };
    }

    @GetMapping(value = "/v1/display/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = authorization != null && authorization.startsWith("Display ") ? authorization.substring(8).trim() : null;
        return hub.open(service.authenticate(token));
    }
}

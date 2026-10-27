package com.nabd.hms.queue;

import com.nabd.hms.queue.api.ReasonCodes;
import com.nabd.hms.shared.Idempotent;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** ACC-007: the reason list. Permissions: queue:view to read, setup:edit to add (scheduling:configure does not exist, see the ACC-008 report). */
@RestController
class ReasonCodeController {

    @Schema(name = "ReasonCodeList")
    record ListResponse(List<ReasonCodes.Item> items) {
    }

    @Schema(name = "ReasonCodeAddRequest")
    record AddRequest(@NotBlank String kind, @NotBlank String code, @NotNull Map<String, String> labels) {
    }

    private final ReasonCodeService service;

    ReasonCodeController(ReasonCodeService service) {
        this.service = service;
    }

    @GetMapping("/v1/reason-codes")
    @PreAuthorize("hasAuthority('queue:view')")
    ListResponse list(@RequestParam String kind, @RequestParam(required = false) String locale) {
        return new ListResponse(service.list(kind, locale));
    }

    @Idempotent
    @PostMapping("/v1/reason-codes")
    @PreAuthorize("hasAuthority('setup:edit')")
    ResponseEntity<Map<String, Object>> add(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.add(UUID.fromString(jwt.getSubject()), req.kind(), req.code(), req.labels()));
    }
}

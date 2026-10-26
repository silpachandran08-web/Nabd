package com.nabd.hms.clinical;

import com.nabd.hms.common.RequestMeta;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** CLN-019: operator side of the drug master import (platform token, kb_import:edit). The service-identity caller of the
 * spec waits on PLT-009 (aud=internal tokens); the operator permission is the gate until then. */
@RestController
class DrugMasterImportController {

    @io.swagger.v3.oas.annotations.media.Schema(name = "DrugMasterImportRequest")
    public record ImportRequest(@NotBlank @Size(max = 300) String feedUri, @NotBlank @Pattern(regexp = "full|delta") String kind, Boolean dryRun) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "DrugMasterActivateRequest")
    public record ActivateRequest(@Size(max = 200) String csoAckRef) {
    }

    private final DrugMasterImportService service;

    DrugMasterImportController(DrugMasterImportService service) {
        this.service = service;
    }

    @PostMapping("/internal/v1/drug-master/imports")
    @PreAuthorize("hasAuthority('kb_import:edit')")
    public ResponseEntity<Map<String, Object>> start(@Valid @RequestBody ImportRequest req, @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.start(req.feedUri(), req.kind(),
                req.dryRun() == null || req.dryRun(), UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http)));
    }

    @GetMapping("/internal/v1/drug-master/imports/{importId}")
    @ClinicalReadScope.NoPatientData
    @PreAuthorize("hasAuthority('kb_import:edit')")
    public Map<String, Object> status(@PathVariable UUID importId) {
        return service.status(importId);
    }

    @PostMapping("/internal/v1/drug-master/imports/{importId}/activate")
    @PreAuthorize("hasAuthority('kb_import:edit')")
    public Map<String, Object> activate(@PathVariable UUID importId, @Valid @RequestBody(required = false) ActivateRequest req,
                                        @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return service.activate(importId, req == null ? null : req.csoAckRef(), UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http));
    }
}

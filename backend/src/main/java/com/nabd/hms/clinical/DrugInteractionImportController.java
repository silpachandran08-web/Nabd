package com.nabd.hms.clinical;

import com.nabd.hms.common.RequestMeta;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** CLN-032: operator side of the interaction knowledge-base loader (platform token, kb_import:edit, as the CLN-019
 * drug master import next to it). */
@RestController
class DrugInteractionImportController {

    @io.swagger.v3.oas.annotations.media.Schema(name = "DrugInteractionImportRequest")
    public record LoadRequest(@NotBlank @Size(max = 300) String feedUri) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "DrugInteractionActivateRequest")
    public record ActivateRequest(@NotBlank @Size(max = 200) String csoAckRef) {
    }

    private final DrugInteractionImportService service;

    DrugInteractionImportController(DrugInteractionImportService service) {
        this.service = service;
    }

    @PostMapping("/internal/v1/drug-master/interaction-imports")
    @PreAuthorize("hasAuthority('kb_import:edit')")
    public Map<String, Object> load(@Valid @RequestBody LoadRequest req, @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return service.load(req.feedUri(), UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http));
    }

    @PostMapping("/internal/v1/drug-master/interaction-imports/{importId}/activate")
    @PreAuthorize("hasAuthority('kb_import:edit')")
    public Map<String, Object> activate(@PathVariable UUID importId, @Valid @RequestBody ActivateRequest req,
                                        @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return service.activate(importId, req.csoAckRef(), UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http));
    }
}

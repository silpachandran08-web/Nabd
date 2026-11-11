package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.SafetyDictionaryBundle;
import com.nabd.hms.common.RequestMeta;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** CLN-004: the clinic-side read (staff token, clinical:view) and the operator-side content flow (platform token,
 * safety_content:edit) for the versioned safety dictionary. */
@RestController
public class SafetyDictionaryController {

    public record ApproveRequest(@NotBlank @Size(max = 200) String csoApprovalRef, @NotBlank @Size(max = 200) String approvedBy) {
    }

    public record ActivateRequest(@NotBlank @Size(max = 200) String csoApprovalRef, @NotBlank @Size(max = 500) String reason) {
    }

    private final SafetyDictionaryService service;

    SafetyDictionaryController(SafetyDictionaryService service) {
        this.service = service;
    }

    @GetMapping("/v1/clinical/safety-dictionary")
    @ClinicalReadScope.NoPatientData
    @PreAuthorize("hasAuthority('clinical:view')")
    public Map<String, Object> getActiveSafetyDictionary(@AuthenticationPrincipal Jwt jwt) {
        return service.activeForTenant(UUID.fromString(jwt.getClaimAsString("tenantId")));
    }

    @GetMapping("/internal/v1/safety-dictionaries/{region}/versions")
    @ClinicalReadScope.NoPatientData
    @PreAuthorize("hasAuthority('safety_content:edit')")
    public List<Map<String, Object>> listSafetyDictionaryVersions(@PathVariable String region) {
        return service.versions(region);
    }

    @PostMapping("/internal/v1/safety-dictionaries/{region}/versions")
    @PreAuthorize("hasAuthority('safety_content:edit')")
    public ResponseEntity<Map<String, Object>> uploadSafetyDictionaryVersion(@PathVariable String region, @Valid @RequestBody SafetyDictionaryBundle bundle,
                                                      @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.upload(region, bundle, UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http)));
    }

    @PostMapping("/internal/v1/safety-dictionaries/{region}/versions/{versionId}/approve")
    @PreAuthorize("hasAuthority('safety_content:edit')")
    public Map<String, Object> approveSafetyDictionaryVersion(@PathVariable String region, @PathVariable UUID versionId, @Valid @RequestBody ApproveRequest req,
                                       @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return service.approve(region, versionId, req.csoApprovalRef(), req.approvedBy(), UUID.fromString(jwt.getSubject()),
                RequestMeta.clientIp(http));
    }

    @PostMapping("/internal/v1/safety-dictionaries/{region}/versions/{versionId}/activate")
    @PreAuthorize("hasAuthority('safety_content:edit')")
    public Map<String, Object> activateSafetyDictionaryVersion(@PathVariable String region, @PathVariable UUID versionId, @Valid @RequestBody ActivateRequest req,
                                        @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
        return service.activate(region, versionId, req.csoApprovalRef(), req.reason(), UUID.fromString(jwt.getSubject()),
                RequestMeta.clientIp(http));
    }
}

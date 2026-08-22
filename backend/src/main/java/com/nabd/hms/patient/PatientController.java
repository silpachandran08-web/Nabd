package com.nabd.hms.patient;

import com.nabd.hms.patient.dto.DuplicateCandidatesResponse;
import com.nabd.hms.patient.dto.GuardianReviewResponse;
import com.nabd.hms.patient.dto.MergeRequest;
import com.nabd.hms.patient.dto.PatientDetailResponse;
import com.nabd.hms.patient.dto.PatientPage;
import com.nabd.hms.patient.dto.PatientResponse;
import com.nabd.hms.patient.dto.PatientWriteRequest;
import com.nabd.hms.patient.dto.WithdrawConsentRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/patients")
public class PatientController {

    private final PatientService service;

    PatientController(PatientService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('patients:view')")
    public PatientPage list(@AuthenticationPrincipal Jwt jwt,
                             @RequestParam(required = false) String q,
                             @RequestParam(defaultValue = "20") int limit,
                             @RequestParam(required = false) String cursor) {
        return service.list(tenantId(jwt), staffId(jwt), q, limit, cursor);
    }

    @GetMapping("/guardian-reviews-due")
    @PreAuthorize("hasAuthority('patients:view')")
    public List<GuardianReviewResponse> guardianReviewsDue(@AuthenticationPrincipal Jwt jwt) {
        return service.guardianReviewsDue(tenantId(jwt), staffId(jwt));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('patients:create')")
    public ResponseEntity<Object> register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PatientWriteRequest req) {
        Object result = service.register(tenantId(jwt), staffId(jwt), req);
        HttpStatus status = result instanceof DuplicateCandidatesResponse ? HttpStatus.CONFLICT : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('patients:view')")
    public PatientDetailResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(tenantId(jwt), staffId(jwt), id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('patients:edit')")
    public PatientResponse patch(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                  @Valid @RequestBody PatientWriteRequest req) {
        return service.patch(tenantId(jwt), staffId(jwt), id, req);
    }

    @PostMapping("/{id}/merge")
    @PreAuthorize("hasAuthority('patients:delete')")
    public PatientDetailResponse merge(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                        @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken,
                                        @Valid @RequestBody MergeRequest req) {
        return service.merge(tenantId(jwt), id, staffId(jwt), stepUpToken, req);
    }

    @PostMapping("/merges/{mergeId}/undo")
    @PreAuthorize("hasAuthority('patients:delete')")
    public ResponseEntity<Void> undoMerge(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID mergeId,
                                           @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        service.unmerge(tenantId(jwt), mergeId, staffId(jwt), stepUpToken);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/consent/withdraw")
    @PreAuthorize("hasAuthority('patients:edit')")
    public ResponseEntity<Void> withdrawConsent(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                 @Valid @RequestBody WithdrawConsentRequest req) {
        service.withdrawConsent(tenantId(jwt), staffId(jwt), id, req.consentTypeOrDefault());
        return ResponseEntity.noContent().build();
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}

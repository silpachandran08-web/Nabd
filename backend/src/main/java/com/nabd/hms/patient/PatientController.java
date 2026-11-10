package com.nabd.hms.patient;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.patient.dto.PatientRegistryResponse;
import com.nabd.hms.patient.dto.DuplicateCandidatesResponse;
import com.nabd.hms.patient.dto.GuardianReviewDecisionRequest;
import com.nabd.hms.patient.dto.GuardianReviewResponse;
import com.nabd.hms.patient.dto.MergeRequest;
import com.nabd.hms.patient.dto.PatientDetailResponse;
import com.nabd.hms.patient.dto.PatientPage;
import com.nabd.hms.patient.dto.PatientPatchRequest;
import com.nabd.hms.patient.dto.PatientResponse;
import com.nabd.hms.patient.dto.PatientWriteRequest;
import com.nabd.hms.patient.dto.WithdrawConsentRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final HouseholdService households;
    private final DuplicateSuggestionService suggestions;

    PatientController(PatientService service, HouseholdService households, DuplicateSuggestionService suggestions) {
        this.service = service;
        this.households = households;
        this.suggestions = suggestions;
    }

    /** ACC-030: likely duplicate pairs with the fields that matched. Merging needs the same authority as POST /{id}/merge (patients:delete). */
    @com.nabd.hms.shared.phiread.PhiRead(value = "patient_duplicate_list", key = "a.patientId,b.patientId")
    @GetMapping("/duplicate-suggestions")
    @PreAuthorize("hasAuthority('patients:delete')")
    public com.nabd.hms.patient.dto.DuplicateSuggestionPage duplicateSuggestions(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId,
                                                                                   @RequestParam(required = false) String cursor) {
        return suggestions.list(tenantId, staffId, cursor);
    }

    /** ACC-030: merges still inside the 30-day undo window. */
    @com.nabd.hms.shared.phiread.PhiRead(value = "patient_merge_list", key = "duplicate.patientId,survivor.patientId")
    @GetMapping("/merges")
    @PreAuthorize("hasAuthority('patients:delete')")
    public com.nabd.hms.patient.dto.RecentMerges recentMerges(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId) {
        return suggestions.recentMerges(tenantId, staffId);
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/duplicate-suggestions/{id}/dismiss")
    @PreAuthorize("hasAuthority('patients:delete')")
    public ResponseEntity<Void> dismissSuggestion(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable String id) {
        suggestions.dismiss(tenantId, staffId, id);
        return ResponseEntity.noContent().build();
    }

    /** ACC-029: everyone sharing the phone, or whose guardian has it, with relation, age and last visit. */
    @com.nabd.hms.shared.phiread.PhiRead(value = "patient_household_list")
    @GetMapping("/by-phone")
    @PreAuthorize("hasAuthority('patients:view')")
    public com.nabd.hms.patient.dto.HouseholdResponse byPhone(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @RequestParam String phone) {
        return households.byPhone(tenantId, staffId, phone);
    }

    /**
     * Patient Registry — filter: all (default), recent, followup, package, balance, duplicate, archived.
     * REC-040/OPS-008: optional limit (1-200, default 200) and opaque keyset cursor page the matching rows.
     */
    @com.nabd.hms.shared.phiread.PhiRead(value = "patient_registry_list", key = "id")
    @GetMapping("/registry")
    @PreAuthorize("hasAuthority('patients:view')")
    public PatientRegistryResponse registry(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(required = false) String filter,
                                            @RequestParam(required = false) Integer limit,
                                            @RequestParam(required = false) String cursor) {
        return service.registry(tenantId, staffId, q, filter, limit == null ? null : com.nabd.hms.common.PageRequest.limit(limit, 200), cursor);
    }

    @com.nabd.hms.shared.phiread.PhiRead(value = "patient_list", key = "id")
    @GetMapping
    @PreAuthorize("hasAuthority('patients:view')")
    public PatientPage list(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId,
                             @RequestParam(required = false) String q,
                             @RequestParam(required = false) Integer limit,
                             @RequestParam(required = false) String cursor) {
        return service.list(tenantId, staffId, q, com.nabd.hms.common.PageRequest.limit(limit), cursor);
    }

    /** ACC-028: records needing correction (issue=phone: stored numbers that failed validation). */
    @com.nabd.hms.shared.phiread.PhiRead(value = "patient_data_quality_list", purpose = "directory")
    @GetMapping("/data-quality")
    @PreAuthorize("hasAuthority('patients:edit')")
    public com.nabd.hms.patient.dto.DataQualityPage dataQuality(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId,
                                                                 @RequestParam String issue,
                                                                 @RequestParam(required = false) Integer limit,
                                                                 @RequestParam(required = false) String cursor) {
        return service.dataQuality(tenantId, staffId, issue, com.nabd.hms.common.PageRequest.limit(limit), cursor);
    }

    @com.nabd.hms.shared.phiread.PhiRead("guardian_reviews_list")
    @GetMapping("/guardian-reviews-due")
    @PreAuthorize("hasAuthority('patients:view')")
    public List<GuardianReviewResponse> guardianReviewsDue(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId) {
        return service.guardianReviewsDue(tenantId, staffId);
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping
    @PreAuthorize("hasAuthority('patients:create')")
    public ResponseEntity<Object> register(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @Valid @RequestBody PatientWriteRequest req) {
        Object result = service.register(tenantId, staffId, req);
        HttpStatus status = result instanceof DuplicateCandidatesResponse ? HttpStatus.CONFLICT : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result);
    }

    @com.nabd.hms.shared.phiread.PhiRead(value = "patient", key = "id")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('patients:view')")
    public PatientDetailResponse get(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id) {
        return service.get(tenantId, staffId, id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('patients:edit')")
    public PatientResponse patch(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                  @Valid @RequestBody PatientPatchRequest req) {
        return service.patch(tenantId, staffId, id, req);
    }

    @DeleteMapping("/{id}/guardian")
    @PreAuthorize("hasAuthority('patients:edit')")
    public ResponseEntity<Void> revokeGuardian(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id) {
        service.revokeGuardian(tenantId, staffId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/guardian/review")
    @PreAuthorize("hasAuthority('patients:edit')")
    public PatientDetailResponse keepGuardian(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                              @Valid @RequestBody GuardianReviewDecisionRequest req) {
        return service.keepGuardian(tenantId, staffId, id);
    }

    @PostMapping("/{id}/merge")
    @PreAuthorize("hasAuthority('patients:delete')")
    public PatientDetailResponse merge(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                        @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken,
                                        @Valid @RequestBody MergeRequest req) {
        return service.merge(tenantId, id, staffId, stepUpToken, req);
    }

    @PostMapping("/merges/{mergeId}/undo")
    @PreAuthorize("hasAuthority('patients:delete')")
    public ResponseEntity<Void> undoMerge(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID mergeId,
                                           @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken) {
        service.unmerge(tenantId, mergeId, staffId, stepUpToken);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/consent/withdraw")
    @PreAuthorize("hasAuthority('patients:edit')")
    public ResponseEntity<Void> withdrawConsent(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id,
                                                 @Valid @RequestBody WithdrawConsentRequest req) {
        service.withdrawConsent(tenantId, staffId, id, req.consentTypeOrDefault(), req.channel());
        return ResponseEntity.noContent().build();
    }
}

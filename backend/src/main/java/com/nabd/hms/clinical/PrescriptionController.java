package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.DoseCalculationResponse;
import com.nabd.hms.clinical.dto.DrugSuggestionResponse;
import com.nabd.hms.clinical.dto.FavouriteRxSetRequest;
import com.nabd.hms.clinical.dto.FavouriteRxSetResponse;
import com.nabd.hms.clinical.dto.PrescriptionResponse;
import com.nabd.hms.clinical.dto.PrescriptionUpsertRequest;
import com.nabd.hms.common.RequestMeta;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/clinical")
public class PrescriptionController {

    private final PrescriptionService service;

    PrescriptionController(PrescriptionService service) {
        this.service = service;
    }

    @GetMapping("/prescriptions/{queueEntryId}")
    @PreAuthorize("hasAuthority('clinical:view')")
    public PrescriptionResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId) {
        return service.get(tenantId(jwt), queueEntryId);
    }

    @PatchMapping("/prescriptions/{queueEntryId}")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public PrescriptionResponse upsert(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId,
                                        @Valid @RequestBody PrescriptionUpsertRequest req, HttpServletRequest http) {
        return service.upsert(tenantId(jwt), queueEntryId, staffId(jwt), canApprove(jwt), RequestMeta.clientIp(http), req.items());
    }

    @PostMapping("/prescriptions/{queueEntryId}/sign")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public PrescriptionResponse sign(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId, HttpServletRequest http) {
        return service.sign(tenantId(jwt), queueEntryId, staffId(jwt), canApprove(jwt), RequestMeta.clientIp(http));
    }

    @GetMapping("/patients/{patientId}/prescriptions")
    @PreAuthorize("hasAuthority('clinical:view')")
    public List<PrescriptionResponse> previousForPatient(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId) {
        return service.previousForPatient(tenantId(jwt), patientId);
    }

    @GetMapping("/patients/{patientId}/dose-calculator")
    @PreAuthorize("hasAuthority('clinical:view')")
    public DoseCalculationResponse calculateDose(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId,
                                                  @RequestParam BigDecimal mgPerKg) {
        return service.calculateDose(tenantId(jwt), patientId, mgPerKg);
    }

    /** DOC-033: drug type-ahead from the tenant's own pharmacy item master and prescribing history (names only). */
    @GetMapping("/drugs/search")
    @PreAuthorize("hasAuthority('clinical:view') or hasAuthority('clinical:edit')")
    public List<DrugSuggestionResponse> searchDrugs(@AuthenticationPrincipal Jwt jwt, @RequestParam String q,
                                                     @RequestParam(defaultValue = "8") int limit) {
        return service.searchDrugs(tenantId(jwt), q, limit);
    }

    @PostMapping("/rx-sets")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public FavouriteRxSetResponse createSet(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody FavouriteRxSetRequest req) {
        return service.createSet(tenantId(jwt), staffId(jwt), req);
    }

    @GetMapping("/rx-sets")
    @PreAuthorize("hasAuthority('clinical:view')")
    public List<FavouriteRxSetResponse> listSets(@AuthenticationPrincipal Jwt jwt) {
        return service.listSets(tenantId(jwt), staffId(jwt));
    }

    @PostMapping("/prescriptions/{queueEntryId}/apply-set/{setId}")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public PrescriptionResponse applySet(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId,
                                          @PathVariable UUID setId, HttpServletRequest http) {
        return service.applySet(tenantId(jwt), queueEntryId, setId, staffId(jwt), canApprove(jwt), RequestMeta.clientIp(http));
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    /** V1-007: the explicit permission that lets someone other than the visit's doctor record a warning decision
     * or sign (a covering doctor, a clinical lead). It is the existing `approve` action of the `clinical` module
     * in the role matrix; nothing new to seed. */
    private static boolean canApprove(Jwt jwt) {
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        return permissions != null && permissions.contains(PrescriptionService.PERMISSION_CLINICAL_APPROVE);
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}

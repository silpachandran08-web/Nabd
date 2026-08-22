package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.DoseCalculationResponse;
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
        return service.upsert(tenantId(jwt), queueEntryId, staffId(jwt), RequestMeta.clientIp(http), req.items());
    }

    @PostMapping("/prescriptions/{queueEntryId}/sign")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public PrescriptionResponse sign(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID queueEntryId) {
        return service.sign(tenantId(jwt), queueEntryId);
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
        return service.applySet(tenantId(jwt), queueEntryId, setId, staffId(jwt), RequestMeta.clientIp(http));
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}

package com.nabd.hms.patient;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.patient.dto.PatientDetailResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/** FEF-016: GET /patients/{id} for many patients in one call (My Day, consult queue), same gates as the single read. */
@RestController
@RequestMapping("/v1/patients")
public class PatientBatchController {

    static final int MAX_IDS = 100;

    /** ACC-022: the header a board row needs (first name + last initial; no phone, no date of birth). */
    @Schema(name = "PatientBatchSummary")
    public record Summary(UUID id, String mrn, String displayName, String gender, boolean allergyFlag, boolean isMinor) {
    }

    @Schema(name = "PatientBatchPage")
    public record SummaryPage(List<Summary> items) {
    }
    private final PatientService service;

    PatientBatchController(PatientService service) {
        this.service = service;
    }

    /** Unknown or scoped-out ids are simply absent from the answer. */
    @com.nabd.hms.shared.phiread.PhiRead(value = "patient_batch_list", key = "id")
    @GetMapping("/batch")
    @PreAuthorize("hasAuthority('patients:view')")
    public List<PatientDetailResponse> batch(@AuthenticationPrincipal Jwt jwt, @RequestParam List<UUID> ids) {
        if (ids.size() > MAX_IDS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "At most " + MAX_IDS + " ids per call.");
        }
        return List.copyOf(service.getVisibleMany(UUID.fromString(jwt.getClaimAsString("tenantId")), UUID.fromString(jwt.getSubject()), ids).values());
    }

    /** ACC-022: GET /v1/patients?ids= (max 100): one call instead of one per board row; same visibility gates, absent ids are silently dropped. */
    @com.nabd.hms.shared.phiread.PhiRead(value = "patient_summary_list", key = "id")
    @GetMapping(params = "ids")
    @PreAuthorize("hasAuthority('patients:view')")
    public SummaryPage summaries(@AuthenticationPrincipal Jwt jwt, @RequestParam List<UUID> ids) {
        if (ids.size() > MAX_IDS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "too-many-ids", "Too many ids", "At most " + MAX_IDS + " ids per call.",
                    java.util.Map.of("code", "TOO_MANY_IDS"));
        }
        var found = service.getVisibleMany(UUID.fromString(jwt.getClaimAsString("tenantId")), UUID.fromString(jwt.getSubject()), ids);
        return new SummaryPage(found.values().stream().map(p -> new Summary(p.id(), p.mrn(),
                com.nabd.hms.common.DisplayNames.firstNameLastInitial(p.name()), p.gender(),
                p.allergies() != null && !p.allergies().isEmpty(), p.isMinor())).toList());
    }
}

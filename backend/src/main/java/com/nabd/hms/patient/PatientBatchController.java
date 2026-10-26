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

import java.util.List;
import java.util.UUID;

/** FEF-016: GET /patients/{id} for many patients in one call (My Day, consult queue), same gates as the single read. */
@RestController
@RequestMapping("/v1/patients")
public class PatientBatchController {

    static final int MAX_IDS = 100;
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
}

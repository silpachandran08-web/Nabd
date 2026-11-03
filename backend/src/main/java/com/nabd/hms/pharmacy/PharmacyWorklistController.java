package com.nabd.hms.pharmacy;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.pharmacy.dto.WorklistDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** PHL-012: the In-house dispensing worklist. Live updates come on the SSE topic pharmacy.worklist (GET /v1/events). */
@RestController
@RequestMapping("/v1/pharmacy/worklist")
public class PharmacyWorklistController {

    private final PharmacyWorklistService service;

    PharmacyWorklistController(PharmacyWorklistService service) {
        this.service = service;
    }

    /** Newest first; status is a comma-separated filter (default new,in_progress,partially_dispensed). */
    @GetMapping
    @RequiresPharmacyMode(PharmacyMode.IN_HOUSE)
    @PreAuthorize("hasAuthority('pharmacy:dispense')")
    public WorklistDtos.Page list(@RequestParam(required = false) String status, @RequestParam(required = false) String cursor,
                                  @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.list(status, cursor, limit);
    }

    /** Start work on an entry. Two pharmacists at once: one 200, the other 409 already-claimed naming the first. */
    @PostMapping("/{id}/claim")
    @com.nabd.hms.organization.api.RequiresEntitlement("pharmacy_inhouse") // PLT-059
    @RequiresPharmacyMode(PharmacyMode.IN_HOUSE)
    @PreAuthorize("hasAuthority('pharmacy:dispense')")
    public WorklistDtos.Entry claim(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, HttpServletRequest http) {
        return service.claim(id, UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http));
    }
}

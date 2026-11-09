package com.nabd.hms.reports;

import com.nabd.hms.reports.dto.DailySummaryPreviewResponse;
import com.nabd.hms.reports.dto.DailySummarySettingsRequest;
import com.nabd.hms.reports.dto.DailySummarySettingsResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ADM-043: the owner's daily WhatsApp summary settings and preview. reports:view plus the Owner role (checked in the service). */
@RestController
@RequestMapping("/v1/reports/daily-summary")
public class DailySummaryController {

    private final DailySummaryService service;

    DailySummaryController(DailySummaryService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('reports:view')")
    public DailySummarySettingsResponse settings(@AuthenticationPrincipal Jwt jwt) {
        return service.settings(tenantId(jwt), staffId(jwt));
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('reports:view')")
    public DailySummarySettingsResponse update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DailySummarySettingsRequest req) {
        return service.update(tenantId(jwt), staffId(jwt), req);
    }

    @GetMapping("/preview")
    @PreAuthorize("hasAuthority('reports:view')")
    public DailySummaryPreviewResponse preview(@AuthenticationPrincipal Jwt jwt) {
        return service.preview(tenantId(jwt), staffId(jwt));
    }

    private static UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private static UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}

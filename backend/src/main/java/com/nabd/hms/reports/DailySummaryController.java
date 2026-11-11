package com.nabd.hms.reports;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.reports.dto.DailySummaryPreviewResponse;
import com.nabd.hms.reports.dto.DailySummarySettingsRequest;
import com.nabd.hms.reports.dto.DailySummarySettingsResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
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
    public DailySummarySettingsResponse settings(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId) {
        return service.settings(tenantId, staffId);
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('reports:view')")
    public DailySummarySettingsResponse update(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @Valid @RequestBody DailySummarySettingsRequest req) {
        return service.update(tenantId, staffId, req);
    }

    @GetMapping("/preview")
    @PreAuthorize("hasAuthority('reports:view')")
    public DailySummaryPreviewResponse preview(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId) {
        return service.preview(tenantId, staffId);
    }
}

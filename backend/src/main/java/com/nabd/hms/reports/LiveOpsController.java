package com.nabd.hms.reports;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.reports.dto.LiveOpsResponse;
import com.nabd.hms.reports.dto.OpsThresholds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ADM-042: the owner's live operations board and its alert thresholds. facility, when sent, must be the session's facility. */
@RestController
@RequestMapping("/v1/reports/live")
public class LiveOpsController {

    private final LiveOpsService service;

    LiveOpsController(LiveOpsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('reports:view')")
    public LiveOpsResponse live(@CurrentTenant UUID tenantId, @RequestParam(required = false) UUID facility) {
        return service.live(tenantId, facility);
    }

    @PutMapping("/thresholds")
    @PreAuthorize("hasAuthority('setup:edit')")
    public OpsThresholds thresholds(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @Valid @RequestBody OpsThresholds req, HttpServletRequest http) {
        return service.updateThresholds(tenantId, staffId, RequestMeta.clientIp(http), req);
    }
}

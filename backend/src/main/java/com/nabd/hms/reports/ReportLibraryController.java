package com.nabd.hms.reports;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.reports.dto.ReportExportRequest;
import com.nabd.hms.reports.dto.ReportExportResponse;
import com.nabd.hms.reports.dto.ReportFrame;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** ADM-047: the reports library on the common frame. Literal paths of ReportsController (overview, money, sources ...) win over {reportId}. */
@RestController
@RequestMapping("/v1/reports")
public class ReportLibraryController {

    private final ReportLibraryService service;

    ReportLibraryController(ReportLibraryService service) {
        this.service = service;
    }

    /** from/to are clinic days (default: last 30 days), branch is the facility (this clinic), cursor continues the drill-down rows (50 per page). */
    @GetMapping("/{reportId}")
    @PreAuthorize("hasAuthority('reports:view')")
    public ReportFrame run(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable String reportId,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           @RequestParam(required = false) UUID branch, @RequestParam(required = false) String cursor,
                           @RequestParam(required = false) BigDecimal threshold) {
        return service.run(tenantId, staffId, reportId, from, to, branch, cursor, threshold);
    }

    @PostMapping("/{reportId}/exports")
    @PreAuthorize("hasAuthority('reports:export')")
    public ReportExportResponse export(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable String reportId,
                                       @Valid @RequestBody ReportExportRequest req, HttpServletRequest http) {
        return service.export(tenantId, staffId, RequestMeta.clientIp(http), reportId, req);
    }
}

package com.nabd.hms.reports;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import com.nabd.hms.reports.dto.MoneyResponse;
import com.nabd.hms.reports.dto.OverviewResponse;
import com.nabd.hms.reports.dto.PackagesReportResponse;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.reports.dto.BillingLeakageResponse;
import com.nabd.hms.reports.dto.DailyMoneyResponse;
import com.nabd.hms.reports.dto.DoctorPunctualityResponse;
import com.nabd.hms.reports.dto.NoShowRiskResponse;
import com.nabd.hms.reports.dto.RetentionResponse;
import com.nabd.hms.reports.dto.SourceBreakdownResponse;
import com.nabd.hms.reports.dto.StaffPerformanceReport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/reports")
public class ReportsController {

    private final ReportsService service;

    ReportsController(ReportsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('reports:view')")
    public OverviewResponse overview(@CurrentTenant UUID tenantId, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.overview(tenantId, from, to);
    }

    @GetMapping("/daily-money")
    @PreAuthorize("hasAuthority('reports:view')")
    public DailyMoneyResponse dailyMoney(@CurrentTenant UUID tenantId) {
        return service.dailyMoney(tenantId);
    }

    /** Reports → Today's money. from/to are clinic days, inclusive; both default to today. */
    @GetMapping("/money")
    @PreAuthorize("hasAuthority('reports:view')")
    public MoneyResponse money(@CurrentTenant UUID callerTenantId, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID tenantId = callerTenantId;
        return service.money(tenantId, service.range(tenantId, 0, from, to));
    }

    /** from/to (clinic days, inclusive) win over days; days alone keeps the old "last N days" window. */
    @GetMapping("/sources")
    @PreAuthorize("hasAuthority('reports:view')")
    public List<SourceBreakdownResponse> sources(@CurrentTenant UUID callerTenantId,
                                                   @RequestParam(defaultValue = "30") int days, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID tenantId = callerTenantId;
        return service.sourceBreakdown(tenantId, service.range(tenantId, days, from, to));
    }

    /** OWN-026 Packages tab. Same period rules as /sources. */
    @GetMapping("/packages")
    @PreAuthorize("hasAuthority('reports:view')")
    public PackagesReportResponse packages(@CurrentTenant UUID callerTenantId,
                                           @RequestParam(defaultValue = "30") int days, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID tenantId = callerTenantId;
        return service.packages(tenantId, service.range(tenantId, days, from, to));
    }

    @GetMapping("/staff-performance")
    @PreAuthorize("hasAuthority('reports:view')")
    public StaffPerformanceReport staffPerformance(@CurrentTenant UUID callerTenantId, @CurrentStaff UUID staffId,
                                                    @RequestParam(defaultValue = "30") int days, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID tenantId = callerTenantId;
        return service.staffPerformanceReport(tenantId, staffId, service.range(tenantId, days, from, to));
    }

    @GetMapping("/billing-leakage")
    @PreAuthorize("hasAuthority('reports:view')")
    public BillingLeakageResponse billingLeakage(@CurrentTenant UUID tenantId,
                                                  @RequestParam(defaultValue = "0") BigDecimal thresholdAmount) {
        return service.billingLeakage(tenantId, thresholdAmount);
    }

    @GetMapping("/doctor-punctuality")
    @PreAuthorize("hasAuthority('reports:view')")
    public DoctorPunctualityResponse doctorPunctuality(@CurrentTenant UUID tenantId) {
        return service.doctorPunctuality(tenantId);
    }

    @GetMapping("/retention")
    @PreAuthorize("hasAuthority('reports:view')")
    public RetentionResponse retention(@CurrentTenant UUID tenantId) {
        return service.retention(tenantId);
    }

    @GetMapping("/no-show-risk")
    @PreAuthorize("hasAuthority('reports:view')")
    public NoShowRiskResponse noShowRisk(@CurrentTenant UUID tenantId) {
        return service.noShowRisk(tenantId);
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('reports:export')")
    public ResponseEntity<String> export(@CurrentTenant UUID callerTenantId, @CurrentStaff UUID staffId, @RequestParam String reportType,
                                          @RequestParam(defaultValue = "30") int days, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                          HttpServletRequest http) {
        UUID tenantId = callerTenantId;
        String csv = service.exportCsv(tenantId, staffId, RequestMeta.clientIp(http), reportType,
                service.range(tenantId, days, from, to));
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + reportType + ".csv\"")
                .body(csv);
    }
}

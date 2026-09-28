package com.nabd.hms.reports;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import com.nabd.hms.reports.dto.MoneyResponse;
import com.nabd.hms.reports.dto.OverviewResponse;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
    public OverviewResponse overview(@AuthenticationPrincipal Jwt jwt) {
        return service.overview(tenantId(jwt));
    }

    @GetMapping("/daily-money")
    @PreAuthorize("hasAuthority('reports:view')")
    public DailyMoneyResponse dailyMoney(@AuthenticationPrincipal Jwt jwt) {
        return service.dailyMoney(tenantId(jwt));
    }

    /** Reports → Today's money. from/to are clinic days, inclusive; both default to today. */
    @GetMapping("/money")
    @PreAuthorize("hasAuthority('reports:view')")
    public MoneyResponse money(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID tenantId = tenantId(jwt);
        return service.money(tenantId, service.range(tenantId, 0, from, to));
    }

    /** from/to (clinic days, inclusive) win over days; days alone keeps the old "last N days" window. */
    @GetMapping("/sources")
    @PreAuthorize("hasAuthority('reports:view')")
    public List<SourceBreakdownResponse> sources(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestParam(defaultValue = "30") int days, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID tenantId = tenantId(jwt);
        return service.sourceBreakdown(tenantId, service.range(tenantId, days, from, to));
    }

    @GetMapping("/staff-performance")
    @PreAuthorize("hasAuthority('reports:view')")
    public StaffPerformanceReport staffPerformance(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestParam(defaultValue = "30") int days, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UUID tenantId = tenantId(jwt);
        return service.staffPerformanceReport(tenantId, staffId(jwt), service.range(tenantId, days, from, to));
    }

    @GetMapping("/billing-leakage")
    @PreAuthorize("hasAuthority('reports:view')")
    public BillingLeakageResponse billingLeakage(@AuthenticationPrincipal Jwt jwt,
                                                  @RequestParam(defaultValue = "0") BigDecimal thresholdAmount) {
        return service.billingLeakage(tenantId(jwt), thresholdAmount);
    }

    @GetMapping("/doctor-punctuality")
    @PreAuthorize("hasAuthority('reports:view')")
    public DoctorPunctualityResponse doctorPunctuality(@AuthenticationPrincipal Jwt jwt) {
        return service.doctorPunctuality(tenantId(jwt));
    }

    @GetMapping("/retention")
    @PreAuthorize("hasAuthority('reports:view')")
    public RetentionResponse retention(@AuthenticationPrincipal Jwt jwt) {
        return service.retention(tenantId(jwt));
    }

    @GetMapping("/no-show-risk")
    @PreAuthorize("hasAuthority('reports:view')")
    public NoShowRiskResponse noShowRisk(@AuthenticationPrincipal Jwt jwt) {
        return service.noShowRisk(tenantId(jwt));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAuthority('reports:export')")
    public ResponseEntity<String> export(@AuthenticationPrincipal Jwt jwt, @RequestParam String reportType,
                                          @RequestParam(defaultValue = "30") int days, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                          HttpServletRequest http) {
        UUID tenantId = tenantId(jwt);
        String csv = service.exportCsv(tenantId, staffId(jwt), RequestMeta.clientIp(http), reportType,
                service.range(tenantId, days, from, to));
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + reportType + ".csv\"")
                .body(csv);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}

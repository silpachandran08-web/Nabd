package com.nabd.hms.reports;

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

    @GetMapping("/daily-money")
    @PreAuthorize("hasAuthority('reports:view')")
    public DailyMoneyResponse dailyMoney(@AuthenticationPrincipal Jwt jwt) {
        return service.dailyMoney(tenantId(jwt));
    }

    @GetMapping("/sources")
    @PreAuthorize("hasAuthority('reports:view')")
    public List<SourceBreakdownResponse> sources(@AuthenticationPrincipal Jwt jwt,
                                                   @RequestParam(defaultValue = "30") int days) {
        return service.sourceBreakdown(tenantId(jwt), days);
    }

    @GetMapping("/staff-performance")
    @PreAuthorize("hasAuthority('reports:view')")
    public StaffPerformanceReport staffPerformance(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestParam(defaultValue = "30") int days) {
        return service.staffPerformanceReport(tenantId(jwt), staffId(jwt), days);
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
                                          @RequestParam(defaultValue = "30") int days, HttpServletRequest http) {
        String csv = service.exportCsv(tenantId(jwt), staffId(jwt), RequestMeta.clientIp(http), reportType, days);
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

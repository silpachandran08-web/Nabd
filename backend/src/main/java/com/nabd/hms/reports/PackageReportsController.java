package com.nabd.hms.reports;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.reports.dto.ReportFrame;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * ADM-049: GET /v1/reports/packages?view=sales|redemption|expiry, the spec's address for the three library reports
 * (package-sales, package-redemption, package-expiry-loss); same frame, filters and gates as GET /v1/reports/{reportId}.
 * Without {@code view} the path stays OWN-026's ReportsController.packages, unchanged.
 */
@RestController
@RequestMapping("/v1/reports")
public class PackageReportsController {

    private final ReportLibraryService service;

    PackageReportsController(ReportLibraryService service) {
        this.service = service;
    }

    // Hidden from OpenAPI on purpose: springdoc would merge it into OWN-026's operation as a oneOf response, which changes that
    // published contract. The documented operations for these reports are GET /v1/reports/{reportId} and its /exports.
    @Hidden
    @GetMapping(value = "/packages", params = "view")
    @PreAuthorize("hasAuthority('reports:view')")
    public ReportFrame packages(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @RequestParam String view,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(required = false) UUID branch, @RequestParam(required = false) String cursor) {
        String reportId = switch (view) {
            case "sales" -> PackageReports.SALES;
            case "redemption" -> PackageReports.REDEMPTION;
            case "expiry" -> PackageReports.EXPIRY;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "invalid-view", "Invalid view", "The view must be sales, redemption or expiry.");
        };
        return service.run(tenantId, staffId, reportId, from, to, branch, cursor, null);
    }
}

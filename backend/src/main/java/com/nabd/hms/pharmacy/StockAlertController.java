package com.nabd.hms.pharmacy;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.pharmacy.dto.StockAlertDtos.AcknowledgeRequest;
import com.nabd.hms.pharmacy.dto.StockAlertDtos.Alert;
import com.nabd.hms.pharmacy.dto.StockAlertDtos.AlertPage;
import com.nabd.hms.pharmacy.dto.StockAlertDtos.ExpiryReport;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** PHL-008: open stock alerts, acknowledge with the action taken, and the expiry report (JSON or CSV). */
@RestController
@RequestMapping("/v1/pharmacy")
public class StockAlertController {

    private final StockAlertService service;

    StockAlertController(StockAlertService service) {
        this.service = service;
    }

    /** type = expiry | low | dead (omitted = all); counts are the open alerts per tab, for the badges. */
    @GetMapping("/alerts")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public AlertPage alerts(@RequestParam(required = false) String type, @RequestParam(required = false) String cursor,
                            @RequestParam(defaultValue = "50") int limit) {
        return service.open(type, cursor, limit);
    }

    /** Idempotent: acknowledging an acknowledged alert returns it unchanged. */
    @PostMapping("/alerts/{id}/acknowledge")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public Alert acknowledge(@CurrentStaff UUID staffId, @PathVariable UUID id, @Valid @RequestBody AcknowledgeRequest req, HttpServletRequest http) {
        return service.acknowledge(staffId, RequestMeta.clientIp(http), id, req);
    }

    /** Batches expiring within withinDays (expired ones first), value at risk = qty x purchase rate. format=csv downloads it. */
    @GetMapping("/reports/expiry")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    @ApiResponse(responseCode = "200", description = "The report", content = {
            @Content(mediaType = "application/json", schema = @Schema(implementation = ExpiryReport.class)),
            @Content(mediaType = "text/csv", schema = @Schema(type = "string"))})
    public ResponseEntity<?> expiryReport(@CurrentStaff UUID staffId, @RequestParam(defaultValue = "90") int withinDays,
                                          @RequestParam(defaultValue = "json") String format, HttpServletRequest http) {
        if ("csv".equals(format)) {
            return ResponseEntity.ok().contentType(MediaType.valueOf("text/csv"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"expiry-report-" + withinDays + "-days.csv\"")
                    .body(service.expiryCsv(staffId, RequestMeta.clientIp(http), withinDays));
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(service.expiryReport(withinDays));
    }
}

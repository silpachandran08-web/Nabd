package com.nabd.hms.billing;

import com.nabd.hms.billing.dto.DeskSummaryResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** ADM-044: lives in billing because it reuses the day close's queries; the clinic comes from the token, never a parameter. */
@RestController
public class DeskSummaryController {

    private final DayCloseService service;

    DeskSummaryController(DayCloseService service) {
        this.service = service;
    }

    @GetMapping("/v1/reports/desk-summary")
    @PreAuthorize("hasAuthority('queue:view')")
    public DeskSummaryResponse deskSummary(@AuthenticationPrincipal Jwt jwt,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.deskSummary(UUID.fromString(jwt.getClaimAsString("tenantId")), date);
    }
}

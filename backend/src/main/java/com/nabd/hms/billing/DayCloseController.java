package com.nabd.hms.billing;

import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.billing.dto.DayCloseHistoryResponse;
import com.nabd.hms.billing.dto.DayCloseRequest;
import com.nabd.hms.billing.dto.DayCloseSummaryResponse;
import com.nabd.hms.billing.dto.DayReopenRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Closing and reopening need billing:approve — the Owner / Clinic Manager, per the wireframe. */
@RestController
@RequestMapping("/v1/billing/day-close")
public class DayCloseController {

    private final DayCloseService service;

    DayCloseController(DayCloseService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('billing:view')")
    public DayCloseSummaryResponse summary(@CurrentTenant UUID tenantId,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.summary(tenantId, date);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('billing:approve')")
    @com.nabd.hms.shared.Idempotent // FIN-005 spec: a retried close with the same Idempotency-Key replays the first 200
    public DayCloseSummaryResponse close(@CurrentTenant UUID tenantId, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DayCloseRequest req) {
        return service.close(tenantId, UUID.fromString(jwt.getSubject()), req);
    }

    @PostMapping("/reopen")
    @PreAuthorize("hasAuthority('billing:approve')")
    public DayCloseSummaryResponse reopen(@CurrentTenant UUID tenantId, @AuthenticationPrincipal Jwt jwt,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                          @Valid @RequestBody DayReopenRequest req) {
        return service.reopen(tenantId, UUID.fromString(jwt.getSubject()), date, req);
    }

    /** FIN-005: every close and reopen in [from, to], newest first. */
    @GetMapping("/history")
    @PreAuthorize("hasAuthority('billing:view')")
    public DayCloseHistoryResponse history(@CurrentTenant UUID tenantId,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                           @RequestParam(required = false) String cursor,
                                           @RequestParam(defaultValue = "50") int limit) {
        return service.history(tenantId, from, to, cursor, limit);
    }
}

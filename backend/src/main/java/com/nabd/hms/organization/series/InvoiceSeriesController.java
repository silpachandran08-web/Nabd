package com.nabd.hms.organization.series;

import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.organization.series.dto.InvoiceSeriesConfigRequest;
import com.nabd.hms.organization.series.dto.InvoiceSeriesListResponse;
import com.nabd.hms.organization.series.dto.InvoiceSeriesResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/organization/invoice-series")
public class InvoiceSeriesController {

    private final InvoiceSeriesService service;

    InvoiceSeriesController(InvoiceSeriesService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('setup:view')")
    public InvoiceSeriesListResponse list() {
        return service.list();
    }

    @PutMapping("/{kind}")
    @PreAuthorize("hasAuthority('setup:edit')")
    public InvoiceSeriesResponse configure(@AuthenticationPrincipal Jwt jwt, @PathVariable String kind,
                                           @Valid @RequestBody InvoiceSeriesConfigRequest req, HttpServletRequest http) {
        return service.configure(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), kind, req);
    }
}

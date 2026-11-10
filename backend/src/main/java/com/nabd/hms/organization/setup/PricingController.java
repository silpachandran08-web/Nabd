package com.nabd.hms.organization.setup;

import jakarta.validation.Valid;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.organization.setup.dto.PolicyResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** FIN-015 per-doctor fees + follow-up window, FIN-016 price change log, FIN-017 specialty starter price lists. */
@RestController
@RequestMapping("/v1/setup")
public class PricingController {

    public record WindowRequest(Integer value) {
    }

    private final PricingService service;

    PricingController(PricingService service) {
        this.service = service;
    }

    @GetMapping("/charges/{id}/doctor-fees")
    @PreAuthorize("hasAuthority('setup:view')")
    public PricingService.FeesResponse fees(@PathVariable UUID id) {
        return service.fees(id);
    }

    @PutMapping("/charges/{id}/doctor-fees")
    @PreAuthorize("hasAuthority('setup:edit')")
    public PricingService.FeesResponse setFees(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody PricingService.FeesRequest req,
                                               HttpServletRequest http) {
        return service.setFees(id, UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), req);
    }

    @PutMapping("/policies/follow_up_window_days")
    @PreAuthorize("hasAuthority('setup:edit')")
    public PolicyResponse followUpWindow(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WindowRequest req, HttpServletRequest http) {
        return service.setFollowUpWindow(req.value(), UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http));
    }

    @GetMapping("/price-templates")
    @PreAuthorize("hasAuthority('setup:view')")
    public PricingService.TemplateView priceTemplate(@RequestParam(required = false) String specialty, @RequestParam(required = false) String jurisdiction) {
        return service.template(specialty, jurisdiction);
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/charges/bulk")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ResponseEntity<PricingService.BulkResponse> bulk(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PricingService.BulkRequest req, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.applyTemplate(UUID.fromString(jwt.getSubject()), RequestMeta.clientIp(http), req));
    }

    @GetMapping("/charges/changes")
    @PreAuthorize("hasAuthority('setup:view')")
    public PricingService.ChangePage changes(@RequestParam(required = false) UUID chargeId, @RequestParam(required = false) String field,
                                             @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                             @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        return service.changes(chargeId, field, from, to, cursor, limit);
    }

    @GetMapping("/charges/changes/export")
    @PreAuthorize("hasAuthority('setup:view')")
    public ResponseEntity<String> export(@RequestParam(required = false) UUID chargeId, @RequestParam(required = false) String field,
                                         @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"price-changes.csv\"")
                .body(service.changesCsv(chargeId, field, from, to));
    }
}

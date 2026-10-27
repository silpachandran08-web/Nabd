package com.nabd.hms.organization.setup;

import com.nabd.hms.organization.setup.dto.ApplyTemplateRequest;
import com.nabd.hms.organization.setup.dto.ApplyTemplateResponse;
import com.nabd.hms.organization.setup.dto.ClinicHolidayResponse;
import com.nabd.hms.organization.setup.dto.HolidayEffectRequest;
import com.nabd.hms.organization.setup.dto.HolidayTemplateResponse;
import com.nabd.hms.organization.setup.dto.QuietHoursRequest;
import com.nabd.hms.organization.setup.dto.QuietHoursResponse;
import com.nabd.hms.shared.Idempotent;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** ADM-028: holiday templates, a holiday's effect and quiet hours. */
@RestController
@RequestMapping("/v1/setup")
public class CalendarController {

    private final CalendarService service;

    CalendarController(CalendarService service) {
        this.service = service;
    }

    @GetMapping("/calendar/templates")
    @PreAuthorize("hasAuthority('setup:view')")
    public List<HolidayTemplateResponse> templates(@RequestParam(defaultValue = "IN") String market) {
        return service.templates(market);
    }

    @Idempotent
    @PostMapping("/calendar/templates/{code}/apply")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ApplyTemplateResponse apply(@AuthenticationPrincipal Jwt jwt, @PathVariable String code, @Valid @RequestBody ApplyTemplateRequest req) {
        return service.apply(UUID.fromString(jwt.getSubject()), code, req.year());
    }

    @Idempotent
    @PatchMapping("/holidays/{id}")
    @PreAuthorize("hasAuthority('setup:edit')")
    public ClinicHolidayResponse setEffect(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody HolidayEffectRequest req) {
        return service.setEffect(UUID.fromString(jwt.getSubject()), id, req);
    }

    @GetMapping("/calendar/quiet-hours")
    @PreAuthorize("hasAuthority('setup:view')")
    public QuietHoursResponse quietHours() {
        return service.quietHours();
    }

    @PutMapping("/calendar/quiet-hours")
    @PreAuthorize("hasAuthority('setup:edit')")
    public QuietHoursResponse putQuietHours(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody QuietHoursRequest req) {
        return service.putQuietHours(UUID.fromString(jwt.getSubject()), req);
    }
}

package com.nabd.hms.platform.fleet;

import com.nabd.hms.platform.fleet.dto.FleetPage;
import com.nabd.hms.platform.fleet.dto.FleetSummaryResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Gated on clinics_fleet:view (NB-257's matrix) — every role except sre carries it. */
@RestController
@RequestMapping("/v1/platform/tenants")
@PreAuthorize("hasAuthority('clinics_fleet:view')")
public class FleetController {

    private final FleetService service;

    FleetController(FleetService service) {
        this.service = service;
    }

    @GetMapping
    public FleetPage list(@RequestParam(defaultValue = "50") int limit,
                           @RequestParam(required = false) String cursor) {
        return service.list(limit, cursor);
    }

    @GetMapping("/summary")
    public FleetSummaryResponse summary() {
        return service.summary();
    }
}

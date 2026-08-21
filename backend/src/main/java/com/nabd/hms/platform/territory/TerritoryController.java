package com.nabd.hms.platform.territory;

import com.nabd.hms.platform.territory.dto.RegionSummaryResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Gated on territories:view (NB-257's matrix) — super_admin, sre, commercial, compliance_dpo. */
@RestController
@RequestMapping("/v1/platform/territories")
@PreAuthorize("hasAuthority('territories:view')")
public class TerritoryController {

    private final TerritoryService service;

    TerritoryController(TerritoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<RegionSummaryResponse> list() {
        return service.list();
    }
}

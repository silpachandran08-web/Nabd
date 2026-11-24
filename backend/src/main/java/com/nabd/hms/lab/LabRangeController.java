package com.nabd.hms.lab;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.lab.LabRangeDtos.RangeSet;
import com.nabd.hms.lab.LabRangeDtos.RangeSetRequest;
import com.nabd.hms.shared.Idempotent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** PHL-042: reference ranges of a lab test. Behind the lab entitlement like every /v1/lab path (LabEntitlementGuard). */
@RestController
@RequestMapping("/v1/lab/tests/{id}/ranges")
public class LabRangeController {

    private final LabRangeService service;

    LabRangeController(LabRangeService service) {
        this.service = service;
    }

    /** The current range set, or an earlier one by version (what an old result was flagged against). */
    @GetMapping
    @PreAuthorize("hasAuthority('lab:view')")
    public RangeSet ranges(@PathVariable UUID id, @RequestParam(required = false) Integer version) {
        return service.ranges(id, version);
    }

    /** Replaces the range set: stored as a new version. 422 invalid-band or overlapping-bands; nothing is stored then. */
    @Idempotent
    @PutMapping
    @PreAuthorize("hasAuthority('lab:validate')")
    public RangeSet replace(@CurrentStaff UUID staffId, @PathVariable UUID id, @Valid @RequestBody RangeSetRequest req, HttpServletRequest http) {
        return service.replace(staffId, RequestMeta.clientIp(http), id, req);
    }
}

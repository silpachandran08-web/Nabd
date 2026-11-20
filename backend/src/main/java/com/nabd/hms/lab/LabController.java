package com.nabd.hms.lab;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.lab.LabDtos.ImportRequest;
import com.nabd.hms.lab.LabDtos.ImportResult;
import com.nabd.hms.lab.LabDtos.LabPanel;
import com.nabd.hms.lab.LabDtos.LabTest;
import com.nabd.hms.lab.LabDtos.PanelList;
import com.nabd.hms.lab.LabDtos.PanelRequest;
import com.nabd.hms.lab.LabDtos.TestPage;
import com.nabd.hms.lab.LabDtos.TestPatch;
import com.nabd.hms.lab.LabDtos.TestRequest;
import com.nabd.hms.shared.Idempotent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * PHL-041: the lab test and panel catalogue. Every path here also needs the clinic's `lab` entitlement (LabEntitlementGuard:
 * 403 module-not-entitled). lab:view reads; lab:catalogue (the lab manager's grant) changes the catalogue.
 */
@RestController
@RequestMapping("/v1/lab")
public class LabController {

    private final LabCatalogueService service;

    LabController(LabCatalogueService service) {
        this.service = service;
    }

    /** q matches code, name or LOINC; ordered by code, 50 a page (limit 1 to 200). */
    @GetMapping("/tests")
    @PreAuthorize("hasAuthority('lab:view')")
    public TestPage tests(@RequestParam(required = false) String q, @RequestParam(required = false) String cursor,
                          @RequestParam(defaultValue = "50") int limit) {
        return service.tests(q, cursor, limit);
    }

    /** 409 duplicate-code when the clinic's catalogue or price list already has the code. */
    @Idempotent
    @PostMapping("/tests")
    @PreAuthorize("hasAuthority('lab:catalogue')")
    public ResponseEntity<LabTest> createTest(@CurrentStaff UUID staffId, @Valid @RequestBody TestRequest req, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createTest(staffId, RequestMeta.clientIp(http), req));
    }

    /** Partial update. A price change goes to the linked price-list head with its change log; issued bills keep their price. */
    @PatchMapping("/tests/{id}")
    @PreAuthorize("hasAuthority('lab:catalogue')")
    public LabTest updateTest(@CurrentStaff UUID staffId, @PathVariable UUID id, @Valid @RequestBody TestPatch req, HttpServletRequest http) {
        return service.updateTest(staffId, RequestMeta.clientIp(http), id, req);
    }

    @GetMapping("/panels")
    @PreAuthorize("hasAuthority('lab:view')")
    public PanelList panels() {
        return service.panels();
    }

    /** 409 duplicate-code; 422 unknown-test when a test is not an active test of this clinic. */
    @Idempotent
    @PostMapping("/panels")
    @PreAuthorize("hasAuthority('lab:catalogue')")
    public ResponseEntity<LabPanel> createPanel(@CurrentStaff UUID staffId, @Valid @RequestBody PanelRequest req, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createPanel(staffId, RequestMeta.clientIp(http), req));
    }

    /** Starter pack import; dryRun = true returns the diff and changes nothing. Add-only: existing codes are skipped. */
    @Idempotent
    @PostMapping("/catalogue/imports")
    @PreAuthorize("hasAuthority('lab:catalogue')")
    public ImportResult importCatalogue(@CurrentStaff UUID staffId, @Valid @RequestBody ImportRequest req, HttpServletRequest http) {
        return service.importPack(staffId, RequestMeta.clientIp(http), req);
    }
}

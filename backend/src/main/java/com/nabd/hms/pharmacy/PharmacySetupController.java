package com.nabd.hms.pharmacy;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.pharmacy.dto.PharmacySetupResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** PHL-032: the pharmacy setup wizard (any pharmacy mode: the owner leaves External through the same screen). */
@RestController
@RequestMapping("/v1/pharmacy/setup")
public class PharmacySetupController {

    private final PharmacySetupService service;

    PharmacySetupController(PharmacySetupService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPharmacyMode(PharmacyMode.EXTERNAL)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public PharmacySetupResponse status(@CurrentTenant UUID tenantId) {
        return service.status(tenantId);
    }

    @PostMapping("/printer-test")
    @RequiresPharmacyMode(PharmacyMode.EXTERNAL)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public Map<String, Instant> printerTest(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, HttpServletRequest http) {
        return Map.of("printerTestedAt", service.recordPrinterTest(tenantId, staffId, RequestMeta.clientIp(http)));
    }
}

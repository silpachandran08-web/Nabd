package com.nabd.hms.lab;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.shared.Idempotent;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PHL-056: the lab setup wizard (accreditation, pathologist, letterhead lines, label-printer test) and the price list import that
 * prices the starter catalogue. Under /v1/lab, so LabEntitlementGuard applies. lab:view reads; lab:catalogue (the lab manager's
 * grant) writes.
 */
@RestController
@RequestMapping("/v1/lab")
public class LabSetupController {

    /** Every field is the new value: one left out (null) or blank is cleared. letterheadLines: up to 6 lines printed under the lab's name. */
    @Schema(name = "LabSetupRequest")
    public record SetupRequest(@Size(max = 40) @Pattern(regexp = LabDtos.TEXT) String accreditationNo,
                               UUID pathologistStaffId,
                               @Size(max = 80) @Pattern(regexp = LabDtos.TEXT) String pathologistRegistrationNo,
                               @Size(max = 400) @Pattern(regexp = "^[^\\x00-\\x09\\x0B-\\x1F\\x7F]*$") String letterheadLines) {
    }

    /** activeTests: tests that have a price and can be billed (an imported starter test is inactive until it is priced). */
    @Schema(name = "LabSetup")
    public record Setup(String accreditationNo, UUID pathologistStaffId, String pathologistName, String pathologistRegistrationNo,
                        String letterheadLines, Instant printerTestedAt,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int catalogueTests,
                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int activeTests) {
    }

    /** csv: lines of code,price (a header line "code,price", # notes, further columns and a line with no price are skipped).
     * dryRun = true changes nothing and returns the diff. */
    @Schema(name = "LabPriceImportRequest")
    public record PriceImportRequest(@NotBlank @Size(max = 300_000) String csv, boolean dryRun) {
    }

    @Schema(name = "LabPriceImportLine")
    public record PriceLine(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal currentPrice,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal newPrice) {
    }

    /** line: the file's line number; message: a sentence for the person who made the file. */
    @Schema(name = "LabPriceImportProblem")
    public record Problem(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int line,
                          @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message) {
    }

    /** toPrice: tests the import prices and activates (or did); unchanged: already active at that price; unknown: codes the
     * catalogue does not have; problems: lines that cannot be read. Lines with a problem are left out, the rest are applied. */
    @Schema(name = "LabPriceImportResult")
    public record PriceImportResult(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean dryRun,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PriceLine> toPrice,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int unchanged,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> unknown,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Problem> problems,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int updated) {
    }

    private final LabSetupService service;

    LabSetupController(LabSetupService service) {
        this.service = service;
    }

    @GetMapping("/setup")
    @PreAuthorize("hasAuthority('lab:view')")
    public Setup setup() {
        return service.status();
    }

    /** 422 unknown-staff when the pathologist is not a staff member of this lab. */
    @PutMapping("/setup")
    @PreAuthorize("hasAuthority('lab:catalogue')")
    public Setup save(@CurrentStaff UUID staffId, @Valid @RequestBody SetupRequest req, HttpServletRequest http) {
        return service.save(staffId, RequestMeta.clientIp(http), req);
    }

    /** Records that the test label printed; the page itself is printed by the browser. */
    @PostMapping("/setup/printer-test")
    @PreAuthorize("hasAuthority('lab:catalogue')")
    public Map<String, Instant> printerTest(@CurrentStaff UUID staffId, HttpServletRequest http) {
        return Map.of("printerTestedAt", service.recordPrinterTest(staffId, RequestMeta.clientIp(http)));
    }

    /** Prices the catalogue from the lab's own price list. Each priced test gets its price change log row and becomes billable. */
    @Idempotent
    @PostMapping("/catalogue/price-imports")
    @PreAuthorize("hasAuthority('lab:catalogue')")
    public PriceImportResult importPrices(@CurrentStaff UUID staffId, @Valid @RequestBody PriceImportRequest req, HttpServletRequest http) {
        return service.importPrices(staffId, RequestMeta.clientIp(http), req);
    }
}

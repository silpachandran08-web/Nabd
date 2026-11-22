package com.nabd.hms.pharmacy;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.RequestMeta;
import com.nabd.hms.pharmacy.dto.QuarantineDtos;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** PHL-009: quarantine a batch, the quarantine bin, supplier returns (debit note), write-offs and the valuation split.
 * Same gates as the other stock writes. */
@RestController
@RequestMapping("/v1/pharmacy")
public class QuarantineController {

    private final QuarantineService service;

    QuarantineController(QuarantineService service) {
        this.service = service;
    }

    /** 409 already-quarantined when the batch is not active. */
    @com.nabd.hms.shared.Idempotent
    @PostMapping("/batches/{id}/quarantine")
    @com.nabd.hms.organization.api.RequiresEntitlement("pharmacy_inhouse")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public QuarantineDtos.QuarantinedBatch quarantine(@CurrentStaff UUID staffId, @PathVariable UUID id,
                                                      @Valid @RequestBody QuarantineDtos.QuarantineRequest req, HttpServletRequest http) {
        return service.quarantine(staffId, RequestMeta.clientIp(http), id, req);
    }

    /** Quarantined batches that still hold stock, with their value at purchase rate. */
    @GetMapping("/quarantine")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public QuarantineDtos.Bin bin() {
        return service.bin();
    }

    // pharmacy:purchase is not a module:action pair of the RBAC model; a supplier return is a stock write, so it takes pharmacy:edit like a goods receipt (PHL-007).
    /** 422 batch-not-quarantined when a line is not quarantined stock; 400 insufficient-stock when a batch holds less. */
    @com.nabd.hms.shared.Idempotent
    @PostMapping("/supplier-returns")
    @com.nabd.hms.organization.api.RequiresEntitlement("pharmacy_inhouse")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:edit')")
    public ResponseEntity<QuarantineDtos.SupplierReturn> supplierReturn(@CurrentStaff UUID staffId,
                                                                        @Valid @RequestBody QuarantineDtos.SupplierReturnRequest req,
                                                                        HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.supplierReturn(staffId, RequestMeta.clientIp(http), req));
    }

    /** The return note, for printing again. */
    @GetMapping("/supplier-returns/{id}")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public QuarantineDtos.SupplierReturn supplierReturn(@PathVariable UUID id) {
        return service.getReturn(id);
    }

    /** 422 witness-required when a controlled-class line has no valid witness (witnessStaffId + witnessPin). */
    @com.nabd.hms.shared.Idempotent
    @PostMapping("/write-offs")
    @com.nabd.hms.organization.api.RequiresEntitlement("pharmacy_inhouse")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:adjust_approve')")
    public ResponseEntity<QuarantineDtos.WriteOff> writeOff(@CurrentStaff UUID staffId, @Valid @RequestBody QuarantineDtos.WriteOffRequest req,
                                                            HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.writeOff(staffId, RequestMeta.clientIp(http), req));
    }

    /** Stock value at purchase rate, saleable and quarantined shown separately. */
    @GetMapping("/reports/valuation")
    @RequiresPharmacyMode(PharmacyMode.HYBRID)
    @PreAuthorize("hasAuthority('pharmacy:view')")
    public QuarantineDtos.Valuation valuation() {
        return service.valuation();
    }
}

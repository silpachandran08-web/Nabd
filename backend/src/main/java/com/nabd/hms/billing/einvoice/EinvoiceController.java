package com.nabd.hms.billing.einvoice;

import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.RequestMeta;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** FIN-011: IRP e-invoice status, retry and the owner's switch. */
@RestController
@RequestMapping("/v1/billing")
class EinvoiceController {

    private final EinvoiceService service;

    EinvoiceController(EinvoiceService service) {
        this.service = service;
    }

    /** 202 with the submission's status; 409 NOT_APPLICABLE for a bill that has no e-invoice (switch off, no buyer GSTIN, void). Safe to repeat. */
    @PostMapping("/invoices/{id}/einvoice/retry")
    @PreAuthorize("hasAuthority('billing:edit')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    EinvoiceDtos.RetryResponse retry(@CurrentStaff UUID staffId, @PathVariable UUID id, HttpServletRequest http) {
        return service.retry(staffId, RequestMeta.clientIp(http), id);
    }

    /** The clinic's newest 50 e-invoice submissions with their IRN status (the chip beside an invoice). */
    @GetMapping("/einvoices")
    @PreAuthorize("hasAuthority('billing:view')")
    EinvoiceDtos.ListResponse list() {
        return service.list();
    }

    @GetMapping("/einvoice/settings")
    @PreAuthorize("hasAuthority('billing:view')")
    EinvoiceDtos.Settings settings() {
        return service.settings();
    }

    /** Owner only (403 OWNER_REQUIRED); 409 IRP_NOT_CONFIGURED while no IRP adapter is deployed. */
    @PutMapping("/einvoice/settings")
    @PreAuthorize("hasAuthority('billing:approve')")
    EinvoiceDtos.Settings switchIrp(@CurrentStaff UUID staffId, @Valid @RequestBody EinvoiceDtos.SettingsRequest req, HttpServletRequest http) {
        return service.setIrpEnabled(staffId, RequestMeta.clientIp(http), req.irpEnabled());
    }
}

package com.nabd.hms.billing.document;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** FIN-012: the invoice / receipt PDF. */
@RestController
@RequestMapping("/v1/billing")
class InvoiceDocumentController {

    private final InvoiceDocumentService service;

    InvoiceDocumentController(InvoiceDocumentService service) {
        this.service = service;
    }

    @GetMapping(value = "/invoices/{id}/document", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAuthority('billing:view')")
    ResponseEntity<byte[]> document(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                    @RequestParam(defaultValue = "a4") String layout, @RequestParam(defaultValue = "invoice") String kind) {
        byte[] pdf = service.document(id, layout, kind, UUID.fromString(jwt.getSubject()));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(kind + "-" + layout + ".pdf").build().toString())
                .body(pdf);
    }
}

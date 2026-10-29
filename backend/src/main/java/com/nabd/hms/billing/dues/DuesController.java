package com.nabd.hms.billing.dues;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** FIN-014: invoice list, dues list, reminders, write-offs. */
@RestController
@RequestMapping("/v1/billing")
class DuesController {

    private final DuesService service;

    DuesController(DuesService service) {
        this.service = service;
    }

    @GetMapping("/invoices")
    @PreAuthorize("hasAuthority('billing:view')")
    InvoiceListResponse invoices(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
                                 @RequestParam(required = false) String status, @RequestParam(required = false) UUID doctorId,
                                 @RequestParam(required = false) String method, @RequestParam(required = false) String q,
                                 @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        return service.list(from, to, status, doctorId, method, q, cursor, limit);
    }

    @GetMapping("/dues")
    @PreAuthorize("hasAuthority('billing:view')")
    DuesResponse dues(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String bucket, @RequestParam(required = false) String cursor,
                      @RequestParam(defaultValue = "50") int limit) {
        return service.dues(bucket, cursor, limit);
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/dues/{patientId}/remind")
    @PreAuthorize("hasAuthority('billing:edit')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, UUID> remind(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId, @Valid @RequestBody RemindRequest req) {
        return Map.of("reminderId", service.remind(patientId, req.channel(), req.includePaymentLink(), UUID.fromString(jwt.getSubject())));
    }

    @com.nabd.hms.shared.Idempotent
    @PostMapping("/invoices/{id}/write-off")
    @PreAuthorize("hasAuthority('billing:approve')")
    InvoiceListItem writeOff(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody WriteOffRequest req) {
        return service.writeOff(id, req.amount(), req.reasonCode(), req.note(), UUID.fromString(jwt.getSubject()));
    }
}

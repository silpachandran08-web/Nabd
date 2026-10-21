package com.nabd.hms.platform.ticket;

import com.nabd.hms.platform.ticket.dto.RaiseTicketRequest;
import com.nabd.hms.platform.ticket.dto.TicketResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Tenant-side: any authenticated staff row (owner-linked or not, any role — SSA-08 has no gate here). */
@RestController
@RequestMapping("/v1/support/tickets")
public class TicketController {

    private final TicketService service;

    TicketController(TicketService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TicketResponse> raise(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RaiseTicketRequest req) {
        TicketResponse ticket = service.raiseFromStaff(tenantId(jwt), staffId(jwt), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ticket);
    }

    /** PLT-057: the form calls this while the staff member types, to warn before submitting. Stores nothing. */
    @PostMapping("/redaction-preview")
    public TicketService.RedactionPreview preview(@Valid @RequestBody RaiseTicketRequest req) {
        return service.preview(req);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}

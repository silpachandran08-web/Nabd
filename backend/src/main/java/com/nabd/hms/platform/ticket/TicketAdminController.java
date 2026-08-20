package com.nabd.hms.platform.ticket;

import com.nabd.hms.platform.ticket.dto.TicketResponse;
import com.nabd.hms.platform.ticket.dto.TransitionRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Gated on support_tickets:view — per NB-257's matrix, super_admin/implementation/support_engineer. */
@RestController
@RequestMapping("/v1/platform/support/tickets")
@PreAuthorize("hasAuthority('support_tickets:view')")
public class TicketAdminController {

    private final TicketService service;

    TicketAdminController(TicketService service) {
        this.service = service;
    }

    @GetMapping
    public List<TicketResponse> list() {
        return service.list();
    }

    @PostMapping("/{id}/transitions")
    public TicketResponse transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest req) {
        return service.transition(id, req.toStatus());
    }
}

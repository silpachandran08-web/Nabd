package com.nabd.hms.messaging;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** No send endpoint here on purpose (NB-191): messages go out only through WhatsAppMessageService,
 * which takes a template name and slot values — never message text. PLT-019: no tenantId parameter; the
 * signed-in staff member's tenant is bound when the service's transaction starts. */
@RestController
@RequestMapping("/v1/setup/whatsapp-templates")
class WhatsAppTemplateController {

    private final WhatsAppTemplateService service;

    WhatsAppTemplateController(WhatsAppTemplateService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('setup:view')")
    List<WhatsAppTemplateService.TemplateResponse> list() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('setup:edit')")
    ResponseEntity<WhatsAppTemplateService.TemplateResponse> create(@RequestBody WhatsAppTemplateService.TemplateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('setup:edit')")
    WhatsAppTemplateService.TemplateResponse resubmit(@PathVariable UUID id,
                                                      @RequestBody WhatsAppTemplateService.TemplateRequest req) {
        return service.resubmit(id, req);
    }
}

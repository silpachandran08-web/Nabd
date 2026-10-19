package com.nabd.hms.clinical.templates;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.nabd.hms.clinical.templates.NoteTemplateModels.CreateRequest;
import static com.nabd.hms.clinical.templates.NoteTemplateModels.TemplateResponse;
import static com.nabd.hms.clinical.templates.NoteTemplateModels.UpdateRequest;

/** DOC-027: the calling doctor's own note templates. Reads need clinical:view, writes clinical:edit (as the note itself). */
@RestController
@RequestMapping("/v1/clinical/note-templates")
public class NoteTemplateController {

    private final NoteTemplateService service;

    NoteTemplateController(NoteTemplateService service) {
        this.service = service;
    }

    @GetMapping
    @com.nabd.hms.clinical.ClinicalReadScope.NoPatientData
    @PreAuthorize("hasAuthority('clinical:view')")
    public List<TemplateResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(tenantId(jwt), staffId(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('clinical:edit')")
    public TemplateResponse create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateRequest req) {
        return service.create(tenantId(jwt), staffId(jwt), req);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public TemplateResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestBody UpdateRequest req) {
        return service.update(tenantId(jwt), staffId(jwt), id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('clinical:edit')")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.delete(tenantId(jwt), staffId(jwt), id);
    }

    private UUID tenantId(Jwt jwt) {
        return UUID.fromString(jwt.getClaimAsString("tenantId"));
    }

    private UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}

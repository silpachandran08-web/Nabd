package com.nabd.hms.clinical.templates;

import jakarta.validation.Valid;
import com.nabd.hms.common.CurrentStaff;
import com.nabd.hms.common.CurrentTenant;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
    public List<TemplateResponse> list(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId) {
        return service.list(tenantId, staffId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('clinical:edit')")
    public TemplateResponse create(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @Valid @RequestBody CreateRequest req) {
        return service.create(tenantId, staffId, req);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('clinical:edit')")
    public TemplateResponse update(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id, @Valid @RequestBody UpdateRequest req) {
        return service.update(tenantId, staffId, id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('clinical:edit')")
    public void delete(@CurrentTenant UUID tenantId, @CurrentStaff UUID staffId, @PathVariable UUID id) {
        service.delete(tenantId, staffId, id);
    }
}

package com.nabd.hms.clinical.templates;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.nabd.hms.clinical.templates.NoteTemplateModels.CreateRequest;
import static com.nabd.hms.clinical.templates.NoteTemplateModels.TemplateResponse;
import static com.nabd.hms.clinical.templates.NoteTemplateModels.TemplateRow;
import static com.nabd.hms.clinical.templates.NoteTemplateModels.UpdateRequest;

/** DOC-027: a doctor's own note templates (CRUD). Not audited: configuration the doctor typed, not a clinical record. */
@Service
public class NoteTemplateService {

    private static final TypeReference<List<String>> TAGS_TYPE = new TypeReference<>() { };

    private final NoteTemplateRepository repo;
    private final TenantContext tenantContext;
    private final ObjectMapper objectMapper;

    NoteTemplateService(NoteTemplateRepository repo, TenantContext tenantContext, ObjectMapper objectMapper) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<TemplateResponse> list(UUID tenantId, UUID doctorId) {
        tenantContext.set(tenantId);
        return repo.list(tenantId, doctorId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public TemplateResponse create(UUID tenantId, UUID doctorId, CreateRequest req) {
        tenantContext.set(tenantId);
        String name = NoteTemplateRules.name(req.name());
        String s = NoteTemplateRules.section("subjective", req.subjective());
        String o = NoteTemplateRules.section("objective", req.objective());
        String a = NoteTemplateRules.section("assessment", req.assessment());
        String p = NoteTemplateRules.section("plan", req.plan());
        List<String> tags = NoteTemplateRules.tags(req.tags());
        if (repo.count(tenantId, doctorId) >= NoteTemplateRules.MAX_TEMPLATES_PER_DOCTOR) {
            throw new ApiException(HttpStatus.CONFLICT, "note-template-limit", "Template limit reached",
                    "You can keep at most " + NoteTemplateRules.MAX_TEMPLATES_PER_DOCTOR + " templates. Delete one first.");
        }
        UUID id = repo.insert(tenantId, doctorId, name, s, o, a, p, toJson(tags), Boolean.TRUE.equals(req.favourite()));
        return repo.find(tenantId, doctorId, id).map(this::toResponse).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public TemplateResponse update(UUID tenantId, UUID doctorId, UUID id, UpdateRequest req) {
        tenantContext.set(tenantId);
        TemplateRow cur = repo.find(tenantId, doctorId, id).orElseThrow(ApiException::notFound);
        String name = req.name() == null ? cur.name() : NoteTemplateRules.name(req.name());
        String s = req.subjective() == null ? cur.subjective() : NoteTemplateRules.section("subjective", req.subjective());
        String o = req.objective() == null ? cur.objective() : NoteTemplateRules.section("objective", req.objective());
        String a = req.assessment() == null ? cur.assessment() : NoteTemplateRules.section("assessment", req.assessment());
        String p = req.plan() == null ? cur.plan() : NoteTemplateRules.section("plan", req.plan());
        String tagsJson = req.tags() == null ? cur.tagsJson() : toJson(NoteTemplateRules.tags(req.tags()));
        boolean fav = req.favourite() == null ? cur.favourite() : req.favourite();
        if (repo.update(tenantId, doctorId, id, name, s, o, a, p, tagsJson, fav) == 0) {
            throw ApiException.notFound();
        }
        return repo.find(tenantId, doctorId, id).map(this::toResponse).orElseThrow(ApiException::notFound);
    }

    @Transactional
    public void delete(UUID tenantId, UUID doctorId, UUID id) {
        tenantContext.set(tenantId);
        if (repo.delete(tenantId, doctorId, id) == 0) {
            throw ApiException.notFound();
        }
    }

    private TemplateResponse toResponse(TemplateRow r) {
        List<String> tags;
        try {
            tags = r.tagsJson() == null ? List.of() : objectMapper.readValue(r.tagsJson(), TAGS_TYPE);
        } catch (JacksonException e) {
            tags = List.of();
        }
        return new TemplateResponse(r.id(), r.name(), r.subjective(), r.objective(), r.assessment(), r.plan(), tags,
                r.favourite(), r.createdAt(), r.updatedAt());
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JacksonException e) {
            throw new IllegalStateException("could not serialise note template json", e);
        }
    }

}

package com.nabd.hms.clinical.templates;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** DOC-027: doctor-authored note templates. Content is only what the owning doctor typed; nothing is seeded. */
public final class NoteTemplateModels {

    private NoteTemplateModels() {
    }

    public record TemplateRow(UUID id, UUID doctorId, String name, String subjective, String objective,
                              String assessment, String plan, String tagsJson, boolean favourite,
                              Instant createdAt, Instant updatedAt) {
    }

    /** Create: name required; the four sections and tags are optional (default empty). */
    public record CreateRequest(String name, String subjective, String objective, String assessment, String plan,
                                List<String> tags, Boolean favourite) {
    }

    /** Partial update: a null field is left unchanged (use an empty string / list to clear). */
    public record UpdateRequest(String name, String subjective, String objective, String assessment, String plan,
                                List<String> tags, Boolean favourite) {
    }

    public record TemplateResponse(UUID id, String name, String subjective, String objective, String assessment,
                                   String plan, List<String> tags, boolean favourite, Instant createdAt,
                                   Instant updatedAt) {
    }
}

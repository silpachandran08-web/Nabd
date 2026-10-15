package com.nabd.hms.clinical.templates;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nabd.hms.clinical.templates.NoteTemplateModels.TemplateRow;

/** Every query is scoped to the tenant AND the owning doctor: a doctor never sees or touches another doctor's templates. */
@Repository
class NoteTemplateRepository {

    private static final String COLS = "id, doctor_id, name, subjective, objective, assessment, plan, tags::text AS tags_json, " +
            "favourite, created_at, updated_at";

    private final JdbcTemplate jdbc;

    NoteTemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<TemplateRow> list(UUID tenantId, UUID doctorId) {
        return jdbc.query("SELECT " + COLS + " FROM note_templates WHERE tenant_id = ? AND doctor_id = ? " +
                "ORDER BY favourite DESC, lower(name), id", mapper(), tenantId, doctorId);
    }

    Optional<TemplateRow> find(UUID tenantId, UUID doctorId, UUID id) {
        return jdbc.query("SELECT " + COLS + " FROM note_templates WHERE tenant_id = ? AND doctor_id = ? AND id = ?",
                mapper(), tenantId, doctorId, id).stream().findFirst();
    }

    int count(UUID tenantId, UUID doctorId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM note_templates WHERE tenant_id = ? AND doctor_id = ?",
                Integer.class, tenantId, doctorId);
        return n == null ? 0 : n;
    }

    UUID insert(UUID tenantId, UUID doctorId, String name, String subjective, String objective, String assessment,
                String plan, String tagsJson, boolean favourite) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO note_templates (id, tenant_id, doctor_id, name, subjective, objective, assessment, plan, " +
                        "tags, favourite) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?)",
                id, tenantId, doctorId, name, subjective, objective, assessment, plan, tagsJson, favourite);
        return id;
    }

    int update(UUID tenantId, UUID doctorId, UUID id, String name, String subjective, String objective,
               String assessment, String plan, String tagsJson, boolean favourite) {
        return jdbc.update("UPDATE note_templates SET name = ?, subjective = ?, objective = ?, assessment = ?, plan = ?, " +
                        "tags = ?::jsonb, favourite = ? WHERE tenant_id = ? AND doctor_id = ? AND id = ?",
                name, subjective, objective, assessment, plan, tagsJson, favourite, tenantId, doctorId, id);
    }

    int delete(UUID tenantId, UUID doctorId, UUID id) {
        return jdbc.update("DELETE FROM note_templates WHERE tenant_id = ? AND doctor_id = ? AND id = ?",
                tenantId, doctorId, id);
    }

    private RowMapper<TemplateRow> mapper() {
        return (rs, i) -> new TemplateRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("doctor_id")),
                rs.getString("name"), rs.getString("subjective"), rs.getString("objective"),
                rs.getString("assessment"), rs.getString("plan"), rs.getString("tags_json"),
                rs.getBoolean("favourite"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}

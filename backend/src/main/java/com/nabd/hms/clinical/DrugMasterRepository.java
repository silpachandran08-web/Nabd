package com.nabd.hms.clinical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** CLN-019: drug_master & co are regional reference tables (no tenant_id, no RLS); nabd_app may only SELECT them and
 * the importer changes them through the V2180 SECURITY DEFINER functions. */
@Repository
class DrugMasterRepository {

    record Drug(String id, String brand, String strength, String form, String route, List<String> schedule,
                String pregnancyCategory, String lactationNote, String status, String kb, String month) {
    }

    record Ingredient(String id, String name, java.math.BigDecimal amount, String unit) {
    }

    record Named(String id, String name) {
    }

    record Summary(String id, String brand, String strength, String form, List<String> generic, List<String> schedule) {
    }

    record ImportRow(UUID id, String region, String kind, boolean dryRun, String status, Integer version, String kb,
                     String dataMonth, String diffJson, String error) {
    }

    record Started(UUID importId, boolean created, String status) {
    }

    record Activated(String outcome, Integer version, Integer added, Integer changed, Integer retired) {
    }

    private final JdbcTemplate jdbc;

    DrugMasterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Drug> find(String region, String drugId) {
        return jdbc.query("SELECT m.id, m.brand, m.strength, m.form, m.route, m.schedule, m.pregnancy_category, m.lactation_note, m.status, " +
                        "i.kb, i.data_month FROM drug_master m LEFT JOIN drug_master_imports i ON i.id = m.import_id " +
                        "WHERE m.region = ? AND m.id = ?",
                (rs, n) -> new Drug(rs.getString("id"), rs.getString("brand"), rs.getString("strength"), rs.getString("form"),
                        rs.getString("route"), strings(rs.getArray("schedule")), rs.getString("pregnancy_category"),
                        rs.getString("lactation_note"), rs.getString("status"), rs.getString("kb"), rs.getString("data_month")),
                region, drugId).stream().findFirst();
    }

    List<Ingredient> ingredients(String drugId) {
        return jdbc.query("SELECT g.id, g.name, d.amount, d.unit FROM drug_ingredients d JOIN ingredients g ON g.id = d.ingredient_id " +
                        "WHERE d.drug_id = ? ORDER BY g.name",
                (rs, n) -> new Ingredient(rs.getString("id"), rs.getString("name"), rs.getBigDecimal("amount"), rs.getString("unit")), drugId);
    }

    List<Named> classes(String drugId) {
        return jdbc.query("SELECT DISTINCT c.id, c.name FROM drug_ingredients d JOIN ingredients g ON g.id = d.ingredient_id " +
                        "JOIN drug_classes c ON c.id = ANY (g.class_ids) WHERE d.drug_id = ? ORDER BY c.name",
                (rs, n) -> new Named(rs.getString("id"), rs.getString("name")), drugId);
    }

    /** Active products only (retired ones are hidden from new search), prefix match on every word. */
    List<Summary> search(String region, String tsQuery, int limit) {
        return jdbc.query("SELECT m.id, m.brand, m.strength, m.form, m.schedule, " +
                        "(SELECT COALESCE(array_agg(g.name ORDER BY g.name), '{}') FROM drug_ingredients d JOIN ingredients g ON g.id = d.ingredient_id WHERE d.drug_id = m.id) AS generic " +
                        "FROM drug_master m WHERE m.region = ? AND m.status = 'active' AND m.search_text @@ to_tsquery('simple', ?) " +
                        "ORDER BY ts_rank(m.search_text, to_tsquery('simple', ?)) DESC, m.brand, m.id LIMIT ?",
                (rs, n) -> new Summary(rs.getString("id"), rs.getString("brand"), rs.getString("strength"), rs.getString("form"),
                        strings(rs.getArray("generic")), strings(rs.getArray("schedule"))),
                region, tsQuery, tsQuery, limit);
    }

    // ── import side ──────────────────────────────────────────────────────

    Started start(String region, String kind, boolean dryRun, String checksum, String by) {
        return jdbc.queryForObject("SELECT * FROM drug_master_import_start(?, ?, ?, ?, ?)",
                (rs, n) -> new Started(UUID.fromString(rs.getString("import_id")), rs.getBoolean("created"), rs.getString("status")),
                region, kind, dryRun, checksum, by);
    }

    void stage(UUID importId, String rowsJson) {
        jdbc.queryForObject("SELECT drug_master_stage_batch(?, ?::jsonb)", Integer.class, importId, rowsJson);
    }

    String validate(UUID importId, String kb, String month, String classesJson) {
        return jdbc.queryForObject("SELECT drug_master_validate(?, ?, ?, ?::jsonb)::text", String.class, importId, kb, month, classesJson);
    }

    void fail(UUID importId, String error) {
        jdbc.queryForObject("SELECT drug_master_fail(?, ?)::text", String.class, importId, error);
    }

    Activated activate(UUID importId, String csoAckRef) {
        return jdbc.queryForObject("SELECT * FROM drug_master_activate(?, ?)",
                (rs, n) -> new Activated(rs.getString("outcome"), (Integer) rs.getObject("version"), (Integer) rs.getObject("added"),
                        (Integer) rs.getObject("changed"), (Integer) rs.getObject("retired")),
                importId, csoAckRef);
    }

    Optional<ImportRow> findImport(UUID id) {
        return jdbc.query("SELECT id, region, kind, dry_run, status, version, kb, data_month, diff::text AS diff, error " +
                        "FROM drug_master_imports WHERE id = ?",
                (rs, n) -> new ImportRow(UUID.fromString(rs.getString("id")), rs.getString("region"), rs.getString("kind"),
                        rs.getBoolean("dry_run"), rs.getString("status"), (Integer) rs.getObject("version"), rs.getString("kb"),
                        rs.getString("data_month"), rs.getString("diff"), rs.getString("error")), id).stream().findFirst();
    }

    private static List<String> strings(Array a) {
        try {
            return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}

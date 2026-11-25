package com.nabd.hms.clinical;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** CLN-032: the interaction knowledge base (V3760). Regional reference tables: nabd_app may only SELECT them and the
 * loader changes them through the SECURITY DEFINER functions. Every read names the region and the active version, and
 * asks only for the ingredients and classes in play: no cache to go stale after a version switch. */
@Repository
class DrugInteractionRepository {

    record Version(UUID id, String region, int kbVersion, String kb, String month, String duplicateIngredientSeverity, String status) {

        /** The source shown with every finding: "<KB> <month>". */
        String label() {
            return kb + " " + month;
        }
    }

    record Started(UUID importId, int kbVersion, boolean created, String status) {
    }

    record Activated(String outcome, String region, Integer kbVersion) {
    }

    private final JdbcTemplate jdbc;

    DrugInteractionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String VERSION_COLS = "id, region, kb_version, kb, data_month, duplicate_ingredient_severity, status";

    private static Version version(java.sql.ResultSet rs) throws SQLException {
        return new Version(UUID.fromString(rs.getString("id")), rs.getString("region"), rs.getInt("kb_version"), rs.getString("kb"),
                rs.getString("data_month"), rs.getString("duplicate_ingredient_severity"), rs.getString("status"));
    }

    /** Empty = no knowledge base is active for the region: the caller must not check and must not report "nothing found". */
    Optional<Version> activeVersion(String region) {
        return jdbc.query("SELECT " + VERSION_COLS + " FROM drug_interaction_versions WHERE region = ? AND status = 'active'",
                (rs, n) -> version(rs), region).stream().findFirst();
    }

    Optional<Version> findVersion(UUID id) {
        return jdbc.query("SELECT " + VERSION_COLS + " FROM drug_interaction_versions WHERE id = ?", (rs, n) -> version(rs), id)
                .stream().findFirst();
    }

    /** Ingredients (with the product's strength and the ingredient's classes) of each product, by drug id. */
    Map<String, List<RxInteractionRules.Ingredient>> ingredients(Collection<String> drugIds) {
        Map<String, List<RxInteractionRules.Ingredient>> out = new HashMap<>();
        if (drugIds.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT d.drug_id, g.id, g.name, d.amount, d.unit, g.class_ids FROM drug_ingredients d " +
                        "JOIN ingredients g ON g.id = d.ingredient_id WHERE d.drug_id = ANY (?) ORDER BY d.drug_id, g.name",
                rs -> {
                    out.computeIfAbsent(rs.getString("drug_id"), k -> new ArrayList<>()).add(new RxInteractionRules.Ingredient(
                            rs.getString("id"), rs.getString("name"), rs.getBigDecimal("amount"), rs.getString("unit"),
                            Set.of(strings(rs.getArray("class_ids")))));
                }, (Object) drugIds.toArray(String[]::new));
        return out;
    }

    /** The version's pairs among these ingredients, keyed by {@link RxInteractionRules.Kb#key}. */
    Map<String, RxInteractionRules.Pair> pairs(Version v, Collection<String> ingredientIds) {
        Map<String, RxInteractionRules.Pair> out = new LinkedHashMap<>();
        String[] ids = ingredientIds.toArray(String[]::new);
        jdbc.query("SELECT ingredient_a, ingredient_b, severity, mechanism, management, source_ref FROM drug_interactions " +
                        "WHERE region = ? AND kb_version = ? AND ingredient_a = ANY (?) AND ingredient_b = ANY (?)",
                rs -> {
                    out.put(RxInteractionRules.Kb.key(rs.getString("ingredient_a"), rs.getString("ingredient_b")),
                            new RxInteractionRules.Pair(rs.getString("severity"), rs.getString("mechanism"), rs.getString("management"),
                                    rs.getString("source_ref")));
                }, v.region(), v.kbVersion(), ids, ids);
        return out;
    }

    /** The version's non-additive classes among these classes, by class id. */
    Map<String, RxInteractionRules.DuplicateClass> duplicateClasses(Version v, Collection<String> classIds) {
        Map<String, RxInteractionRules.DuplicateClass> out = new LinkedHashMap<>();
        jdbc.query("SELECT d.class_id, c.name, d.severity, d.source_ref FROM drug_duplicate_classes d JOIN drug_classes c ON c.id = d.class_id " +
                        "WHERE d.region = ? AND d.kb_version = ? AND d.class_id = ANY (?)",
                rs -> {
                    out.put(rs.getString("class_id"), new RxInteractionRules.DuplicateClass(rs.getString("name"), rs.getString("severity"),
                            rs.getString("source_ref")));
                }, v.region(), v.kbVersion(), classIds.toArray(String[]::new));
        return out;
    }

    // ── loader side ──────────────────────────────────────────────────────

    Set<String> knownIngredients(Collection<String> ids) {
        return Set.copyOf(jdbc.queryForList("SELECT id FROM ingredients WHERE id = ANY (?)", String.class, (Object) ids.toArray(String[]::new)));
    }

    Set<String> knownClasses(Collection<String> ids) {
        return Set.copyOf(jdbc.queryForList("SELECT id FROM drug_classes WHERE id = ANY (?)", String.class, (Object) ids.toArray(String[]::new)));
    }

    Started start(String region, String kb, String month, String duplicateIngredientSeverity, String checksum, String by) {
        return jdbc.queryForObject("SELECT * FROM drug_interaction_import_start(?, ?, ?, ?, ?, ?)",
                (rs, n) -> new Started(UUID.fromString(rs.getString("import_id")), rs.getInt("kb_version"), rs.getBoolean("created"),
                        rs.getString("status")), region, kb, month, duplicateIngredientSeverity, checksum, by);
    }

    void batch(UUID importId, String rowsJson) {
        jdbc.queryForObject("SELECT drug_interaction_import_batch(?, ?::jsonb)", Integer.class, importId, rowsJson);
    }

    /** [pairs, classes] of the version, now validated. */
    int[] finish(UUID importId) {
        return jdbc.queryForObject("SELECT * FROM drug_interaction_import_finish(?)",
                (rs, n) -> new int[]{rs.getInt("pairs"), rs.getInt("classes")}, importId);
    }

    Activated activate(UUID importId, String csoAckRef) {
        return jdbc.queryForObject("SELECT * FROM drug_interaction_activate(?, ?)",
                (rs, n) -> new Activated(rs.getString("outcome"), rs.getString("region"), (Integer) rs.getObject("kb_version")),
                importId, csoAckRef);
    }

    private static String[] strings(Array a) throws SQLException {
        return a == null ? new String[0] : (String[]) a.getArray();
    }
}

package com.nabd.control;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLT-061 "no PHI in the Control Plane": the PLT-052 classification rule applied to this database. Every column of the
 * migrated schema carries a tier in classification.yaml (same format and tiers as Core's db/classification.yaml), none may
 * be `phi`, and no column may be named like clinical or patient data. An untagged new column fails here, so a column cannot
 * arrive without someone deciding what it holds.
 */
class NoPhiSchemaScanTest extends ControlPlaneTestBase {

    private static final Set<String> ALLOWED_TIERS = Set.of("pii", "financial", "operational");
    /** Names that would mean patient or clinical content. A match is a failure, not a tier to choose. */
    private static final Pattern PHI_NAME = Pattern.compile(
            "patient|diagnos|allerg|prescri|medic|drug|dose|dosage|symptom|clinical|vital|lab_|specimen|complaint|dob|birth|gender|aadhaar|abha|mrn|national_id|blood");

    @Test
    @SuppressWarnings("unchecked")
    void everyColumnIsClassifiedAndNoneIsPhi() throws IOException {
        Map<String, String> tagged = new TreeMap<>();
        Map<String, Object> doc = new Yaml().load(Files.readString(Path.of("classification.yaml")));
        ((Map<String, Map<String, Map<String, String>>>) doc.get("schemas")).forEach((schema, tables) ->
                tables.forEach((table, cols) -> cols.forEach((col, tier) -> tagged.put(schema + "." + table + "." + col, tier))));

        Set<String> actual = new TreeSet<>(superuser().queryForList("SELECT table_schema || '.' || table_name || '.' || column_name "
                + "FROM information_schema.columns WHERE table_schema IN ('public', 'master') AND table_name <> 'flyway_schema_history'", String.class));
        assertTrue(actual.size() > 100, "the scan saw the migrated schema (" + actual.size() + " columns)");

        Set<String> untagged = new TreeSet<>(actual);
        untagged.removeAll(tagged.keySet());
        Set<String> stale = new TreeSet<>(tagged.keySet());
        stale.removeAll(actual);
        assertEquals(Set.of(), untagged, "columns without a tier in services/control-plane/classification.yaml");
        assertEquals(Set.of(), stale, "tiers for columns that no longer exist");

        Set<String> bad = new TreeSet<>();
        tagged.forEach((col, tier) -> {
            if (!ALLOWED_TIERS.contains(tier)) {
                bad.add(col + " is tagged " + tier);
            }
            if (PHI_NAME.matcher(col.substring(col.indexOf('.') + 1)).find()) {
                bad.add(col + " is named like patient or clinical data");
            }
        });
        assertEquals(Set.of(), bad, "the Control Plane holds no PHI");
    }

    @Test
    void theScanCatchesAPhiColumn() {
        assertTrue(PHI_NAME.matcher("support_tickets.patient_name").find());
        assertTrue(PHI_NAME.matcher("tenant_directory.diagnosis_code").find());
        assertTrue(!PHI_NAME.matcher("operators.email").find() && !PHI_NAME.matcher("subscriptions.renewal_date").find());
    }

    @Test
    void theOperatorAuditLogStaysAppendOnlyAndTheServiceRoleHasNoDdl() {
        // a row to refuse on: a row trigger never fires on an empty table
        UUID op = UUID.randomUUID();
        superuser().update("INSERT INTO master.operators (id, name, email, role) VALUES (?, 'Audit Op', ?, 'super_admin')", op, "audit-" + op + "@nabd.test");
        superuser().update("INSERT INTO master.operator_audit_log (operator_id, action, row_count) VALUES (?, 'x', 0)", op);
        for (String sql : new String[] {"UPDATE master.operator_audit_log SET action = action", "DELETE FROM master.operator_audit_log",
                "TRUNCATE master.operator_audit_log", "CREATE TABLE master.x (id int)", "DROP TABLE tenant_directory"}) {
            assertTrue(org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.execute(sql), sql)
                    .getMostSpecificCause().getMessage().matches("(?s).*(permission denied|must be owner).*"), sql);
        }
        // the triggers copied from Core refuse the owner too
        for (String sql : new String[] {"DELETE FROM master.operator_audit_log", "TRUNCATE master.operator_audit_log"}) {
            org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () -> superuser().execute(sql), sql);
        }
    }
}

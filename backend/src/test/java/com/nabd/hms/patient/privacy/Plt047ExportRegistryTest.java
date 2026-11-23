package com.nabd.hms.patient.privacy;

import com.nabd.hms.patient.internal.privacy.DsrExportService;
import com.nabd.hms.shared.PatientDataExporter;
import com.nabd.hms.shared.PatientDataExporter.Table;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-047 completeness: the module export registry against the migrated schema. A table that holds a patient's id and is declared
 * by no module fails here, so a table added by a later story cannot silently stay out of a patient's export. To fix a failure: add
 * the table to its owning module's PatientDataExporter (Table.of / Table.child, or Table.excluded with the reason).
 */
class Plt047ExportRegistryTest extends ApiTestBase {

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    @Autowired DsrExportService exports;

    private List<Table> declared() {
        List<Table> all = new ArrayList<>();
        exports.registry().forEach(e -> all.addAll(e.tables()));
        return all;
    }

    @Test
    void everyTableWithAPatientColumnIsDeclaredByExactlyOneModule() {
        Set<String> withPatientColumn = new TreeSet<>(su.queryForList("""
                SELECT DISTINCT k.relname FROM pg_class k JOIN pg_attribute a ON a.attrelid = k.oid AND NOT a.attisdropped AND a.attnum > 0
                WHERE k.relnamespace = 'public'::regnamespace AND k.relkind IN ('r', 'p') AND NOT k.relispartition
                  AND (a.attname = 'patient_id' OR a.attname LIKE '%\\_patient\\_id')""", String.class));
        withPatientColumn.add("patients");
        List<String> names = declared().stream().map(Table::name).toList();
        assertThat(names).as("a table is declared once, by its owning module").doesNotHaveDuplicates();
        assertThat(new TreeSet<>(names)).as("tables with a patient column that no module's PatientDataExporter declares").containsAll(withPatientColumn);
        assertThat(exports.registry()).extracting(PatientDataExporter::module).doesNotHaveDuplicates()
                .contains("patient", "clinical", "billing", "queue", "messaging", "shared", "nursing", "packages", "pharmacy", "identity", "iam", "engagement", "organization");
    }

    @Test
    void everyDeclarationMatchesTheSchemaAndAnExclusionSaysWhy() {
        for (Table t : declared()) {
            if (!t.exported()) {
                assertThat(t.excludedReason()).as(t.name()).hasSizeGreaterThan(20);
                continue;
            }
            List<String> columns = su.queryForList("SELECT column_name::text FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?", String.class, t.name());
            assertThat(columns).as("table %s exists and is a clinic table", t.name()).contains("tenant_id");
            assertThat(columns).as("omitted columns of %s", t.name()).containsAll(t.omit());
            if (t.parent() == null) {
                assertThat(columns).as("patient columns of %s", t.name()).containsAll(t.patientColumns());
            } else {
                assertThat(columns).as("parent key of %s", t.name()).contains(t.parentKey());
            }
        }
        // only signed notes and prescriptions, only issued documents: the filters are part of the registry
        Map<String, String> filters = new java.util.HashMap<>();
        declared().stream().filter(t -> t.filter() != null).forEach(t -> filters.put(t.name(), t.filter()));
        assertThat(filters).containsEntry("clinical_notes", "t.status = 'signed'").containsEntry("note_amendments", "t.status = 'signed'")
                .containsEntry("prescriptions", "t.status = 'signed'").containsEntry("prescription_items", "t.status = 'signed'")
                .containsEntry("clinical_documents", "t.status = 'issued'");
    }

    @Test
    void theReaderRunsEveryDeclaredQueryAndReturnsEveryExportedTable() {
        SeededTenant t = seedTenant();
        // an id no clinic has: every query of the registry runs against the real schema and finds nothing
        Map<String, Map<String, List<Map<String, Object>>>> data = inTenantTx(t.id(), () -> exports.read(UUID.randomUUID()));
        Set<String> read = new TreeSet<>();
        data.values().forEach(tables -> {
            tables.values().forEach(rows -> assertThat(rows).isEmpty());
            read.addAll(tables.keySet());
        });
        assertThat(read).containsExactlyInAnyOrderElementsOf(declared().stream().filter(Table::exported).map(Table::name).toList());
    }
}

package com.nabd.hms.shared;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * PLT-047: the module export registry. Every module that keeps rows about a patient publishes one bean of this type naming its
 * own tables: what goes into a patient's data export, with which filter, and what stays out and why. The patient module's export
 * job reads exactly these declarations under the clinic's tenant context; Plt047ExportRegistryTest fails when a table with a
 * patient column is declared by no module, so a table added later cannot be forgotten. Lives in the shared module's base package
 * (exposed API) so every module can implement it without depending on the patient module.
 */
public interface PatientDataExporter {

    /** The owning module's name as in docs/architecture/table-ownership.yaml. */
    String module();

    List<Table> tables();

    /**
     * One table. Names are code constants (never request data). {@code patientColumns}: the row is the patient's when any of them
     * holds the patient's id. A child table ({@code parent} set) has no patient column of its own: its rows are exported when
     * {@code parentKey} points at an exported row of the parent. {@code filter} is an extra SQL predicate on alias {@code t},
     * the table that holds the patient column (for a child: its parent). {@code omit}: columns never exported (secrets, hashes, storage keys); binary
     * columns are always left out. {@code excludedReason} set = declared but not exported.
     */
    record Table(String name, List<String> patientColumns, String parent, String parentKey, String filter, Set<String> omit, String excludedReason) {

        public static Table of(String name, String... patientColumns) {
            return new Table(name, patientColumns.length == 0 ? List.of("patient_id") : List.of(patientColumns), null, null, null, Set.of(), null);
        }

        /** Rows of {@code name} whose {@code parentKey} is the id of an exported row of {@code parent}. */
        public static Table child(String name, String parentKey, Table parent) {
            if (parent.parent() != null || parent.excludedReason() != null) {
                throw new IllegalArgumentException("the parent of " + name + " must be an exported table with a patient column");
            }
            return new Table(name, parent.patientColumns(), parent.name(), parentKey, parent.filter(), Set.of(), null);
        }

        public static Table excluded(String name, String reason) {
            return new Table(name, List.of(), null, null, null, Set.of(), reason);
        }

        public Table where(String predicate) {
            return new Table(name, patientColumns, parent, parentKey, predicate, omit, excludedReason);
        }

        public Table omit(String... columns) {
            return new Table(name, patientColumns, parent, parentKey, filter, Set.copyOf(Arrays.asList(columns)), excludedReason);
        }

        public boolean exported() {
            return excludedReason == null;
        }
    }
}

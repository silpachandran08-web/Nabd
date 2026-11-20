package com.nabd.hms.common;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PLT-022 seam (in the kernel like CatalogueTemplates, because provisioning may not depend on organization): a clinic's configuration, copied into a new branch of the same owner. Sections: departments (with service points
 * and platform workflow selections), charges, policies, roles (every role except the built-in Owner), templates (the
 * languages of the WhatsApp starter catalogue) and hours (holidays, quiet hours, prayer and Friday windows).
 * Patients, staff accounts, per-doctor fees and hours, stock and anything clinical are never part of a bundle.
 */
public interface ConfigBundle {

    List<String> SECTIONS = List.of("departments", "charges", "policies", "roles", "templates", "hours");

    /** rows: table name to its rows as JSON text, read under the source clinic. */
    record Bundle(UUID sourceTenantId, List<String> sections, Map<String, List<String>> rows, List<String> templateLanguages) {
    }

    /** Reads the sections in a transaction of its own bound to the source clinic. Call it outside any transaction. */
    Bundle export(UUID sourceTenantId, List<String> sections);

    /**
     * Writes the bundle into a clinic that is still being provisioned, in the caller's transaction (bound to the target).
     * Departments and roles replace the provisioning placeholders; everything else is insert-if-absent, so applying the
     * same bundle again changes nothing. Returns rows per table.
     */
    Map<String, Integer> apply(UUID targetTenantId, Bundle bundle);

    /** Saga undo: removes everything {@link #apply} may have written, so the half-made clinic can be deleted. */
    void remove(UUID targetTenantId);
}

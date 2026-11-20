package com.nabd.hms.organization.branch;

import com.nabd.hms.common.CatalogueTemplates;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.common.ConfigBundle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PLT-022: {@link ConfigBundle}. Rows travel as JSON (to_jsonb / jsonb_populate_record), so a column added to one of these
 * tables later is copied without a change here; id, tenant_id and timestamps are always rewritten for the target.
 * Only the tables listed in TABLES are ever read: no patient, staff or clinical table is in the list.
 */
@Service
class ConfigBundleService implements ConfigBundle {

    /** extra: SQL appended to the row JSON (src.j = source row, src.tenant = target clinic). */
    private record Table(String section, String name, String exportSql, String extra) {
    }

    private static final String BY_DEPARTMENT = "|| jsonb_build_object('department_id', "
            + "(SELECT d.id FROM departments d WHERE d.tenant_id = src.tenant AND d.name = src.j->>'_department'))";

    // Order matters: service points and workflow selections point at the departments copied before them.
    private static final List<Table> TABLES = List.of(
            new Table("departments", "departments", "SELECT to_jsonb(t)::text FROM departments t WHERE t.tenant_id = ?", ""),
            new Table("departments", "service_points", "SELECT (to_jsonb(t) || jsonb_build_object('_department', d.name))::text "
                    + "FROM service_points t JOIN departments d ON d.id = t.department_id WHERE t.tenant_id = ?", BY_DEPARTMENT),
            // a clinic's own one-off workflow definition is not copied: the branch falls back to the platform default
            new Table("departments", "department_workflow_selection", "SELECT (to_jsonb(t) || jsonb_build_object('_department', d.name))::text "
                    + "FROM department_workflow_selection t JOIN departments d ON d.id = t.department_id "
                    + "JOIN workflow_definitions w ON w.id = t.workflow_definition_id WHERE t.tenant_id = ? AND w.tenant_id IS NULL", BY_DEPARTMENT),
            // stock stays with the source clinic: a stock-tracked item starts at 0 in the branch
            new Table("charges", "charge_catalogue", "SELECT to_jsonb(t)::text FROM charge_catalogue t WHERE t.tenant_id = ?",
                    "|| jsonb_build_object('stock_qty', CASE WHEN src.j->>'stock_qty' IS NOT NULL THEN 0 END)"),
            new Table("policies", "clinic_policies", "SELECT to_jsonb(t)::text FROM clinic_policies t WHERE t.tenant_id = ?", ""),
            new Table("roles", "roles", "SELECT to_jsonb(t)::text FROM roles t WHERE t.tenant_id = ? AND NOT t.built_in", ""),
            new Table("hours", "clinic_holidays", "SELECT to_jsonb(t)::text FROM clinic_holidays t WHERE t.tenant_id = ?", ""),
            new Table("hours", "clinic_quiet_hours", "SELECT to_jsonb(t)::text FROM clinic_quiet_hours t WHERE t.tenant_id = ?", ""),
            new Table("hours", "tenant_messaging_settings", "SELECT to_jsonb(t)::text FROM tenant_messaging_settings t WHERE t.tenant_id = ?", ""));

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final CatalogueTemplates templates;
    private final TransactionTemplate ownTx;

    ConfigBundleService(JdbcTemplate jdbc, TenantScope tenantScope, CatalogueTemplates templates, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.templates = templates;
        this.ownTx = new TransactionTemplate(txManager);
        this.ownTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Bundle export(UUID sourceTenantId, List<String> sections) {
        List<String> wanted = SECTIONS.stream().filter(sections::contains).toList();
        return tenantScope.run(sourceTenantId, () -> {
            Map<String, List<String>> rows = new LinkedHashMap<>();
            for (Table t : TABLES) {
                if (wanted.contains(t.section())) {
                    rows.put(t.name(), jdbc.queryForList(t.exportSql(), String.class, sourceTenantId));
                }
            }
            return new Bundle(sourceTenantId, wanted, rows, wanted.contains("templates") ? templates.languages(sourceTenantId) : List.of());
        });
    }

    @Override
    public Map<String, Integer> apply(UUID targetTenantId, Bundle bundle) {
        try {
            return tenantScope.run(targetTenantId, () -> write(targetTenantId, bundle));
        } catch (RuntimeException e) {
            // the caller's transaction rolls back; the failure is kept in a transaction of its own (class name only, no row data)
            try {
                ownTx.executeWithoutResult(s -> tenantScope.run(targetTenantId, () -> jdbc.update(
                        "INSERT INTO config_bundle_imports (tenant_id, source_tenant_id, sections, status, finished_at, error) VALUES (?,?,?,'failed',now(),?)",
                        targetTenantId, bundle.sourceTenantId(), bundle.sections().toArray(new String[0]), e.getClass().getSimpleName())));
            } catch (RuntimeException ignored) {
                // the import's own error is the one to report
            }
            throw e;
        }
    }

    private Map<String, Integer> write(UUID target, Bundle bundle) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (!bundle.rows().getOrDefault("departments", List.of()).isEmpty()) {
            // the provisioning placeholder ('General') goes: the branch gets the source's departments, default included
            jdbc.update("DELETE FROM department_workflow_selection WHERE tenant_id = ?", target);
            jdbc.update("DELETE FROM service_points WHERE tenant_id = ?", target);
            jdbc.update("DELETE FROM departments WHERE tenant_id = ?", target);
        }
        if (bundle.sections().contains("roles")) {
            jdbc.update("DELETE FROM roles WHERE tenant_id = ? AND NOT built_in", target); // seeded presets; the built-in Owner stays
        }
        for (Table t : TABLES) {
            List<String> rows = bundle.rows().get(t.name());
            if (rows == null) continue;
            String sql = "INSERT INTO " + t.name() + " SELECT r.* FROM (SELECT ?::jsonb AS j, ?::uuid AS tenant) src, "
                    + "jsonb_populate_record(NULL::" + t.name() + ", src.j || jsonb_build_object('id', gen_random_uuid(), 'tenant_id', src.tenant, "
                    + "'created_at', now(), 'updated_at', now()) " + t.extra() + ") r ON CONFLICT DO NOTHING";
            int n = 0;
            for (String row : rows) {
                n += jdbc.update(sql, row, target);
            }
            counts.put(t.name(), n);
        }
        if (!bundle.templateLanguages().isEmpty()) {
            counts.put("whatsapp_templates", templates.instantiateIfAbsent(target, bundle.templateLanguages()));
        }
        jdbc.update("INSERT INTO config_bundle_imports (tenant_id, source_tenant_id, sections, status, finished_at) VALUES (?,?,?,'done',now())",
                target, bundle.sourceTenantId(), bundle.sections().toArray(new String[0]));
        return counts;
    }

    @Override
    public void remove(UUID targetTenantId) {
        tenantScope.run(targetTenantId, () -> {
            for (int i = TABLES.size() - 1; i >= 0; i--) {
                String table = TABLES.get(i).name();
                // roles: the built-in Owner belongs to seed_masters' undo; departments: its undo deletes whatever is left
                jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?" + ("roles".equals(table) ? " AND NOT built_in" : ""), targetTenantId);
            }
            jdbc.update("DELETE FROM config_bundle_imports WHERE tenant_id = ?", targetTenantId);
        });
        templates.removeInstantiated(targetTenantId);
    }
}

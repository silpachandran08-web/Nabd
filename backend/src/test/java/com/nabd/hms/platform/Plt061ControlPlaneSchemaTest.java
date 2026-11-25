package com.nabd.hms.platform;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-061: the Control Plane's copy of the `master` schema (services/control-plane, migration V2) is GENERATED here from
 * Core's migrated catalog, so the tables the cut-over copies have the same columns, types, defaults, constraints, indexes
 * and triggers on both sides. This test fails when Core's master schema and that file differ.
 *
 * Writer (run alone, like the OpenAPI writer): mvn test -Dtest=Plt061ControlPlaneSchemaTest -Dcontrolplane.write=true
 * Regenerating V2 in place is right only until a Control Plane database exists somewhere; after that, add the change as a
 * new migration in services/control-plane and extend this generator to render the sum.
 *
 * Not carried, on purpose: foreign keys to Core tables (tenants, staff, ...: the Control Plane has no such rows, ids stay
 * plain uuids), tenant RLS policies (the Control Plane is cross-tenant by nature and no clinic role connects to it) and grants.
 */
class Plt061ControlPlaneSchemaTest extends ApiTestBase {

    static final Path FILE = Path.of("../services/control-plane/src/main/resources/db/migration/V2__master_tables.sql");

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    @Test
    void controlPlaneMasterTablesEqualCoresMasterSchema() throws IOException {
        String generated = generate();
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/control-plane-V2__master_tables.sql"), generated);
        if (Boolean.getBoolean("controlplane.write")) {
            Files.writeString(FILE, generated);
        }
        assertThat(Files.exists(FILE) ? Files.readString(FILE) : "")
                .as("Core's master schema and %s differ (Core's version is in backend/target/control-plane-V2__master_tables.sql). "
                        + "Regenerate: mvn test -Dtest=Plt061ControlPlaneSchemaTest -Dcontrolplane.write=true, then update "
                        + "services/control-plane/classification.yaml for new columns.", FILE)
                .isEqualTo(generated);
    }

    private String generate() {
        StringBuilder sql = new StringBuilder("""
                -- GENERATED from Core's migrated `master` schema by backend test Plt061ControlPlaneSchemaTest
                -- (mvn test -Dtest=Plt061ControlPlaneSchemaTest -Dcontrolplane.write=true). Do not edit by hand.
                -- Same names, types, defaults, checks, indexes and triggers as Core. Differences, all on purpose:
                --   foreign keys between master tables are DEFERRABLE (the copy job loads tables in any order);
                --   foreign keys to Core tables, tenant RLS and grants are not carried (listed at the end).
                """);
        List<String> other = su.queryForList("SELECT c.relkind::text || ' ' || c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = 'master' AND c.relkind NOT IN ('r', 'i', 'S') ORDER BY 1", String.class);
        assertThat(other).as("master holds a view, partitioned table or other object this generator does not render").isEmpty();

        List<Map<String, Object>> tables = su.queryForList("SELECT c.oid::bigint AS oid, c.relname AS name, c.relrowsecurity AS rls FROM pg_class c "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'master' AND c.relkind = 'r' ORDER BY c.relname");

        // functions the triggers call (public.set_updated_at, master.operator_audit_log_immutable)
        for (String fn : su.queryForList("SELECT pg_get_functiondef(f.oid) FROM pg_proc f WHERE f.oid IN (SELECT t.tgfoid FROM pg_trigger t "
                + "JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'master' AND NOT t.tgisinternal) "
                + "ORDER BY f.proname", String.class)) {
            sql.append('\n').append(fn.strip()).append(";\n");
        }
        // sequences that no column owns (identity columns bring their own)
        for (Map<String, Object> s : su.queryForList("SELECT c.relname AS name, format_type(s.seqtypid, NULL) AS type, s.seqstart, s.seqincrement, "
                + "s.seqmin, s.seqmax, s.seqcache, s.seqcycle FROM pg_sequence s JOIN pg_class c ON c.oid = s.seqrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'master' AND NOT EXISTS (SELECT 1 FROM pg_depend d "
                + "WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype IN ('a', 'i')) ORDER BY c.relname")) {
            sql.append("\nCREATE SEQUENCE master.").append(s.get("name")).append(" AS ").append(s.get("type"))
                    .append(" INCREMENT BY ").append(s.get("seqincrement")).append(" MINVALUE ").append(s.get("seqmin"))
                    .append(" MAXVALUE ").append(s.get("seqmax")).append(" START WITH ").append(s.get("seqstart"))
                    .append(" CACHE ").append(s.get("seqcache")).append(Boolean.TRUE.equals(s.get("seqcycle")) ? " CYCLE" : "").append(";\n");
        }

        StringBuilder later = new StringBuilder();
        StringBuilder notCarried = new StringBuilder();
        for (Map<String, Object> t : tables) {
            long oid = ((Number) t.get("oid")).longValue();
            String name = "master." + t.get("name");
            List<String> lines = new java.util.ArrayList<>();
            for (Map<String, Object> c : su.queryForList("SELECT quote_ident(a.attname) AS name, format_type(a.atttypid, a.atttypmod) AS type, a.attnotnull, "
                    + "a.attidentity::text AS identity, a.attgenerated::text AS generated, pg_get_expr(d.adbin, d.adrelid) AS def FROM pg_attribute a "
                    + "LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum "
                    + "WHERE a.attrelid = ?::oid AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum", oid)) {
                String line = "  " + c.get("name") + " " + c.get("type");
                if ("s".equals(c.get("generated"))) {
                    line += " GENERATED ALWAYS AS (" + c.get("def") + ") STORED";
                } else if (!"".equals(c.get("identity"))) {
                    line += " GENERATED " + ("a".equals(c.get("identity")) ? "ALWAYS" : "BY DEFAULT") + " AS IDENTITY";
                } else if (c.get("def") != null) {
                    line += " DEFAULT " + c.get("def");
                }
                lines.add(line + (Boolean.TRUE.equals(c.get("attnotnull")) ? " NOT NULL" : ""));
            }
            for (Map<String, Object> c : su.queryForList("SELECT quote_ident(con.conname) AS name, con.contype::text AS type, pg_get_constraintdef(con.oid) AS def, "
                    + "con.condeferrable, fn.nspname AS fschema FROM pg_constraint con LEFT JOIN pg_class fc ON fc.oid = con.confrelid "
                    + "LEFT JOIN pg_namespace fn ON fn.oid = fc.relnamespace WHERE con.conrelid = ?::oid "
                    + "ORDER BY CASE con.contype WHEN 'p' THEN 0 WHEN 'u' THEN 1 ELSE 2 END, con.conname", oid)) {
                if (!"f".equals(c.get("type"))) {
                    lines.add("  CONSTRAINT " + c.get("name") + " " + c.get("def"));
                } else if ("master".equals(c.get("fschema"))) {
                    later.append("ALTER TABLE ").append(name).append(" ADD CONSTRAINT ").append(c.get("name")).append(' ').append(c.get("def"))
                            .append(Boolean.TRUE.equals(c.get("condeferrable")) ? "" : " DEFERRABLE").append(";\n");
                } else {
                    notCarried.append("--   ").append(name).append(' ').append(c.get("name")).append(": ").append(c.get("def")).append('\n');
                }
            }
            sql.append("\nCREATE TABLE ").append(name).append(" (\n").append(String.join(",\n", lines)).append("\n);\n");
            // indexes that back no constraint
            for (String idx : su.queryForList("SELECT pg_get_indexdef(i.indexrelid) FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid "
                    + "WHERE i.indrelid = ?::oid AND NOT EXISTS (SELECT 1 FROM pg_constraint con WHERE con.conindid = i.indexrelid) ORDER BY ic.relname", String.class, oid)) {
                sql.append(idx).append(";\n");
            }
            for (String trg : su.queryForList("SELECT pg_get_triggerdef(t.oid) FROM pg_trigger t WHERE t.tgrelid = ?::oid AND NOT t.tgisinternal ORDER BY t.tgname",
                    String.class, oid)) {
                sql.append(trg).append(";\n");
            }
            if (Boolean.TRUE.equals(t.get("rls"))) {
                notCarried.append("--   ").append(name).append(": tenant row level security\n");
            }
        }
        return sql.append("\n-- foreign keys between master tables\n").append(later)
                .append("\n-- Not carried from Core:\n").append(notCarried).toString();
    }
}

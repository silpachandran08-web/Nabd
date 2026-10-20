package com.nabd.hms.architecture;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QA-5 (S12) PLT-033 AC1, against the real migrated database (not the migration regex the scan uses):
 * every table in public/identity/consent has exactly one owner in docs/architecture/table-ownership.yaml.
 */
class S12QaPlt033TableOwnershipTest extends ApiTestBase {

    @SuppressWarnings("unchecked")
    @Test
    void everyLiveTableInPublicIdentityConsentHasExactlyOneOwner() throws Exception {
        Path map = Files.exists(Path.of("..", "docs")) ? Path.of("..", "docs/architecture/table-ownership.yaml") : Path.of("docs/architecture/table-ownership.yaml");
        Map<String, List<String>> owners = new HashMap<>();
        try (Reader r = Files.newBufferedReader(map)) {
            Map<String, Object> mods = (Map<String, Object>) ((Map<String, Object>) new Yaml().load(r)).get("modules");
            mods.forEach((mod, spec) -> {
                Object owns = spec == null ? null : ((Map<String, Object>) spec).get("owns");
                if (owns != null) for (Object t : (List<Object>) owns) owners.computeIfAbsent(t.toString().toLowerCase(), k -> new ArrayList<>()).add(mod);
            });
        }
        // base and partitioned tables; partitions inherit the parent's owner; Flyway's own history table is infrastructure
        List<String> live = new JdbcTemplate(superuserDataSource()).queryForList("""
                SELECT CASE WHEN n.nspname = 'public' THEN c.relname ELSE n.nspname || '.' || c.relname END
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname IN ('public', 'identity', 'consent') AND c.relkind IN ('r', 'p') AND NOT c.relispartition
                  AND c.relname <> 'flyway_schema_history'
                ORDER BY 1""", String.class);
        assertTrue(live.size() > 50, "migrated schema present: " + live.size());
        List<String> bad = new ArrayList<>();
        for (String t : live) {
            int n = owners.getOrDefault(t, List.of()).size();
            if (n != 1) bad.add(t + " -> " + owners.getOrDefault(t, List.of()));
        }
        System.out.println("S12QA PLT-033 live tables checked: " + live.size());
        assertEquals(List.of(), bad, "tables without exactly one owner");
    }
}

package com.nabd.hms.shared.cache;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Independent QA (S12 PLT-052 AC2): the CI drift step (classification_check.py --db) against the REAL schema produced by
 * every migration of the merged tree (Flyway in ApiTestBase), then against the same schema plus a QA table with untagged
 * columns: it must pass, then fail and name exactly those columns.
 */
class S12QaPlt052ClassificationDbTest extends ApiTestBase {

    record Run(int rc, String out) {
    }

    private static Run check() throws Exception {
        String url, user;
        try (Connection c = superuserConnection()) {
            url = c.getMetaData().getURL();
            user = c.getMetaData().getUserName();
        }
        URI u = URI.create(url.substring("jdbc:".length()).replaceAll("\\?.*$", ""));
        String pg = "postgresql://" + user + "@" + u.getHost() + ":" + u.getPort() + u.getPath();
        ProcessBuilder pb = new ProcessBuilder("python3", "scripts/ci/classification_check.py", "--db", pg)
                .directory(new File("..")).redirectErrorStream(true);
        pb.environment().put("PGPASSWORD", "test"); // Testcontainers default for postgres:16-alpine
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(p.exitValue(), out);
    }

    /** Flyway-migrated DB, like ci/migration-lint/migrate-check.sh (flyway image) produces before the CI --db step. */
    @Test
    void ciDriftStepPassesOnAFlywayMigratedSchema() throws Exception {
        Run clean = check();
        System.out.println("S12QA PLT-052 --db on Flyway-migrated schema: " + clean.out().trim());
        assertThat(clean.rc()).as(clean.out()).isZero();
    }

    private static long problemsOutsideFlywayHistory(Run r) {
        return r.out().lines().filter(l -> l.contains(": column has no tier") || l.contains(": tagged in"))
                .filter(l -> !l.startsWith("public.flyway_schema_history.")).count();
    }

    @Test
    void migratedSchemaIsFullyTaggedThenAnUntaggedColumnFailsTheGate() throws Exception {
        Run clean = check();
        assertThat(problemsOutsideFlywayHistory(clean)).as(clean.out()).isZero();

        try (Connection c = superuserConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE public.qa_s12_probe (id uuid, qa_free_text text)");
            s.execute("ALTER TABLE public.patients ADD COLUMN qa_s12_untagged text");
        }
        try {
            Run dirty = check();
            System.out.println("S12QA PLT-052 --db with untagged probe: " + dirty.out().trim());
            assertThat(dirty.rc()).as(dirty.out()).isEqualTo(1);
            assertThat(dirty.out()).contains("public.qa_s12_probe.id: column has no tier", "public.qa_s12_probe.qa_free_text: column has no tier",
                    "public.patients.qa_s12_untagged: column has no tier");
            assertThat(problemsOutsideFlywayHistory(dirty)).isEqualTo(3);
        } finally {
            try (Connection c = superuserConnection(); Statement s = c.createStatement()) {
                s.execute("ALTER TABLE public.patients DROP COLUMN qa_s12_untagged");
                s.execute("DROP TABLE public.qa_s12_probe");
            }
        }
    }
}

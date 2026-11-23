package com.nabd.control;

import com.nabd.control.MasterCopy.Mode;
import com.nabd.control.MasterCopy.Result;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cut-over job against a stand-in for Core: a second database with the same master tables (V2 is generated from Core's
 * catalog, and Core's Plt061ControlPlaneSchemaTest fails when the two differ) plus the three Core tables the directory reads.
 * Each test gets its own source and target database.
 */
class MasterCopyTest extends ControlPlaneTestBase {

    private static final AtomicInteger N = new AtomicInteger();

    /** A fresh pair of databases; the source is seeded with rows of every awkward type the master tables use. */
    private final class Pair {
        final String sourceDb = "core_" + N.incrementAndGet(), targetDb = "cp_" + N.get();
        final JdbcTemplate source, target;
        final UUID operator = UUID.randomUUID(), plan = UUID.randomUUID(), brand = UUID.randomUUID(), owner = UUID.randomUUID();
        final UUID active = UUID.randomUUID(), suspended = UUID.randomUUID(), provisioning = UUID.randomUUID();

        Pair() {
            for (String db : List.of(sourceDb, targetDb)) {
                superuser().execute("CREATE DATABASE " + db);
                Flyway.configure().dataSource(url(db), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
            }
            source = new JdbcTemplate(new DriverManagerDataSource(url(sourceDb), POSTGRES.getUsername(), POSTGRES.getPassword()));
            target = new JdbcTemplate(new DriverManagerDataSource(url(targetDb), POSTGRES.getUsername(), POSTGRES.getPassword()));
            // the Core tables the directory is filled from (columns the job reads)
            source.execute("CREATE TABLE public.regions (code char(2) PRIMARY KEY, cell text NOT NULL)");
            source.execute("CREATE TABLE public.brands (id uuid PRIMARY KEY, owner_id uuid NOT NULL)");
            source.execute("CREATE TABLE public.tenants (id uuid PRIMARY KEY, brand_id uuid, region_code char(2), status text NOT NULL)");
            source.update("INSERT INTO public.regions VALUES ('IN', 'in')");
            source.update("INSERT INTO public.brands VALUES (?, ?)", brand, owner);
            source.update("INSERT INTO public.tenants VALUES (?, ?, 'IN', 'active'), (?, NULL, NULL, 'suspended'), (?, ?, 'IN', 'provisioning')",
                    active, brand, suspended, provisioning, brand);

            source.update("INSERT INTO master.operators (id, name, email, role, mfa_secret_enc) VALUES (?, 'Op One', 'Op1@nabd.test', 'super_admin', '\\x00ff10'::bytea)", operator);
            source.update("INSERT INTO master.sessions (operator_id, family_id, token_hash, ip_address, expires_at) "
                    + "VALUES (?, ?, 'hash-1', '10.1.2.3'::inet, '2031-03-04 05:06:07.123456+05:30'::timestamptz)", operator, UUID.randomUUID());
            for (int i = 0; i < 3; i++) {
                source.update("INSERT INTO master.login_attempts (operator_id, email, ip_address, succeeded) VALUES (?, 'op1@nabd.test', '2001:db8::1'::inet, ?)", operator, i > 0);
            }
            source.update("INSERT INTO master.plans (id, code, name, monthly_price_cents, currency, seat_limit) VALUES (?, 'copy_plan', 'Copy, \"plan\"\ttab', 99900, 'INR', 5)", plan);
            source.update("INSERT INTO master.subscriptions (tenant_id, plan_id, mrr_cents, currency, renewal_date) VALUES (?, ?, 99900, 'INR', DATE '2031-01-31')", active, plan);
            source.update("INSERT INTO master.provisioning_jobs (requested_by, tenant_slug, tenant_name, region, owner_email, owner_name, brand_name, copy_sections) "
                    + "VALUES (?, 'copy-clinic', 'Copy Clinic', 'IN', 'owner@nabd.test', 'Owner', 'Brand', ARRAY['a,b', 'c \"d\"', NULL])", operator);
            source.queryForList("SELECT nextval('master.platform_invoice_seq') FROM generate_series(1, 3)");
        }

        Connection source() throws SQLException {
            return DriverManager.getConnection(url(sourceDb), POSTGRES.getUsername(), POSTGRES.getPassword());
        }

        Connection target() throws SQLException {
            return DriverManager.getConnection(url(targetDb), POSTGRES.getUsername(), POSTGRES.getPassword());
        }

        List<Result> run(Mode mode) throws Exception {
            try (Connection s = source(); Connection t = target()) {
                return MasterCopy.run(mode, s, t);
            }
        }

        long targetRows() {
            return target.queryForList("SELECT 'master.' || relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                            + "WHERE n.nspname = 'master' AND c.relkind = 'r'", String.class).stream()
                    .mapToLong(t -> target.queryForObject("SELECT count(*) FROM " + t, Long.class)).sum()
                    + target.queryForObject("SELECT count(*) FROM tenant_directory", Long.class);
        }
    }

    private static Result of(List<Result> results, String object) {
        return results.stream().filter(r -> r.object().equals(object)).findFirst().orElseThrow(() -> new AssertionError(object + " not in " + results));
    }

    private static List<String> mismatches(List<Result> results) {
        return results.stream().filter(r -> !r.matches()).map(Result::object).sorted().toList();
    }

    @Test
    void copyMovesEveryMasterTableAndSequenceAndVerifiesRowCountsAndChecksums() throws Exception {
        Pair p = new Pair();
        List<Result> results = p.run(Mode.COPY);

        assertEquals(List.of(), mismatches(results), MasterCopy.describe(results));
        int tables = p.source.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'master' AND c.relkind = 'r'", Integer.class);
        int sequences = p.source.queryForObject("SELECT count(*) FROM pg_sequences WHERE schemaname = 'master'", Integer.class);
        assertTrue(tables >= 23, "every master table of Core is in the generated schema (" + tables + ")");
        assertEquals(tables + sequences + 1, results.size(), "one result per table, per sequence, and the directory");
        assertEquals(1, of(results, "master.operators").targetRows());
        assertEquals(3, of(results, "master.login_attempts").targetRows());
        assertFalse(of(results, "master.operators").targetChecksum().equals("0:0"));
        assertEquals("0:0", of(results, "master.trial_signups").targetChecksum(), "an empty table matches as empty");
        assertEquals(2, of(results, MasterCopy.DIRECTORY).targetRows(), "the tenant still provisioning is not in the directory");

        // values arrive exactly: case-preserving citext, bytea, inet, microseconds and zone, text with quotes and tabs, arrays with NULL
        Map<String, Object> op = p.target.queryForMap("SELECT email::text AS email, encode(mfa_secret_enc, 'hex') AS secret FROM master.operators WHERE id = ?", p.operator);
        assertEquals("Op1@nabd.test", op.get("email"));
        assertEquals("00ff10", op.get("secret"));
        assertEquals("2031-03-03 23:36:07.123456+00|10.1.2.3", p.target.queryForObject(
                "SELECT (expires_at AT TIME ZONE 'UTC')::text || '+00|' || host(ip_address) FROM master.sessions", String.class));
        assertEquals("Copy, \"plan\"\ttab", p.target.queryForObject("SELECT name FROM master.plans WHERE id = ?", String.class, p.plan));
        assertEquals("a,b|c \"d\"|<null>", p.target.queryForObject("SELECT array_to_string(copy_sections, '|', '<null>') FROM master.provisioning_jobs", String.class));

        // sequences continue where Core stopped: no reused login-attempt id, no reused invoice number
        assertEquals(4L, p.target.queryForObject("INSERT INTO master.login_attempts (email, ip_address, succeeded) VALUES ('x@nabd.test', '10.0.0.1', false) RETURNING id", Long.class));
        assertEquals(4L, p.target.queryForObject("SELECT nextval('master.platform_invoice_seq')", Long.class));

        Map<String, Object> dir = p.target.queryForMap("SELECT * FROM tenant_directory WHERE tenant_id = ?", p.active);
        assertEquals(p.brand, dir.get("brand_id"));
        assertEquals(p.owner, dir.get("owner_id"));
        assertEquals("IN", dir.get("region_code"));
        assertEquals("in", dir.get("cell"));
        assertEquals("active", dir.get("status"));
        assertNull(p.target.queryForMap("SELECT * FROM tenant_directory WHERE tenant_id = ?", p.suspended).get("cell"));
    }

    @Test
    void verifyNamesExactlyTheObjectsThatDiffer() throws Exception {
        Pair p = new Pair();
        p.run(Mode.COPY);
        assertEquals(List.of(), mismatches(p.run(Mode.VERIFY)));

        p.target.execute("ALTER TABLE master.plans DISABLE TRIGGER USER"); // the updated_at trigger would change a second column
        p.target.update("UPDATE master.plans SET seat_limit = seat_limit + 1"); // same count, one value changed
        List<Result> changed = p.run(Mode.VERIFY);
        assertEquals(List.of("master.plans"), mismatches(changed));
        assertEquals(of(changed, "master.plans").sourceRows(), of(changed, "master.plans").targetRows());
        assertNotEquals(of(changed, "master.plans").sourceChecksum(), of(changed, "master.plans").targetChecksum());
        p.target.update("UPDATE master.plans SET seat_limit = seat_limit - 1");
        p.target.execute("ALTER TABLE master.plans ENABLE TRIGGER USER");

        p.target.update("DELETE FROM master.login_attempts WHERE succeeded = false"); // a row missing in the target
        p.source.update("INSERT INTO master.operators (name, email, role) VALUES ('Late', 'late@nabd.test', 'billing')"); // a row written to Core after the copy
        p.source.queryForList("SELECT nextval('master.platform_invoice_seq')"); // an invoice number issued in Core after the copy
        p.target.update("UPDATE tenant_directory SET status = 'offboarded' WHERE tenant_id = ?", p.active);
        assertEquals(List.of("master.login_attempts", "master.operators", MasterCopy.DIRECTORY, "sequence master.platform_invoice_seq"),
                mismatches(p.run(Mode.VERIFY)));
    }

    @Test
    void copyRefusesANonEmptyTargetAndASchemaThatDiffers() throws Exception {
        Pair p = new Pair();
        p.run(Mode.COPY);
        long rows = p.targetRows();
        IllegalStateException again = assertThrows(IllegalStateException.class, () -> p.run(Mode.COPY));
        assertTrue(again.getMessage().contains("master.operators already has rows in the target"), again.getMessage());
        assertEquals(rows, p.targetRows());

        Pair q = new Pair();
        q.source.execute("ALTER TABLE master.plans ADD COLUMN added_in_core text");
        q.source.execute("CREATE TABLE master.new_in_core (id int)");
        for (Mode mode : List.of(Mode.COPY, Mode.VERIFY)) {
            IllegalStateException drift = assertThrows(IllegalStateException.class, () -> q.run(mode));
            assertTrue(drift.getMessage().contains("tables differ: source only has [master.new_in_core]"), drift.getMessage());
            assertTrue(drift.getMessage().contains("master.plans columns differ") && drift.getMessage().contains("added_in_core text"), drift.getMessage());
        }
        assertEquals(0, q.targetRows(), "nothing was copied");
    }

    @Test
    void refusesASourceRoleThatRowLevelSecurityWouldHideRowsFrom() throws Exception {
        Pair p = new Pair();
        // as in Core: forced tenant RLS on a master table, and a role without BYPASSRLS
        p.source.execute("ALTER TABLE master.subscriptions ENABLE ROW LEVEL SECURITY");
        p.source.execute("ALTER TABLE master.subscriptions FORCE ROW LEVEL SECURITY");
        p.source.execute("CREATE POLICY tenant_only ON master.subscriptions USING (tenant_id::text = current_setting('app.tenant_id', true))");
        String role = "reader_" + p.sourceDb;
        p.source.execute("CREATE ROLE " + role + " LOGIN PASSWORD 'reader_pw' NOBYPASSRLS");
        p.source.execute("GRANT USAGE ON SCHEMA master TO " + role);
        p.source.execute("GRANT SELECT ON ALL TABLES IN SCHEMA master, public TO " + role);
        p.source.execute("GRANT SELECT ON ALL SEQUENCES IN SCHEMA master TO " + role);
        try (Connection s = DriverManager.getConnection(url(p.sourceDb), role, "reader_pw"); Connection t = p.target()) {
            assertEquals(0, new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(s, true))
                    .queryForObject("SELECT count(*) FROM master.subscriptions", Integer.class), "this role sees none of the rows");
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> MasterCopy.run(Mode.COPY, s, t));
            assertTrue(e.getMessage().contains("row level security on master.subscriptions"), e.getMessage());
        }
        assertEquals(0, p.targetRows());
        assertEquals(List.of(), mismatches(p.run(Mode.COPY)), "a role that RLS does not apply to copies all of it");
        assertEquals(1, p.target.queryForObject("SELECT count(*) FROM master.subscriptions", Integer.class));
    }

    @Test
    void aCopyThatFailsLeavesTheTargetEmpty() throws Exception {
        Pair p = new Pair();
        // a row Core could not hold but a damaged source might: a session of an operator that does not exist
        p.source.execute("ALTER TABLE master.sessions DROP CONSTRAINT sessions_operator_id_fkey");
        p.source.update("INSERT INTO master.sessions (operator_id, family_id, token_hash, expires_at) VALUES (?, ?, 'orphan', now())", UUID.randomUUID(), UUID.randomUUID());
        assertThrows(SQLException.class, () -> p.run(Mode.COPY)); // the deferred foreign key fails at commit
        assertEquals(0, p.targetRows());
    }

    @Test
    void directoryModeFillsBrandAndOwnerKeepsAnEventFedStatusAndCopiesNoMasterTable() throws Exception {
        Pair p = new Pair();
        // the feed already saw this clinic suspended (newer than Core's snapshot can prove) but no event carried its brand
        p.target.update("INSERT INTO tenant_directory (tenant_id, status, status_version) VALUES (?, 'suspended', 99)", p.active);

        List<Result> results = p.run(Mode.DIRECTORY);
        assertEquals(1, results.size());
        assertFalse(results.get(0).matches(), "the status difference is reported");
        Map<String, Object> dir = p.target.queryForMap("SELECT * FROM tenant_directory WHERE tenant_id = ?", p.active);
        assertEquals(p.brand, dir.get("brand_id"));
        assertEquals(p.owner, dir.get("owner_id"));
        assertEquals("suspended", dir.get("status"));
        assertEquals(99L, dir.get("status_version"));
        assertEquals("suspended", p.target.queryForObject("SELECT status FROM tenant_directory WHERE tenant_id = ?", String.class, p.suspended));
        assertEquals(0, p.target.queryForObject("SELECT count(*) FROM tenant_directory WHERE tenant_id = ?", Integer.class, p.provisioning));
        assertEquals(0, p.target.queryForObject("SELECT count(*) FROM master.operators", Integer.class));

        // at the cut-over Core's status is the truth
        assertEquals(List.of(), mismatches(p.run(Mode.COPY)));
        assertEquals("active", p.target.queryForObject("SELECT status FROM tenant_directory WHERE tenant_id = ?", String.class, p.active));
        assertEquals(99L, p.target.queryForObject("SELECT status_version FROM tenant_directory WHERE tenant_id = ?", Long.class, p.active));
    }

    @Test
    void theJobReportsSuccessOnlyWhenEverythingMatchesAndNeverThrows() {
        Pair p = new Pair();
        MasterCopyJob job = new MasterCopyJob(null, new DriverManagerDataSource(url(p.targetDb), POSTGRES.getUsername(), POSTGRES.getPassword()), null);
        assertFalse(job.execute(new ControlPlaneProperties.Copy("verify", url(p.sourceDb), POSTGRES.getUsername(), POSTGRES.getPassword())), "nothing copied yet");
        assertFalse(job.execute(new ControlPlaneProperties.Copy("nonsense", url(p.sourceDb), POSTGRES.getUsername(), POSTGRES.getPassword())));
        assertFalse(job.execute(new ControlPlaneProperties.Copy("copy", url(p.sourceDb), "nobody", "wrong")));
        assertTrue(job.execute(new ControlPlaneProperties.Copy("copy", url(p.sourceDb), POSTGRES.getUsername(), POSTGRES.getPassword())));
        assertTrue(job.execute(new ControlPlaneProperties.Copy("verify", url(p.sourceDb), POSTGRES.getUsername(), POSTGRES.getPassword())));
        assertFalse(job.execute(new ControlPlaneProperties.Copy("copy", url(p.sourceDb), POSTGRES.getUsername(), POSTGRES.getPassword())), "a second copy is refused");
    }
}

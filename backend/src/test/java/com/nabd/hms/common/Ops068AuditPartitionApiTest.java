package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OPS-068: audit_log as monthly partitions (V3671) and the archive of its sealed months. The existing audit, tamper and chain
 * tests run unchanged against the partitioned parent; this class covers what is new. Months used here are years in the past.
 */
class Ops068AuditPartitionApiTest extends ApiTestBase {

    private static final String KEY = "test-only-" + UUID.randomUUID();

    @TempDir static Path archiveDir;
    @Autowired AuditService auditService;
    @Autowired TenantScope tenantScope;
    @Autowired MeterRegistry meters;

    private static JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private AuditChainSealer sealer() {
        return new AuditChainSealer(jdbc, tenantScope, meters, KEY, Clock.systemUTC());
    }

    private AuditArchiveService service() {
        return new AuditArchiveService(jdbc, txManager, tenantScope, sealer(), new LocalAuditArchiveStore(archiveDir.toString()),
                objectMapper, Clock.systemUTC(), false);
    }

    private static String partitionOf(LocalDate month) {
        return String.format("audit_log_y%dm%02d", month.getYear(), month.getMonthValue());
    }

    /** One audit row through the chain trigger as the API role; at = null means now. */
    private void write(UUID tenant, String action, String at) {
        inTenantTx(tenant, () -> jdbc.update("INSERT INTO audit_log (tenant_id, actor_type, actor_name, actor_role, ip_address, action, entity_type, before, after, created_at) "
                + "VALUES (?, 'system', 'Sys \"Q\"', 'System', '10.0.0.9'::inet, ?, 'ops068', '{\"a\": [1, {\"b\": null}]}', '{\"n\": \"ü\"}', COALESCE(?::timestamptz, now()))",
                tenant, action, at));
    }

    private Long verify(UUID t) {
        return inTenantTx(t, () -> jdbc.queryForObject("SELECT verify_audit_chain(?)", Long.class, t));
    }

    private void months(String from, int more) {
        jdbc.queryForObject("SELECT audit_log_ensure_partitions(?::date, ?)", Integer.class, from, more);
    }

    private static boolean exists(String table) {
        return !su().queryForObject("SELECT to_regclass(?) IS NULL", Boolean.class, table);
    }

    private static void dropMonth(String part) {
        JdbcTemplate su = su();
        if (su.queryForObject("SELECT count(*) FROM pg_inherits WHERE inhrelid = to_regclass(?)", Integer.class, part) > 0) {
            su.execute("ALTER TABLE audit_log_months DETACH PARTITION " + part);
        }
        su.execute("DROP TABLE IF EXISTS " + part);
    }

    @AfterAll
    static void leaveNoPastMonthBehind() { // an old, empty month would sit in every later plan of the shared database
        Ops068AuditArchiveApiTest.unapprove("audit_log");
        for (String part : su().queryForList("SELECT relname FROM pg_class WHERE relname ~ '^audit_log_y201[0-9]m[0-9]{2}$' AND relkind = 'r'", String.class)) {
            dropMonth(part);
        }
    }

    // ---- AC2: shape ----
    @Test
    void auditLogIsPartitionedByMonth_withTheParentsRulesOnEveryPartition() throws Exception {
        JdbcTemplate su = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(superuserConnection(), true));
        su.execute("SET TimeZone = 'UTC'"); // bounds are rendered in the session zone
        assertThat(su.queryForObject("SELECT relkind::text FROM pg_class WHERE oid = 'audit_log'::regclass", String.class)).isEqualTo("p");
        assertThat(su.queryForObject("SELECT pg_get_partkeydef('audit_log'::regclass)", String.class)).isEqualTo("RANGE (created_at)");
        assertThat(su.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'audit_log'::regclass AND contype = 'p'", String.class))
                .isEqualTo("PRIMARY KEY (id, created_at)");
        assertThat(su.queryForObject("SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'audit_log_months'::regclass", String.class)).isEqualTo("nabd_definer");

        List<String> bounds = su.queryForList("""
                SELECT c.relname || ' ' || pg_get_expr(c.relpartbound, c.oid) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'audit_log_months'::regclass ORDER BY 1""", String.class);
        LocalDate m = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        assertThat(jdbc.queryForObject("SELECT audit_log_ensure_partitions((now() AT TIME ZONE 'UTC')::date, 3)", Integer.class)).as("the daily call is idempotent").isZero();
        for (int i = 0; i <= 3; i++) {
            LocalDate from = m.plusMonths(i);
            assertThat(bounds).as("premade month").contains(partitionOf(from) + " FOR VALUES FROM ('" + from + " 00:00:00+00') TO ('" + from.plusMonths(1) + " 00:00:00+00')");
        }
        assertThat(bounds).contains("audit_log_default DEFAULT");

        // every table under the parent: forced RLS with the four policies, no grant for the API role, TRUNCATE guard
        List<String> parts = su.queryForList("SELECT c.relname FROM pg_class c WHERE c.relname = 'audit_log_months' OR c.oid IN "
                + "(SELECT inhrelid FROM pg_inherits WHERE inhparent = 'audit_log_months'::regclass) ORDER BY 1", String.class);
        assertThat(parts).hasSizeGreaterThanOrEqualTo(6);
        for (String p : parts) {
            assertThat(su.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = ?::regclass", Boolean.class, p)).as(p + " forced RLS").isTrue();
            assertThat(su.queryForObject("SELECT count(DISTINCT polcmd) FROM pg_policy WHERE polrelid = ?::regclass AND polcmd <> '*'", Integer.class, p)).as(p + " policies").isEqualTo(4);
            assertThat(su.queryForObject("SELECT has_any_column_privilege('nabd_app', ?, 'SELECT,INSERT,UPDATE') OR has_table_privilege('nabd_app', ?, 'DELETE')", Boolean.class, p, p))
                    .as(p + " is reached only through audit_log").isFalse();
            assertThat(su.queryForObject("SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = ?::regclass", String.class, p)).as(p + " owner").isEqualTo("nabd_definer");
            assertTruncateRefusedByTrigger("TRUNCATE " + p, "append-only");
        }
        assertTruncateRefusedByTrigger("TRUNCATE audit_log", "append-only");
        // the parent keeps the old table's grants; the kept copy of the old table is closed
        assertThat(su.queryForObject("SELECT has_table_privilege('nabd_app', 'audit_log', 'SELECT') AND has_table_privilege('nabd_app', 'audit_log', 'INSERT') "
                + "AND NOT has_table_privilege('nabd_app', 'audit_log', 'TRUNCATE')", Boolean.class)).isTrue();
        assertThat(su.queryForObject("SELECT has_any_column_privilege('nabd_app', 'audit_log_unpartitioned', 'SELECT,INSERT,UPDATE')", Boolean.class)).isFalse();
        // partition indexes carry the parent's index name (the OPS-001 plan pins look for it)
        String next = partitionOf(m.plusMonths(1)), suffix = next.substring("audit_log".length());
        assertThat(su.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = ?", String.class, next)).containsExactlyInAnyOrder(
                "audit_log_pkey" + suffix, "idx_audit_log_tenant_created" + suffix, "idx_audit_log_entity" + suffix,
                "idx_audit_log_tenant_id" + suffix, "idx_audit_log_platform" + suffix);
    }

    // ---- AC2: behaviour ----
    @Test
    void anAuditedWriteLandsInTheCurrentMonth_isChained_tenantScoped_andAppendOnlyOnTheParentAndOnThePartition() throws Exception {
        UUID t = seedTenant().id(), other = seedTenant().id();
        auditService.record(t, "staff", UUID.randomUUID(), "Dr. Test", "Doctor", "127.0.0.1", "ops068.write", "ops068", UUID.randomUUID(), Map.of("a", 1), Map.of("a", 2));
        auditService.record(t, "system", null, "System", "System", null, "ops068.write", "ops068", UUID.randomUUID(), null, Map.of("a", 3));
        String part = partitionOf(LocalDate.now(ZoneOffset.UTC));
        assertThat(su().queryForList("SELECT DISTINCT tableoid::regclass::text FROM audit_log WHERE tenant_id = ?", String.class, t)).containsExactly(part);
        assertThat(inTenantTx(t, () -> jdbc.queryForList("SELECT prev_hash FROM audit_log WHERE tenant_id = ? ORDER BY id", String.class, t)))
                .hasSize(2).first().isNull();
        assertThat(verify(t)).isNull();
        assertThat(inTenantTx(other, () -> jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class))).as("another tenant sees nothing").isZero();

        // the API role: no change through the parent, no access to the partition
        assertThatThrownBy(() -> inTenantTx(t, () -> jdbc.update("UPDATE audit_log SET action = 'x' WHERE tenant_id = ?", t))).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> inTenantTx(t, () -> jdbc.update("DELETE FROM audit_log WHERE tenant_id = ?", t))).rootCause().hasMessageContaining("append-only");
        for (String sql : List.of("SELECT count(*) FROM " + part, "UPDATE " + part + " SET action = 'x'", "DELETE FROM " + part,
                "INSERT INTO " + part + " (id, tenant_id, actor_type, actor_name, actor_role, action, entity_type, row_hash) VALUES (1, '" + t + "', 'system', 'x', 'x', 'x', 'x', 'x')",
                "ALTER TABLE audit_log_months DETACH PARTITION " + part, "DROP TABLE " + part, "SELECT count(*) FROM audit_log_months", "SELECT count(*) FROM audit_log_default")) {
            try (Connection c = dataSourceFor(APP_ROLE, APP_ROLE_PW).getConnection(); Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant_id', '" + t + "', false)");
                assertThatThrownBy(() -> s.execute(sql)).as(sql).isInstanceOf(SQLException.class);
            }
        }
        // the owner and a superuser are stopped on the partition itself too (the row triggers are cloned onto it)
        assertThatThrownBy(() -> su().update("UPDATE " + part + " SET action = 'x' WHERE tenant_id = ?", t)).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> su().update("DELETE FROM " + part + " WHERE tenant_id = ?", t)).rootCause().hasMessageContaining("append-only");

        // an edit that bypasses every trigger, made on the partition, is still caught by the chain
        long second = inTenantTx(t, () -> jdbc.queryForObject("SELECT max(id) FROM audit_log WHERE tenant_id = ?", Long.class, t));
        try (Connection su = superuserConnection(); Statement s = su.createStatement()) {
            s.execute("SET session_replication_role = replica");
            s.execute("UPDATE " + part + " SET after = '{\"a\": 99}' WHERE id = " + second);
        }
        assertThat(verify(t)).isEqualTo(second);
        assertThat(auditService.verify(t).intact()).isFalse();
    }

    // ---- AC2: a missing month never loses or refuses a write ----
    @Test
    void aRowOfAMonthWithoutAPartition_landsInTheDefaultPartition_andThatMonthIsThenNotCreatedOverIt() {
        UUID t = seedTenant().id();
        write(t, "ops068.orphan", "2017-01-15T10:00:00Z");
        write(t, "ops068.now", null);
        assertThat(su().queryForObject("SELECT tableoid::regclass::text FROM audit_log WHERE tenant_id = ? AND action = 'ops068.orphan'", String.class, t)).isEqualTo("audit_log_default");
        assertThat(verify(t)).isNull();
        months("2017-01-01", 0);
        assertThat(exists("audit_log_y2017m01")).as("skipped with a warning: the default partition holds rows of that month").isFalse();
    }

    // ---- AC3, AC4, AC5, AC6 on audit_log ----
    @Test
    void aSealedMonthIsExportedCheckedAgainstItsSealsAndDropped_theChainVerifiesAcrossTheBoundary_andTheMonthIsReadBackAndRestoredFromTheFile() throws Exception {
        months("2019-03-01", 1); // 2019-03 and 2019-04
        UUID a = seedTenant().id(), b = seedTenant().id();
        write(a, "ops068.a1", "2019-03-05T08:00:00.123456Z");
        write(b, "ops068.b1", "2019-03-06T08:00:00Z");
        write(a, "ops068.a2", "2019-03-20T08:00:00Z");
        write(b, "ops068.b2", "2019-03-21T08:00:00Z");
        write(a, "ops068.a3", "2019-03-31T23:59:59.999999Z");
        write(a, "ops068.a4", "2019-04-01T00:00:00Z");
        write(a, "ops068.a5", null);
        write(b, "ops068.b3", null);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log_y2019m03", Integer.class)).isEqualTo(5);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log_y2019m04", Integer.class)).isEqualTo(1);
        AuditArchiveService service = service();

        Ops068AuditArchiveApiTest.unapprove("audit_log");
        assertThatThrownBy(() -> service.archive("audit_log_y2019m03")).hasMessageContaining("inside the online retention");
        Ops068AuditArchiveApiTest.approve("audit_log");
        try {
            AuditArchiveService.Manifest m = service.archive("audit_log_y2019m03");
            assertThat(m.rows()).isEqualTo(5);
            assertThat(exists("audit_log_y2019m03")).as("detached and dropped").isFalse();
            assertThat(jdbc.queryForMap("SELECT * FROM archive_manifests WHERE partition_name = 'audit_log_y2019m03'"))
                    .containsEntry("table_name", "audit_log").containsEntry("row_count", 5L).containsEntry("sha256", m.sha256()).containsEntry("region", "IN");
            // the seals the archive was checked against: this month's head of each tenant, signed
            for (UUID t : List.of(a, b)) {
                Map<String, Object> seal = su().queryForMap("SELECT last_id, last_hash, signature FROM audit_chain_seals WHERE tenant_id = ? AND month = '2019-03-01'", t);
                assertThat(seal.get("signature")).isEqualTo(sealer().sign(t, LocalDate.of(2019, 3, 1), (Long) seal.get("last_id"), (String) seal.get("last_hash")));
            }

            // AC5: the first online row points at a row that is gone; verify starts from the previous month's seal
            assertThat(inTenantTx(a, () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ?", Integer.class, a))).isEqualTo(2);
            long firstOnline = inTenantTx(a, () -> jdbc.queryForObject("SELECT min(id) FROM audit_log WHERE tenant_id = ?", Long.class, a));
            assertThat(inTenantTx(a, () -> jdbc.queryForObject("SELECT prev_hash = (SELECT last_hash FROM audit_chain_seals WHERE tenant_id = ? AND month = '2019-03-01') "
                    + "FROM audit_log WHERE id = ?", Boolean.class, a, firstOnline))).as("the boundary link is the seal").isTrue();
            assertThat(verify(a)).isNull();
            assertThat(verify(b)).isNull();
            assertThat(auditService.verify(a).intact()).isTrue();
            write(a, "ops068.a6", null);
            assertThat(verify(a)).as("new rows keep chaining").isNull();
            // a seal that does not carry the boundary hash is no anchor
            String sealed = su().queryForObject("SELECT last_hash FROM audit_chain_seals WHERE tenant_id = ? AND month = '2019-03-01'", String.class, a);
            su().update("UPDATE audit_chain_seals SET last_hash = 'forged' WHERE tenant_id = ? AND month = '2019-03-01'", a);
            assertThat(verify(a)).isEqualTo(firstOnline);
            su().update("UPDATE audit_chain_seals SET last_hash = ? WHERE tenant_id = ? AND month = '2019-03-01'", sealed, a);

            // AC4: read back for one tenant from the file; the partition is not restored
            List<JsonNode> rows = new ArrayList<>();
            service.read("audit_log", a, Instant.parse("2019-03-01T00:00:00Z"), Instant.parse("2019-04-01T00:00:00Z"), rows::add);
            assertThat(rows).extracting(n -> n.path("action").asString()).containsExactly("ops068.a1", "ops068.a2", "ops068.a3");
            assertThat(rows.get(0).path("after").path("n").asString()).isEqualTo("ü");
            assertThat(rows.get(0).path("actor_name").asString()).isEqualTo("Sys \"Q\"");
            assertThat(exists("audit_log_y2019m03")).isFalse();

            // AC6: restore drill; every restored row still hashes to its own row_hash
            long t0 = System.nanoTime();
            assertThat(service.restoreDrill("audit_log_y2019m03")).isEqualTo(5);
            System.out.printf("OPS-068 restore drill of audit_log_y2019m03 (5 rows): %d ms%n", (System.nanoTime() - t0) / 1_000_000);
            assertThat(jdbc.queryForObject("SELECT restored_at IS NOT NULL FROM archive_manifests WHERE partition_name = 'audit_log_y2019m03'", Boolean.class)).isTrue();
            assertThat(exists("audit_log_y2019m03_restored")).isFalse();
            assertThat(jdbc.queryForObject("SELECT audit_log_ensure_partitions(date '2019-03-01', 0)", Integer.class)).as("an archived month is not created again").isZero();

            // AC3 as the job runs it: the next due month goes too, and the chain still verifies from the newer seal
            assertThat(service.archiveDue()).isEqualTo(1);
            assertThat(exists("audit_log_y2019m04")).isFalse();
            assertThat(verify(a)).isNull();
            assertThat(verify(b)).as("b had no row in 2019-04: its anchor is still the 2019-03 seal").isNull();
        } finally {
            Ops068AuditArchiveApiTest.unapprove("audit_log");
        }
    }

    // ---- AC3: the checks that stop a drop ----
    @Test
    void aMonthWithAForgedSeal_orAnEditedRow_isRefusedAndStaysOnline() throws Exception {
        months("2018-06-01", 0);
        UUID t = seedTenant().id();
        write(t, "ops068.c1", "2018-06-05T08:00:00Z");
        write(t, "ops068.c2", "2018-06-06T08:00:00Z");
        write(t, "ops068.c3", null);
        sealer().sealMonth(LocalDate.of(2018, 6, 1));
        Ops068AuditArchiveApiTest.approve("audit_log");
        try {
            String signature = su().queryForObject("SELECT signature FROM audit_chain_seals WHERE tenant_id = ? AND month = '2018-06-01'", String.class, t);
            su().update("UPDATE audit_chain_seals SET signature = 'forged' WHERE tenant_id = ? AND month = '2018-06-01'", t);
            assertThatThrownBy(() -> service().archive("audit_log_y2018m06")).hasMessageContaining("does not match its signed seal");
            su().update("UPDATE audit_chain_seals SET signature = ? WHERE tenant_id = ? AND month = '2018-06-01'", signature, t);

            long first = inTenantTx(t, () -> jdbc.queryForObject("SELECT min(id) FROM audit_log WHERE tenant_id = ?", Long.class, t));
            try (Connection su = superuserConnection(); Statement s = su.createStatement()) {
                s.execute("SET session_replication_role = replica");
                s.execute("UPDATE audit_log SET actor_name = 'TAMPERED' WHERE id = " + first);
            }
            assertThatThrownBy(() -> service().archive("audit_log_y2018m06")).hasMessageContaining("is broken at row " + first);
            assertThat(su().queryForObject("SELECT count(*) FROM audit_log_y2018m06", Integer.class)).as("still online").isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM archive_manifests WHERE partition_name = 'audit_log_y2018m06'", Integer.class)).isZero();
        } finally {
            Ops068AuditArchiveApiTest.unapprove("audit_log");
            dropMonth("audit_log_y2018m06");
        }
    }

    @Test
    void aMonthThatIsNotOneStretchOfTheChain_isRefused() {
        months("2018-09-01", 0);
        UUID t = seedTenant().id();
        write(t, "ops068.d1", null);                      // the lower id sits in the current month
        write(t, "ops068.d2", "2018-09-05T08:00:00Z");    // the higher id in the old one: dropping it would cut the chain in the middle
        Ops068AuditArchiveApiTest.approve("audit_log");
        try {
            assertThat(jdbc.queryForObject("SELECT contiguous FROM audit_archive_heads('audit_log_y2018m09') WHERE tenant_id = ?", Boolean.class, t)).isFalse();
            assertThatThrownBy(() -> service().archive("audit_log_y2018m09")).hasMessageContaining("a later month holds an earlier link");
            assertThat(exists("audit_log_y2018m09")).isTrue();
        } finally {
            Ops068AuditArchiveApiTest.unapprove("audit_log");
            dropMonth("audit_log_y2018m09");
        }
    }

    // ---- the platform chain (tenant_id NULL) is sealed by the drop itself; only the database part is covered here ----
    @Test
    void droppingAMonthSealsThePlatformChain_andThatSealAnchorsThePlatformChain() throws Exception {
        months("2018-11-01", 0);
        long id;
        try (Connection su = superuserConnection(); Statement s = su.createStatement()) {
            s.execute("SET session_replication_role = replica"); // not through the trigger: the live platform chain must not link to this row
            try (var rs = s.executeQuery("INSERT INTO audit_log (tenant_id, actor_type, actor_name, actor_role, action, entity_type, created_at, row_hash) "
                    + "VALUES (NULL, 'system', 'System', 'System', 'ops068.platform', 'ops068', '2018-11-10T08:00:00Z', 'ops068-platform-head') RETURNING id")) {
                rs.next();
                id = rs.getLong(1);
            }
        }
        Ops068AuditArchiveApiTest.approve("audit_log");
        try {
            jdbc.queryForObject("SELECT audit_archive_drop('audit_log_y2018m11', 1, ?, 'TEST DATA', repeat('0', 64), 'sig')::text", String.class, id);
            assertThat(su().queryForMap("SELECT last_id, last_hash, signature FROM audit_chain_seals WHERE tenant_id = '00000000-0000-0000-0000-000000000000' AND month = '2018-11-01'"))
                    .containsEntry("last_id", id).containsEntry("last_hash", "ops068-platform-head").containsEntry("signature", "sig");
            assertThat(jdbc.queryForObject("SELECT audit_chain_anchor_ok(NULL, ?, 'ops068-platform-head', now())", Boolean.class, id + 1)).isTrue();
            assertThat(jdbc.queryForObject("SELECT audit_chain_anchor_ok(NULL, ?, 'another-hash', now())", Boolean.class, id + 1)).isFalse();
        } finally {
            Ops068AuditArchiveApiTest.unapprove("audit_log");
            dropMonth("audit_log_y2018m11");
            su().update("DELETE FROM archive_manifests WHERE partition_name = 'audit_log_y2018m11'");
        }
    }
}

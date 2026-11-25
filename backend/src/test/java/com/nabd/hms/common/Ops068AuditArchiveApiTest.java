package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OPS-068: the archive of sealed months, on phi_read_log (monthly partitions since PLT-039), and the verify_audit_chain rule
 * that lets a chain start from the seal of an archived month. Months used here are years in the past and belong to this class.
 */
class Ops068AuditArchiveApiTest extends ApiTestBase {

    private static final String KEY = "test-only-" + UUID.randomUUID();

    @TempDir static Path archiveDir;
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

    /** An approved policy for every region (the shared database holds clinics of several), 13 months online. */
    static void approve(String dataClass) {
        su().update("""
                INSERT INTO data_retention_policies (region, data_class, online_months, archive_years, legal_basis, approved_by, approved_at)
                SELECT code, ?, 13, 7, 'TEST DATA', 'test', now() FROM regions
                ON CONFLICT (region, data_class) DO UPDATE SET online_months = 13, approved_by = 'test', approved_at = now()""", dataClass);
    }

    static void unapprove(String dataClass) {
        su().update("DELETE FROM data_retention_policies WHERE data_class = ?", dataClass);
    }

    /** One past month of phi_read_log, built the way phi_read_log_ensure_partitions builds a month (V1390). */
    private static String phiMonth(LocalDate month) {
        String part = String.format("phi_read_log_y%dm%02d", month.getYear(), month.getMonthValue());
        JdbcTemplate su = su();
        su.execute("CREATE TABLE " + part + " PARTITION OF phi_read_log FOR VALUES FROM ('" + month + " 00:00:00+00') TO ('" + month.plusMonths(1) + " 00:00:00+00')");
        su.execute("ALTER TABLE " + part + " OWNER TO nabd_definer");
        su.execute("SELECT enforce_tenant_rls('" + part + "')");
        su.execute("REVOKE ALL ON " + part + " FROM nabd_app");
        su.execute("CREATE TRIGGER trg_phi_read_log_no_truncate BEFORE TRUNCATE ON " + part + " FOR EACH STATEMENT EXECUTE FUNCTION phi_read_no_truncate()");
        return part;
    }

    private void read(UUID tenant, UUID patient, String at) {
        inTenantTx(tenant, () -> jdbc.update("""
                INSERT INTO phi_read_log (tenant_id, read_at, actor_type, actor_id, patient_id, resource_type, purpose_code, endpoint, ip)
                VALUES (?, ?::timestamptz, 'staff', gen_random_uuid(), ?, 'timeline', 'treatment', '/v1/ops068', '10.0.0.7'::inet)""", tenant, at, patient));
    }

    private static void dropMonth(String parent, String part) {
        JdbcTemplate su = su();
        if (su.queryForObject("SELECT count(*) FROM pg_inherits WHERE inhrelid = to_regclass(?)", Integer.class, part) > 0) {
            su.execute("ALTER TABLE " + parent + " DETACH PARTITION " + part);
        }
        su.execute("DROP TABLE IF EXISTS " + part);
    }

    @AfterAll
    static void leaveNoPastMonthBehind() {
        unapprove("phi_read_log");
        unapprove("audit_log");
        for (String part : su().queryForList("SELECT relname FROM pg_class WHERE relname ~ '^phi_read_log_y201[0-9]m[0-9]{2}$' AND relkind = 'r'", String.class)) {
            dropMonth("phi_read_log", part);
        }
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    // ---- AC3 (phi_read_log), AC4, AC6 ----
    @Test
    void aSealedPhiReadMonth_isExportedVerifiedAndDropped_readBackForOneTenantWithoutRestoring_andTheRestoreDrillMatchesTheManifest() throws Exception {
        String part = phiMonth(LocalDate.of(2019, 5, 1));
        UUID a = seedTenant().id(), b = seedTenant().id(), patient = UUID.randomUUID();
        read(a, patient, "2019-05-10T10:00:00.123456Z");
        read(a, UUID.randomUUID(), "2019-05-11T10:00:00Z");
        read(a, UUID.randomUUID(), "2019-05-31T23:59:59Z");
        read(b, UUID.randomUUID(), "2019-05-12T10:00:00Z");
        read(b, UUID.randomUUID(), "2019-05-13T10:00:00Z");
        jdbc.queryForObject("SELECT seal_phi_read_batches()", Integer.class);
        AuditArchiveService service = service();

        // no approved retention for the regions: nothing may leave, whoever asks
        unapprove("phi_read_log");
        assertThatThrownBy(() -> service.archive(part)).hasMessageContaining("inside the online retention");
        assertThat(service.archiveDue()).isZero();
        assertThat(su().queryForObject("SELECT count(*) FROM " + part, Integer.class)).isEqualTo(5);

        approve("phi_read_log");
        try {
            assertThat(service.archiveDue()).as("the job finds the month that is past the retention").isEqualTo(1);
            Map<String, Object> m = jdbc.queryForMap("SELECT object_key, sha256 FROM archive_manifests WHERE partition_name = ?", part);
            Path file = archiveDir.resolve((String) m.get("object_key"));
            assertThat(file).exists();
            assertThat(m.get("sha256")).isEqualTo(sha256(file));
            assertThat(su().queryForObject("SELECT to_regclass(?) IS NULL", Boolean.class, part)).as("partition dropped").isTrue();
            assertThat(su().queryForObject("SELECT count(*) FROM phi_read_log WHERE tenant_id IN (?, ?)", Integer.class, a, b)).isZero();
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM archive_manifests WHERE partition_name = ?", part);
            assertThat(row).containsEntry("table_name", "phi_read_log").containsEntry("row_count", 5L).containsEntry("region", "IN")
                    .containsEntry("object_key", "audit-archive/phi_read_log/" + part + ".jsonl.gz").containsEntry("restored_at", null);
            assertThat(row.get("month").toString()).isEqualTo("2019-05-01");

            // AC4: the tenant's rows come back from the file; the other tenant's never do; nothing is re-attached
            List<JsonNode> mine = new ArrayList<>();
            service.read("phi_read_log", a, Instant.parse("2019-05-01T00:00:00Z"), Instant.parse("2019-06-01T00:00:00Z"), mine::add);
            assertThat(mine).hasSize(3).allMatch(n -> n.path("tenant_id").asString().equals(a.toString()));
            assertThat(mine).filteredOn(n -> n.path("patient_id").asString().equals(patient.toString())).hasSize(1)
                    .allMatch(n -> n.path("read_at").asString().startsWith("2019-05-10T10:00:00.123456") && n.path("endpoint").asString().equals("/v1/ops068"));
            List<JsonNode> narrow = new ArrayList<>();
            service.read("phi_read_log", a, Instant.parse("2019-05-11T00:00:00Z"), Instant.parse("2019-05-12T00:00:00Z"), narrow::add);
            assertThat(narrow).hasSize(1);
            List<JsonNode> other = new ArrayList<>();
            service.read("phi_read_log", a, Instant.parse("2019-06-01T00:00:00Z"), Instant.parse("2019-07-01T00:00:00Z"), other::add);
            service.read("phi_read_log", UUID.randomUUID(), Instant.parse("2019-05-01T00:00:00Z"), Instant.parse("2019-06-01T00:00:00Z"), other::add);
            assertThat(other).isEmpty();
            assertThat(su().queryForObject("SELECT to_regclass(?) IS NULL", Boolean.class, part)).as("read back without restoring").isTrue();

            // AC6: the drill loads the file, checks it against the manifest and cleans up
            long t0 = System.nanoTime();
            assertThat(service.restoreDrill(part)).isEqualTo(5);
            System.out.printf("OPS-068 restore drill of %s (5 rows): %d ms%n", part, (System.nanoTime() - t0) / 1_000_000);
            assertThat(jdbc.queryForObject("SELECT restored_at IS NOT NULL FROM archive_manifests WHERE partition_name = ?", Boolean.class, part)).isTrue();
            assertThat(su().queryForObject("SELECT to_regclass(?) IS NULL", Boolean.class, part + "_restored")).isTrue();

            // a file that no longer hashes to its manifest is refused by the read path and by the drill
            byte[] bytes = Files.readAllBytes(file);
            bytes[bytes.length / 2] ^= 0x01;
            Files.write(file, bytes);
            assertThatThrownBy(() -> service.read("phi_read_log", a, Instant.parse("2019-05-01T00:00:00Z"), Instant.parse("2019-06-01T00:00:00Z"), n -> { }))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> service.restoreDrill(part)).hasMessageContaining("does not hash to its manifest");
            assertThat(su().queryForObject("SELECT to_regclass(?) IS NULL", Boolean.class, part + "_restored")).isTrue();
        } finally {
            unapprove("phi_read_log");
        }
    }

    // ---- AC3: the hash check stops the drop ----
    @Test
    void aPhiReadMonthChangedAfterSealing_orNotSealed_isRefusedAndStaysOnline() throws Exception {
        String part = phiMonth(LocalDate.of(2019, 7, 1));
        UUID t = seedTenant().id();
        read(t, UUID.randomUUID(), "2019-07-10T10:00:00Z");
        read(t, UUID.randomUUID(), "2019-07-11T10:00:00Z");
        approve("phi_read_log");
        try {
            assertThatThrownBy(() -> service().archive(part)).as("unsealed rows").hasMessageContaining("unsealed rows or batches");
            jdbc.queryForObject("SELECT seal_phi_read_batches()", Integer.class);
            try (Connection su = superuserConnection(); Statement s = su.createStatement()) {
                s.execute("SET session_replication_role = replica"); // bypass the append-only trigger, as a DBA could
                s.execute("UPDATE phi_read_log SET endpoint = '/v1/tampered' WHERE tenant_id = '" + t + "' AND read_at < '2019-07-11'");
            }
            assertThatThrownBy(() -> service().archive(part)).as("row changed after sealing").hasMessageContaining("unsealed rows or batches");
            assertThat(su().queryForObject("SELECT count(*) FROM phi_read_log WHERE tenant_id = ?", Integer.class, t)).as("still online").isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM archive_manifests WHERE partition_name = ?", Integer.class, part)).isZero();
        } finally {
            unapprove("phi_read_log");
            dropMonth("phi_read_log", part);
        }
    }

    // ---- AC3: "older than the region's online retention" is decided and enforced in the database, not only by the job ----
    @Test
    void aMonthIsDueOnlyPastTheApprovedRetentionOfEveryRegionWithRowsInIt_andNothingElseCanBeDropped() {
        String part = phiMonth(LocalDate.of(2019, 9, 1));
        LocalDate thisMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        String current = String.format("phi_read_log_y%dm%02d", thisMonth.getYear(), thisMonth.getMonthValue());
        try {
            UUID in = seedTenant().id(), ae = UUID.randomUUID();
            jdbc.update("INSERT INTO tenants (id, slug, name, region_code, status, timezone, data_gate, consent_default_deny, day_start_minute) "
                    + "VALUES (?, ?, 'OPS-068 AE', 'AE', 'active', 'UTC', 'live', false, 0)", ae, "t" + ae.toString().replace("-", "").substring(0, 12));
            read(in, UUID.randomUUID(), "2019-09-10T10:00:00Z");
            read(ae, UUID.randomUUID(), "2019-09-11T10:00:00Z");
            String policy = "INSERT INTO data_retention_policies (region, data_class, online_months, archive_years, legal_basis, approved_by, approved_at) VALUES (?, 'phi_read_log', 13, 7, 'TEST DATA', ?, ?::timestamptz)";
            unapprove("phi_read_log");
            assertThat(due(part)).as("no policy at all").isFalse();
            su().update(policy, "IN", "test", "2026-01-01T00:00:00Z");
            assertThat(due(part)).as("AE has rows in the month and no policy").isFalse();
            su().update(policy, "AE", null, null);
            assertThat(due(part)).as("AE's policy is not approved").isFalse();
            su().update("UPDATE data_retention_policies SET approved_by = 'test', approved_at = now(), online_months = 1200 WHERE region = 'AE' AND data_class = 'phi_read_log'");
            assertThat(due(part)).as("AE keeps 100 years online: the longest period wins").isFalse();
            su().update("UPDATE data_retention_policies SET online_months = 13 WHERE region = 'AE' AND data_class = 'phi_read_log'");
            assertThat(due(part)).isTrue();
            assertThat(due(current)).as("the current month").isFalse();

            for (String p : List.of(current, "phi_read_log_default", "patients")) {
                String expected = p.equals(current) ? "inside the online retention" : "not a monthly audit partition";
                assertThatThrownBy(() -> jdbc.queryForObject("SELECT audit_archive_drop(?, 0, NULL, 'k', repeat('0', 64), NULL)::text", String.class, p))
                        .as(p).rootCause().hasMessageContaining(expected);
                assertThatThrownBy(() -> service().archive(p)).as(p).isInstanceOf(RuntimeException.class);
            }
            // due, but not what was exported: the drop refuses
            assertThatThrownBy(() -> jdbc.queryForObject("SELECT audit_archive_drop(?, 1, 1, 'k', repeat('0', 64), NULL)::text", String.class, part))
                    .rootCause().hasMessageContaining("changed since it was exported");
            assertThat(su().queryForObject("SELECT count(*) FROM " + part, Integer.class)).isEqualTo(2);
        } finally {
            unapprove("phi_read_log");
            dropMonth("phi_read_log", part);
        }
    }

    private boolean due(String partition) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT audit_archive_due(?)", Boolean.class, partition));
    }

    @Test
    void theApiRoleReadsPoliciesAndManifestsButCannotWriteThem() {
        assertThat(jdbc.queryForList("SELECT * FROM data_retention_policies WHERE data_class = 'none'")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM archive_manifests WHERE table_name = 'none'")).isEmpty();
        for (String sql : List.of(
                "INSERT INTO data_retention_policies (region, data_class, online_months, archive_years, approved_by, approved_at) VALUES ('IN', 'audit_log', 1, 1, 'me', now())",
                "UPDATE data_retention_policies SET online_months = 1",
                "DELETE FROM data_retention_policies",
                "INSERT INTO archive_manifests (table_name, partition_name, month, region, object_key, row_count, sha256) VALUES ('audit_log', 'audit_log_y2001m01', '2001-01-01', 'IN', 'k', 0, repeat('0', 64))",
                "UPDATE archive_manifests SET sha256 = repeat('0', 64)",
                "DELETE FROM archive_manifests")) {
            assertThatThrownBy(() -> jdbc.update(sql)).as(sql).rootCause().hasMessageContaining("permission denied");
        }
    }

    // ---- AC5: the rule itself, without a partition: a chain may start from the seal of a month the manifest records as archived ----
    @Test
    void verifyAuditChain_startsFromTheSealOfAnArchivedMonth_andFromNothingElse() throws Exception {
        UUID t = seedTenant().id();
        for (String at : List.of("2018-03-05T08:00:00Z", "2018-03-20T08:00:00Z", "2018-03-31T23:59:59Z")) {
            inTenantTx(t, () -> jdbc.update("INSERT INTO audit_log (tenant_id, actor_type, actor_name, actor_role, action, entity_type, after, created_at) "
                    + "VALUES (?, 'system', 'System', 'System', 'ops068.old', 'ops068', '{\"k\": 1}', ?::timestamptz)", t, at));
        }
        inTenantTx(t, () -> jdbc.update("INSERT INTO audit_log (tenant_id, actor_type, actor_name, actor_role, action, entity_type) VALUES (?, 'system', 'System', 'System', 'ops068.new', 'ops068')", t));
        inTenantTx(t, () -> jdbc.update("INSERT INTO audit_log (tenant_id, actor_type, actor_name, actor_role, action, entity_type) VALUES (?, 'system', 'System', 'System', 'ops068.new', 'ops068')", t));
        assertThat(sealer().sealMonth(LocalDate.of(2018, 3, 1))).isGreaterThanOrEqualTo(1);
        assertThat(verify(t)).isNull();

        // the month leaves the table (what audit_archive_drop does by dropping the partition)
        try (Connection su = superuserConnection(); Statement s = su.createStatement()) {
            s.execute("SET session_replication_role = replica");
            s.execute("DELETE FROM audit_log WHERE tenant_id = '" + t + "' AND created_at < '2018-04-01'");
        }
        long firstOnline = inTenantTx(t, () -> jdbc.queryForObject("SELECT min(id) FROM audit_log WHERE tenant_id = ?", Long.class, t));
        assertThat(verify(t)).as("rows missing and no manifest: a break").isEqualTo(firstOnline);

        su().update("INSERT INTO archive_manifests (table_name, partition_name, month, region, object_key, row_count, sha256) "
                + "VALUES ('audit_log', 'audit_log_y2018m03', '2018-03-01', 'IN', 'TEST DATA', 3, repeat('0', 64))");
        try {
            assertThat(verify(t)).as("starts from the seal of the archived month").isNull();
            assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT prev_hash = (SELECT last_hash FROM audit_chain_seals WHERE tenant_id = ? AND month = '2018-03-01') "
                    + "FROM audit_log WHERE id = ?", Boolean.class, t, firstOnline))).isTrue();

            // a seal that does not carry that hash is no anchor
            String sealed = su().queryForObject("SELECT last_hash FROM audit_chain_seals WHERE tenant_id = ? AND month = '2018-03-01'", String.class, t);
            su().update("UPDATE audit_chain_seals SET last_hash = 'forged' WHERE tenant_id = ? AND month = '2018-03-01'", t);
            assertThat(verify(t)).isEqualTo(firstOnline);
            su().update("UPDATE audit_chain_seals SET last_hash = ? WHERE tenant_id = ? AND month = '2018-03-01'", sealed, t);
            assertThat(verify(t)).isNull();

            // and the rows that are online are still checked
            try (Connection su = superuserConnection(); Statement s = su.createStatement()) {
                s.execute("SET session_replication_role = replica");
                s.execute("UPDATE audit_log SET action = 'TAMPERED' WHERE id = " + firstOnline);
            }
            assertThat(verify(t)).isEqualTo(firstOnline);
        } finally {
            su().update("DELETE FROM archive_manifests WHERE partition_name = 'audit_log_y2018m03'");
        }
    }

    private Long verify(UUID t) {
        return inTenantTx(t, () -> jdbc.queryForObject("SELECT verify_audit_chain(?)", Long.class, t));
    }
}

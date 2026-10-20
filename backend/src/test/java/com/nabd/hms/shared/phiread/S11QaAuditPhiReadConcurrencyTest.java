package com.nabd.hms.shared.phiread;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ConcurrencyHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent QA (S11 PLT-038 + PLT-039): audit chain and PHI read log under concurrency, immutability, tamper, timezone. */
@SuppressWarnings({"rawtypes", "unchecked"})
class S11QaAuditPhiReadConcurrencyTest extends ApiTestBase {

    @Override
    protected SeededStaff seedStaff(SeededTenant tenant, UUID roleId, String email, String mobilePhone, boolean mfaEnabled,
                                    boolean emailVerified, boolean mobileVerified) {
        return com.nabd.hms.support.ClinicianFixture.licensed(
                super.seedStaff(tenant, roleId, email, mobilePhone, mfaEnabled, emailVerified, mobileVerified));
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    AuditService auditService;

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private String phone() {
        return "+9192" + (10_000_000 + SEQ.incrementAndGet());
    }

    private record Clinic(SeededTenant tenant, SeededStaff doc, String token, String patientId, String queueEntryId) {
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        SeededStaff doc = seedStaff(t, seedFullAccessRole(t.id()), "q3c" + UUID.randomUUID() + "@a.com", phone(), false);
        String tok = loginAndGetAccessToken(doc);
        String p = patient(tok, "Qachain Opal");
        ResponseEntity<Map> q = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(tok, Map.of(
                "patientId", p, "doctorId", doc.id().toString())), Map.class);
        assertThat(q.getStatusCode().is2xxSuccessful()).as(String.valueOf(q.getBody())).isTrue();
        String qe = (String) q.getBody().get("id");
        exchange("/v1/clinical/notes/" + qe, HttpMethod.PATCH, authedJsonBody(tok, Map.of("assessment", "a", "diagnosis", "QaSecretDx")), Map.class);
        return new Clinic(t, doc, tok, p, qe);
    }

    private String patient(String tok, String name) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(tok, Map.of(
                "name", name, "phone", phone(), "dob", "1990-01-01", "gender", "female")), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as(String.valueOf(r.getBody())).isTrue();
        return (String) r.getBody().get("id");
    }

    private Long verify(UUID t) {
        return inTenantTx(t, () -> jdbc.queryForObject("SELECT verify_audit_chain(?)", Long.class, t));
    }

    private int phiRows(UUID t) {
        return su.queryForObject("SELECT count(*) FROM phi_read_log WHERE tenant_id = ?", Integer.class, t);
    }

    private int settle(java.util.function.IntSupplier count, int expectedAtLeast) throws InterruptedException {
        int last = -1;
        for (int i = 0; i < 100; i++) {
            int now = count.getAsInt();
            if (now >= expectedAtLeast && now == last) {
                return now;
            }
            last = now;
            Thread.sleep(100);
        }
        return last;
    }

    private Object status(ResponseEntity<?> r) {
        return r.getStatusCode().value();
    }

    // ---- task (b): audit writes + read-log writes together, one tenant, under concurrency ----
    @Test
    void plt038_039_mixedAuditedWritesAndPhiReadsInOneTenant_noForkNoDeadlockNo5xx_oneReadRowPerRead() throws Exception {
        Clinic c = clinic();
        UUID t = c.tenant().id();
        int phiBefore = settle(() -> phiRows(t), 0);
        int auditBefore = inTenantTx(t, () -> jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class));
        List<Callable<Object>> tasks = new ArrayList<>();
        AtomicInteger n = new AtomicInteger();
        for (int i = 0; i < 15; i++) {
            tasks.add(() -> { inTenantTx(t, () -> auditService.record(t, "system", null, "System", "System", null, "qa3.direct", "qa3", UUID.randomUUID(), null, Map.of("i", "x"))); return 200; });
            tasks.add(() -> status(exchange("/v1/clinical/patients/" + c.patientId() + "/allergies", HttpMethod.POST,
                    authedJsonBody(c.token(), Map.of("substance", "QaSub" + n.incrementAndGet(), "severity", "mild")), Map.class)));
            tasks.add(() -> status(exchange("/v1/patients/" + c.patientId(), HttpMethod.PATCH,
                    authedJsonBody(c.token(), Map.of("address", "QA street " + n.incrementAndGet())), Map.class)));
            tasks.add(() -> status(exchange("/v1/clinical/patients/" + c.patientId() + "/timeline", HttpMethod.GET, authed(c.token()), String.class)));
            tasks.add(() -> status(exchange("/v1/clinical/notes/" + c.queueEntryId(), HttpMethod.GET, authed(c.token()), String.class)));
        }
        long t0 = System.nanoTime();
        List<Object> results = ConcurrencyHarness.race(tasks);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("QA3 PLT-038/039 mixed one-tenant 75 tasks took " + ms + " ms: " + results);
        assertThat(results).as("no 5xx (a deadlock or lock timeout surfaces as 500/503)").allMatch(s -> ((Integer) s) < 500);
        assertThat(results.stream().filter(s -> ((Integer) s) >= 400).count()).as(results.toString()).isZero();

        assertThat(verify(t)).as("chain intact after the mixed run").isNull();
        int auditAfter = inTenantTx(t, () -> jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class));
        assertThat(auditAfter - auditBefore).as("15 direct + 15 allergy + 15 patient edits at least").isGreaterThanOrEqualTo(45);
        Map<String, Object> head = su.queryForMap("SELECT last_id, last_hash FROM audit_chain_heads WHERE tenant_id = ?", t);
        Map<String, Object> last = su.queryForMap("SELECT id, row_hash FROM audit_log WHERE tenant_id = ? ORDER BY id DESC LIMIT 1", t);
        assertThat(head.get("last_id")).isEqualTo(last.get("id"));
        assertThat(head.get("last_hash")).isEqualTo(last.get("row_hash"));
        // 30 successful clinical GETs -> exactly 30 read rows
        assertThat(settle(() -> phiRows(t), phiBefore + 30) - phiBefore).isEqualTo(30);
    }

    @Test
    void plt038_ac2_tenTenantsWritingInParallel_eachChainIntact_headsIndependent() {
        List<UUID> tenants = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tenants.add(seedTenant().id());
        }
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (UUID t : tenants) {
            for (int i = 0; i < 6; i++) {
                tasks.add(() -> { inTenantTx(t, () -> auditService.record(t, "system", null, "System", "System", null, "qa3.tenants", "qa3", UUID.randomUUID(), null, Map.of("k", "v"))); return true; });
            }
        }
        ConcurrencyHarness.race(tasks);
        for (UUID t : tenants) {
            assertThat(verify(t)).as(t.toString()).isNull();
            assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'qa3.tenants'", Integer.class))).isEqualTo(6);
            assertThat(su.queryForObject("SELECT last_id FROM audit_chain_heads WHERE tenant_id = ?", Long.class, t))
                    .isEqualTo(su.queryForObject("SELECT max(id) FROM audit_log WHERE tenant_id = ?", Long.class, t));
        }
    }

    /** Lock independence: tenant A's open transaction (holding its head) never blocks tenant B. */
    @Test
    void plt038_ac2_openTransactionInTenantA_doesNotBlockTenantB_butBlocksA() throws Exception {
        UUID a = seedTenant().id(), b = seedTenant().id();
        String ins = "INSERT INTO audit_log (tenant_id, actor_type, actor_name, actor_role, action, entity_type) VALUES (?, 'system','S','S','qa3.lock','qa3')";
        try (Connection ca = appTx(a); Connection cb = appTx(b); Connection ca2 = appTx(a)) {
            exec(ca, ins, a);
            try (Statement s = cb.createStatement()) { s.execute("SET LOCAL lock_timeout = '1s'"); }
            exec(cb, ins, b);
            cb.commit();
            try (Statement s = ca2.createStatement()) { s.execute("SET LOCAL lock_timeout = '500ms'"); }
            assertThatThrownBy(() -> exec(ca2, ins, a)).hasMessageContaining("lock timeout");
            ca2.rollback();
            ca.commit();
        }
        assertThat(verify(a)).isNull();
        assertThat(verify(b)).isNull();
    }

    private static Connection appTx(UUID t) throws SQLException {
        Connection c = dataSourceFor(APP_ROLE, APP_ROLE_PW).getConnection();
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            s.execute("SELECT set_config('app.tenant_id', '" + t + "', true)");
        }
        return c;
    }

    private static void exec(Connection c, String sql, UUID t) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, t);
            ps.executeUpdate();
        }
    }

    // ---- PLT-038 AC3 ----
    @Test
    void plt038_ac3_rowsWrittenUnderDubaiHashTheSameAsUnderUtc_andVerifyInAnyZone() throws Exception {
        UUID t = seedTenant().id();
        try (Connection c = appTx(t)) {
            try (Statement s = c.createStatement()) { s.execute("SET LOCAL TimeZone = 'Asia/Dubai'"); }
            exec(c, "INSERT INTO audit_log (tenant_id, actor_type, actor_name, actor_role, action, entity_type, after) VALUES (?, 'system','S','S','qa3.tz','qa3','{\"b\":1,\"a\":2}'::jsonb)", t);
            c.commit();
        }
        Map<String, Object> row = su.queryForMap("SELECT id, hash_v, row_hash, audit_row_hash_v2(prev_hash, tenant_id, actor_type, actor_id, actor_name, actor_role, " +
                "ip_address, action, entity_type, entity_id, before, after, created_at) AS utc_hash FROM audit_log WHERE tenant_id = ? AND action = 'qa3.tz'", t);
        assertThat(((Number) row.get("hash_v")).intValue()).isEqualTo(2);
        assertThat(row.get("row_hash")).isEqualTo(row.get("utc_hash"));
        for (String zone : List.of("UTC", "Asia/Dubai", "Pacific/Kiritimati")) {
            try (Connection c = appTx(t); Statement s = c.createStatement()) {
                s.execute("SET LOCAL TimeZone = '" + zone + "'");
                try (ResultSet rs = s.executeQuery("SELECT verify_audit_chain('" + t + "')")) {
                    rs.next();
                    assertThat(rs.getObject(1)).as(zone).isNull();
                }
                c.rollback();
            }
        }
    }

    // ---- PLT-038 AC5 (detection part) ----
    @Test
    void plt038_ac5_superuserEditDeleteOrHeadTamper_isCaughtByVerify() throws Exception {
        UUID t = seedTenant().id();
        for (int i = 0; i < 4; i++) {
            inTenantTx(t, () -> auditService.record(t, "system", null, "System", "System", null, "qa3.tamper", "qa3", UUID.randomUUID(), null, Map.of("n", "1")));
        }
        List<Long> ids = su.queryForList("SELECT id FROM audit_log WHERE tenant_id = ? ORDER BY id", Long.class, t);
        assertThat(verify(t)).isNull();
        try (Connection c = superuserConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("SET session_replication_role = replica"); // bypass every trigger, as a DBA could
            s.executeUpdate("UPDATE audit_log SET after = '{\"n\":\"2\"}'::jsonb WHERE id = " + ids.get(1));
            c.commit();
        }
        assertThat(verify(t)).as("edited row").isEqualTo(ids.get(1));

        UUID t2 = seedTenant().id();
        for (int i = 0; i < 3; i++) {
            inTenantTx(t2, () -> auditService.record(t2, "system", null, "System", "System", null, "qa3.tamper", "qa3", UUID.randomUUID(), null, Map.of("n", "1")));
        }
        List<Long> ids2 = su.queryForList("SELECT id FROM audit_log WHERE tenant_id = ? ORDER BY id", Long.class, t2);
        try (Connection c = superuserConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("SET session_replication_role = replica");
            s.executeUpdate("DELETE FROM audit_log WHERE id = " + ids2.get(1));
            c.commit();
        }
        assertThat(verify(t2)).as("deleted middle row").isEqualTo(ids2.get(2));

        UUID t3 = seedTenant().id();
        inTenantTx(t3, () -> auditService.record(t3, "system", null, "System", "System", null, "qa3.tamper", "qa3", UUID.randomUUID(), null, Map.of("n", "1")));
        su.update("UPDATE audit_chain_heads SET last_hash = repeat('0', 64) WHERE tenant_id = ?", t3);
        inTenantTx(t3, () -> auditService.record(t3, "system", null, "System", "System", null, "qa3.tamper", "qa3", UUID.randomUUID(), null, Map.of("n", "2")));
        assertThat(verify(t3)).as("a forged head makes the next row break the chain").isNotNull();
    }

    // ---- PLT-038 AC6 ----
    @Test
    void plt038_ac6_appRoleCannotDisableTriggersOrRewriteAuditOrTouchHeads_ownersAsSpecified() throws Exception {
        UUID t = seedTenant().id();
        inTenantTx(t, () -> auditService.record(t, "system", null, "System", "System", null, "qa3.perm", "qa3", UUID.randomUUID(), null, Map.of()));
        List<String> allowed = new ArrayList<>();
        for (String sql : List.of("ALTER TABLE audit_log DISABLE TRIGGER ALL", "UPDATE audit_log SET action = 'x'", "DELETE FROM audit_log",
                "TRUNCATE audit_log", "SELECT * FROM audit_chain_heads", "UPDATE audit_chain_heads SET last_hash = ''",
                "DROP TRIGGER trg_audit_log_chain ON audit_log", "SET session_replication_role = replica")) {
            try (Connection c = appTx(t); Statement s = c.createStatement()) {
                try {
                    s.execute(sql);
                    allowed.add(sql);
                } catch (SQLException expected) {
                    // refused, as required
                }
                c.rollback();
            }
        }
        assertThat(allowed).as("statements nabd_app was allowed to run").isEmpty();
        assertThat(su.queryForObject("SELECT tableowner FROM pg_tables WHERE tablename = 'audit_log'", String.class)).isEqualTo("nabd_migrator");
        assertThat(su.queryForObject("SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE proname = 'audit_log_chain'", String.class)).isEqualTo("nabd_definer");
        assertThat(su.queryForObject("SELECT prosecdef FROM pg_proc WHERE proname = 'audit_log_chain'", Boolean.class)).isTrue();
    }

    // ---- PLT-038 AC4/AC5: nightly verify, checkpoints, status API ----
    @Test
    @Tag("known-defect-PLT-038")
    void plt038_ac4_checkpointTableAndStatusEndpointExist() {
        assertThat(su.queryForObject("SELECT to_regclass('public.audit_chain_checkpoints') IS NOT NULL", Boolean.class))
                .as("audit_chain_checkpoints table").isTrue();
        String op = platformLoginAndGetAccessToken(seedOperator("q3op" + UUID.randomUUID() + "@nabd.health", "super_admin", false));
        ResponseEntity<String> r = exchange("/v1/platform/audit-chain/status", HttpMethod.GET, authed(op), String.class);
        assertThat(r.getStatusCode()).as(r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("tenantsVerified").contains("failures");
    }

    // ---- PLT-039 AC1 + AC3 ----
    @Test
    void plt039_ac1_eachSuccessfulGetOneRowWithActorPatientResourcePurpose_ac3_failuresWriteNone_noClinicalContent() throws Exception {
        Clinic c = clinic();
        UUID t = c.tenant().id();
        int base = settle(() -> phiRows(t), 0);
        String[] paths = {"/v1/clinical/patients/" + c.patientId() + "/timeline", "/v1/clinical/notes/" + c.queueEntryId(),
                "/v1/clinical/patients/" + c.patientId() + "/conditions", "/v1/clinical/patients/" + c.patientId() + "/allergies"};
        String[] reqIds = new String[paths.length];
        for (int i = 0; i < paths.length; i++) {
            reqIds[i] = UUID.randomUUID().toString();
            HttpHeaders h = new HttpHeaders();
            h.setBearerAuth(c.token());
            h.set("X-Request-Id", reqIds[i]);
            assertThat(exchange(paths[i], HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode()).as(paths[i]).isEqualTo(HttpStatus.OK);
        }
        // failures: unknown visit 404, unknown patient 404, no permission 403, no token 401
        UUID noClinical = seedRole(t, "NoClin" + UUID.randomUUID().toString().substring(0, 4), false,
                new ModuleGrant("patients", true, false, false, false, false, false, false));
        String noClinTok = loginAndGetAccessToken(seedStaff(c.tenant(), noClinical, "q3n" + UUID.randomUUID() + "@a.com", phone(), false));
        assertThat(exchange("/v1/clinical/notes/" + UUID.randomUUID(), HttpMethod.GET, authed(c.token()), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/clinical/patients/" + UUID.randomUUID() + "/timeline", HttpMethod.GET, authed(c.token()), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(paths[0], HttpMethod.GET, authed(noClinTok), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(paths[0], HttpMethod.GET, HttpEntity.EMPTY, String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(settle(() -> phiRows(t), base + 4) - base).isEqualTo(4);
        for (int i = 0; i < paths.length; i++) {
            List<Map<String, Object>> rows = su.queryForList("SELECT * FROM phi_read_log WHERE tenant_id = ? AND request_id = ?", t, reqIds[i]);
            assertThat(rows).as(paths[i]).hasSize(1);
            Map<String, Object> r = rows.get(0);
            assertThat(r.get("actor_type")).isEqualTo("staff");
            assertThat(r.get("actor_id")).isEqualTo(c.doc().id());
            assertThat(r.get("patient_id").toString()).isEqualTo(c.patientId());
            assertThat(r.get("purpose_code")).isEqualTo("treatment");
            assertThat((String) r.get("resource_type")).isNotBlank();
            assertThat(String.valueOf(r)).as("identifiers only").doesNotContain("QaSecretDx").doesNotContain("Opal");
        }
    }

    // ---- PLT-039 AC4 ----
    @Test
    void plt039_ac4_appendOnly_forAppAndSuperuser_monthlyUtcPartitionExists() throws Exception {
        Clinic c = clinic();
        UUID t = c.tenant().id();
        exchange("/v1/clinical/patients/" + c.patientId() + "/timeline", HttpMethod.GET, authed(c.token()), String.class);
        settle(() -> phiRows(t), 1);
        for (String sql : List.of("UPDATE phi_read_log SET purpose_code = 'billing'", "DELETE FROM phi_read_log", "UPDATE phi_read_log SET batch_id = 1")) {
            try (Connection a = appTx(t); Statement s = a.createStatement()) {
                assertThatThrownBy(() -> s.executeUpdate(sql)).as("app: " + sql).isInstanceOf(SQLException.class);
                a.rollback();
            }
            if (sql.contains("batch_id")) {
                continue; // NULL -> batch id is the sealer's one permitted update; the app role is refused it above
            }
            try (Connection a = superuserConnection(); Statement s = a.createStatement()) {
                a.setAutoCommit(false);
                assertThatThrownBy(() -> s.executeUpdate(sql + " WHERE tenant_id = '" + t + "'")).as("superuser: " + sql)
                        .hasMessageContaining("append-only");
                a.rollback();
            }
        }
        assertTruncateRefusedByTrigger("TRUNCATE phi_read_log", "append-only");
        String part = "phi_read_log_y" + java.time.YearMonth.now(java.time.ZoneOffset.UTC).toString().replace("-", "m");
        assertThat(su.queryForObject("SELECT count(*) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid WHERE c.relname = ?", Integer.class, part))
                .as(part).isEqualTo(1);
        assertThat(su.queryForObject("SELECT count(*) FROM " + part + " WHERE tenant_id = ?", Integer.class, t)).isGreaterThanOrEqualTo(1);
    }

    /** PLT-039 AC4 under concurrency: four sealers racing each other and fresh inserts never fork the batch chain. */
    @Test
    void plt039_ac4_concurrentSealersAndInserts_batchChainLinear_everyOldRowSealedOnce() throws Exception {
        UUID t = seedTenant().id();
        String ins = "INSERT INTO phi_read_log (tenant_id, read_at, actor_type, patient_id, resource_type, purpose_code) VALUES (?, ?, 'system', ?, 'qa3', 'treatment')";
        Timestamp old = Timestamp.from(Instant.now().minusSeconds(180));
        for (int i = 0; i < 40; i++) {
            su.update(ins, t, old, UUID.randomUUID());
        }
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> { int s = 0; for (int k = 0; k < 5; k++) { s += su.queryForObject("SELECT seal_phi_read_batches()", Integer.class); su.update(ins, t, old, UUID.randomUUID()); } return s; });
        }
        ConcurrencyHarness.race(tasks);
        su.queryForObject("SELECT seal_phi_read_batches()", Integer.class); // final sweep
        List<Map<String, Object>> batches = su.queryForList("SELECT batch_id, batch_hash, prev_batch_hash, row_count FROM phi_read_batches WHERE tenant_id = ? ORDER BY batch_id", t);
        assertThat(batches).isNotEmpty();
        String prev = "genesis";
        long expectedId = 1;
        int sealed = 0;
        for (Map<String, Object> b : batches) {
            assertThat(((Number) b.get("batch_id")).longValue()).isEqualTo(expectedId++);
            assertThat(b.get("prev_batch_hash")).isEqualTo(prev);
            prev = (String) b.get("batch_hash");
            sealed += ((Number) b.get("row_count")).intValue();
        }
        assertThat(sealed).isEqualTo(60);
        assertThat(su.queryForObject("SELECT count(*) FROM phi_read_log WHERE tenant_id = ? AND batch_id IS NULL", Integer.class, t)).isZero();
    }

    // ---- PLT-039 AC5 ----
    @Test
    void plt039_ac5_sixtyFirstDistinctPatientInAnHour_alertsOwnerOnce_andEmitsEvent() throws Exception {
        SeededTenant t = seedTenant();
        UUID ownerRole = seedFullAccessRole(t.id());
        SeededStaff owner = seedStaff(t, ownerRole, "q3o" + UUID.randomUUID() + "@a.com", phone(), false);
        String ownerTok = loginAndGetAccessToken(owner);
        UUID recRole = seedRole(t.id(), "Reception" + UUID.randomUUID().toString().substring(0, 4), false,
                new ModuleGrant("patients", true, true, true, false, false, false, false), new ModuleGrant("queue", true, true, true, false, false, false, false));
        SeededStaff rec = super.seedStaff(t, recRole, "q3r" + UUID.randomUUID() + "@a.com", phone(), false, true, true);
        String recTok = loginAndGetAccessToken(rec);
        // 60 distinct patients already read by her within the hour (identifiers only, as the recorder writes them)
        Timestamp recent = Timestamp.from(Instant.now().minusSeconds(600));
        for (int i = 0; i < 60; i++) {
            su.update("INSERT INTO phi_read_log (tenant_id, read_at, actor_type, actor_id, patient_id, resource_type, purpose_code) VALUES (?,?,'staff',?,?,'patient','treatment')",
                    t.id(), recent, rec.id(), UUID.randomUUID());
        }
        String p = patient(ownerTok, "Qavolume Nia");
        assertThat(exchange("/v1/patients/" + p, HttpMethod.GET, authed(recTok), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        int alerts = settle(() -> su.queryForObject("SELECT count(*) FROM phi_read_volume_alerts WHERE tenant_id = ? AND actor_id = ?", Integer.class, t.id(), rec.id()), 1);
        assertThat(alerts).isEqualTo(1);
        assertThat(exchange("/v1/patients/" + p, HttpMethod.GET, authed(recTok), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        Thread.sleep(500);
        assertThat(su.queryForObject("SELECT count(*) FROM phi_read_volume_alerts WHERE tenant_id = ? AND actor_id = ?", Integer.class, t.id(), rec.id())).isEqualTo(1);
        assertThat(su.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'shared.phi_read.volume_alert.v1' AND payload->>'actorId' = ?",
                Integer.class, t.id(), rec.id().toString())).isEqualTo(1);
        ResponseEntity<String> ownerView = exchange("/v1/phi-read/alerts", HttpMethod.GET, authed(ownerTok), String.class);
        assertThat(ownerView.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownerView.getBody()).contains(rec.id().toString());
        assertThat(exchange("/v1/phi-read/alerts", HttpMethod.GET, authed(recTok), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // another tenant's owner does not see it
        SeededTenant o = seedTenant();
        String otherOwner = loginAndGetAccessToken(seedStaff(o, seedFullAccessRole(o.id()), "q3x" + UUID.randomUUID() + "@a.com", phone(), false));
        assertThat(exchange("/v1/phi-read/alerts", HttpMethod.GET, authed(otherOwner), String.class).getBody()).doesNotContain(rec.id().toString());
    }

    /** Boundary: exactly 60 distinct patients is not over the limit. */
    @Test
    void plt039_ac5_boundary_exactlySixtyDistinctPatients_noAlert() throws Exception {
        SeededTenant t = seedTenant();
        String ownerTok = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "q3o" + UUID.randomUUID() + "@a.com", phone(), false));
        UUID recRole = seedRole(t.id(), "Reception" + UUID.randomUUID().toString().substring(0, 4), false,
                new ModuleGrant("patients", true, true, true, false, false, false, false));
        SeededStaff rec = super.seedStaff(t, recRole, "q3r" + UUID.randomUUID() + "@a.com", phone(), false, true, true);
        Timestamp recent = Timestamp.from(Instant.now().minusSeconds(600));
        for (int i = 0; i < 59; i++) {
            su.update("INSERT INTO phi_read_log (tenant_id, read_at, actor_type, actor_id, patient_id, resource_type, purpose_code) VALUES (?,?,'staff',?,?,'patient','treatment')",
                    t.id(), recent, rec.id(), UUID.randomUUID());
        }
        String p = patient(ownerTok, "Qaboundary Mo");
        assertThat(exchange("/v1/patients/" + p, HttpMethod.GET, authed(loginAndGetAccessToken(rec)), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        settle(() -> su.queryForObject("SELECT count(*) FROM phi_read_log WHERE tenant_id = ? AND actor_id = ?", Integer.class, t.id(), rec.id()), 60);
        Thread.sleep(300);
        assertThat(su.queryForObject("SELECT count(*) FROM phi_read_volume_alerts WHERE tenant_id = ?", Integer.class, t.id())).isZero();
    }
}

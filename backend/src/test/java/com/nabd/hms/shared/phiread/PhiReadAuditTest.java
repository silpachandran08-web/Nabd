package com.nabd.hms.shared.phiread;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.support.ApiTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PLT-039 acceptance: one read row per patient per successful PHI GET, immutable monthly-partitioned log, chained sealing, volume alert. */
class PhiReadAuditTest extends ApiTestBase {

    /** /v1/clinical GETs that return no patient data (drug catalogue, the doctor's own Rx sets, note templates). */
    // CLN-004 safety-dictionary versions and CLN-005 signing-authority (canSign + reason code) carry no patient data
    static final Set<String> NO_PHI = Set.of("/v1/clinical/drugs/search", "/v1/clinical/rx-sets", "/v1/clinical/note-templates",
            "/v1/clinical/safety-dictionary", "/v1/clinical/prescriptions/{queueEntryId}/signing-authority",
            "/v1/clinical/drug-knowledge-base", "/v1/clinical/drugs", "/v1/clinical/drugs/{drugId}", "/v1/clinical/drugs/client-index"); // licence attribution (source + data month), no patient data

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired PhiReadRecorder recorder;
    @Autowired PhiReadSealJob sealJob;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired MeterRegistry meters;

    private record Hit(int status, String requestId) {
    }

    private Hit get(String token, String path) {
        String rid = UUID.randomUUID().toString();
        HttpHeaders h = new HttpHeaders();
        h.putAll(authed(token).getHeaders());
        h.set("X-Request-Id", rid);
        ResponseEntity<String> r = exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class);
        return new Hit(r.getStatusCode().value(), rid);
    }

    private void drain() throws InterruptedException {
        assertThat(recorder.awaitIdle(10_000)).as("phi read writer drained").isTrue();
    }

    private static JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private List<Map<String, Object>> rows(String requestId) {
        return su().queryForList("SELECT * FROM phi_read_log WHERE request_id = ? ORDER BY id", requestId);
    }

    private UUID seedPatient(UUID tenantId, int n) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,?,'1990-01-01','other') RETURNING id",
                UUID.class, tenantId, "PR " + n, String.format("+9190%08d", Math.abs((tenantId.hashCode() % 1000) * 100000 + n))));
    }

    private String visit(String token, UUID doctorId, String patientId) {
        exchange("/v1/doctors/" + doctorId + "/working-hours", HttpMethod.POST, authedJsonBody(token, Map.of(
                "dayOfWeek", LocalDate.now(ZoneOffset.UTC).getDayOfWeek().getValue() % 7,
                "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        ResponseEntity<Map> q = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class);
        return (String) q.getBody().get("id");
    }

    // ---- AC1: every /v1/clinical/** GET is covered, one row per request ------------------------------------------

    @Test
    void everyClinicalGetMappingIsAnnotatedOrExplicitlyNotPhi() {
        List<String> missing = new ArrayList<>();
        Set<String> seenExempt = new TreeSet<>();
        int annotated = 0;
        for (var e : mappings.getHandlerMethods().entrySet()) {
            if (!e.getKey().getMethodsCondition().getMethods().contains(RequestMethod.GET)) continue;
            for (String p : e.getKey().getPatternValues()) {
                if (!p.startsWith("/v1/clinical")) continue;
                if (NO_PHI.contains(p)) {
                    seenExempt.add(p);
                } else if (e.getValue().hasMethodAnnotation(PhiRead.class)) {
                    annotated++;
                } else {
                    missing.add(p);
                }
            }
        }
        assertThat(missing).as("clinical GETs without @PhiRead").isEmpty();
        assertThat(seenExempt).as("no stale exemptions").containsExactlyInAnyOrderElementsOf(NO_PHI);
        assertThat(annotated).isGreaterThanOrEqualTo(20);
    }

    @Test
    void eachSuccessfulClinicalGetWritesExactlyOneRowWithActorPatientResourceAndPurpose() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff doc = seedStaff(t, seedFullAccessRole(t.id()), "plt039-doc@a.com", "+919600039001", false);
        String token = loginAndGetAccessToken(doc);
        UUID sid = UUID.fromString((String) objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class).get("sid"));
        String patient = seedPatient(t.id(), 1).toString();
        String q = visit(token, doc.id(), patient);
        exchange("/v1/clinical/notes/" + q, HttpMethod.PATCH, authedJsonBody(token, Map.of("subjective", "x")), Map.class);

        Map<String, String> mustSucceed = new java.util.LinkedHashMap<>();
        mustSucceed.put("/v1/clinical/patients/" + patient + "/timeline", "timeline");
        mustSucceed.put("/v1/clinical/patients/" + patient + "/conditions", "conditions");
        mustSucceed.put("/v1/clinical/patients/" + patient + "/allergies", "allergies");
        mustSucceed.put("/v1/clinical/patients/" + patient + "/allergies/history", "allergy_history");
        mustSucceed.put("/v1/clinical/patients/" + patient + "/allergies/status", "allergy_status");
        mustSucceed.put("/v1/clinical/patients/" + patient + "/prescriptions", "prescription_history");
        mustSucceed.put("/v1/clinical/notes/" + q, "note");
        mustSucceed.put("/v1/clinical/notes/" + q + "/amendments", "note_amendments");      // empty list: patient via the queue entry
        mustSucceed.put("/v1/clinical/visits/" + q + "/documents", "visit_documents");      // empty list: patient via the queue entry
        mustSucceed.put("/v1/patients/" + patient, "patient");
        for (var e : mustSucceed.entrySet()) {
            Hit h = get(token, e.getKey());
            assertThat(h.status()).as(e.getKey()).isEqualTo(200);
            drain();
            List<Map<String, Object>> r = rows(h.requestId());
            assertThat(r).as(e.getKey() + " rows").hasSize(1);
            Map<String, Object> row = r.get(0);
            assertThat(row.get("tenant_id")).isEqualTo(t.id());
            assertThat(row.get("actor_type")).isEqualTo("staff");
            assertThat(row.get("actor_id")).isEqualTo(doc.id());
            assertThat(row.get("session_id")).isEqualTo(sid);
            assertThat(row.get("patient_id").toString()).isEqualTo(patient);
            assertThat(row.get("resource_type")).as(e.getKey()).isEqualTo(e.getValue());
            assertThat(row.get("purpose_code")).isEqualTo("treatment");
            assertThat((String) row.get("endpoint")).as("pattern, not the raw URL").doesNotContain(patient).startsWith("/v1/");
        }
        // the rest of the clinical GETs: one row when they succeed for this visit, none when they do not (e.g. no vitals yet)
        for (String p : List.of("/v1/clinical/vitals/" + q, "/v1/clinical/vitals/patient/" + patient + "/previous",
                "/v1/clinical/prescriptions/" + q, "/v1/clinical/prescriptions/" + q + "/print-header",
                "/v1/clinical/patients/" + patient + "/pregnancy-status", "/v1/clinical/patients/" + patient + "/pregnancy-status/history",
                "/v1/clinical/visits/" + q + "/documents/summary-prefill")) {
            Hit h = get(token, p);
            drain();
            assertThat(rows(h.requestId())).as(p + " -> " + h.status()).hasSize(h.status() / 100 == 2 ? 1 : 0);
        }
        // a list endpoint: one row per distinct patient it actually returned (none for an empty page)
        String rid = UUID.randomUUID().toString();
        HttpHeaders hh = new HttpHeaders();
        hh.putAll(authed(token).getHeaders());
        hh.set("X-Request-Id", rid);
        ResponseEntity<Map> enc = exchange("/v1/clinical/encounters", HttpMethod.GET, new HttpEntity<>(hh), Map.class);
        assertThat(enc.getStatusCode().value()).isEqualTo(200);
        long returned = ((List<Map<String, Object>>) enc.getBody().get("data")).stream().map(m -> m.get("patientId")).distinct().count();
        drain();
        assertThat(rows(rid)).hasSize((int) returned);
    }

    // ---- AC2: a 25-patient list writes 25 rows, capture stays under 15 ms p95 -------------------------------------

    @Test
    void listOf25PatientsWritesOneRowPerPatientWithinBudget() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff rec = seedStaff(t, seedFullAccessRole(t.id()), "plt039-list@a.com", "+919600039002", false);
        String token = loginAndGetAccessToken(rec);
        Set<UUID> seeded = new java.util.HashSet<>();
        for (int i = 0; i < 25; i++) seeded.add(seedPatient(t.id(), 100 + i));

        Hit h = get(token, "/v1/patients?limit=25");
        assertThat(h.status()).isEqualTo(200);
        drain();
        List<Map<String, Object>> r = rows(h.requestId());
        assertThat(r).hasSize(25);
        assertThat(r.stream().map(m -> (UUID) m.get("patient_id")).collect(java.util.stream.Collectors.toSet())).isEqualTo(seeded);
        assertThat(r).allSatisfy(m -> assertThat(m.get("resource_type")).isEqualTo("patient_list"));

        Timer timer = meters.find("nabd.phi_read.capture").timer();
        List<Long> perRequest = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            double before = timer.totalTime(TimeUnit.NANOSECONDS);
            assertThat(get(token, "/v1/patients?limit=25").status()).isEqualTo(200);
            perRequest.add((long) (timer.totalTime(TimeUnit.NANOSECONDS) - before));
        }
        drain();
        perRequest.sort(null);
        long p95 = perRequest.get((int) Math.ceil(perRequest.size() * 0.95) - 1);
        assertThat(p95).as("p95 added on the request thread for a 25-patient list (ns)").isLessThan(TimeUnit.MILLISECONDS.toNanos(15));
    }

    // ---- AC6: consult screen load (timeline + notes + vitals + Rx) adds < 20 ms p95 --------------------------------

    @Test
    void consultScreenLoadAddsUnderTwentyMillisecondsP95() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff doc = seedStaff(t, seedFullAccessRole(t.id()), "plt039-consult@a.com", "+919600039003", false);
        String token = loginAndGetAccessToken(doc);
        String patient = seedPatient(t.id(), 2).toString();
        String q = visit(token, doc.id(), patient);
        exchange("/v1/clinical/notes/" + q, HttpMethod.PATCH, authedJsonBody(token, Map.of("subjective", "x")), Map.class);
        Timer timer = meters.find("nabd.phi_read.capture").timer();
        List<Long> perLoad = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double before = timer.totalTime(TimeUnit.NANOSECONDS);
            get(token, "/v1/clinical/patients/" + patient + "/timeline");
            get(token, "/v1/clinical/notes/" + q);
            get(token, "/v1/clinical/vitals/patient/" + patient + "/previous");
            get(token, "/v1/clinical/patients/" + patient + "/prescriptions");
            perLoad.add((long) (timer.totalTime(TimeUnit.NANOSECONDS) - before));
        }
        drain();
        perLoad.sort(null);
        assertThat(perLoad.get(18)).as("p95 per consult load (ns)").isLessThan(TimeUnit.MILLISECONDS.toNanos(20));
        assertThat(su().queryForObject("SELECT count(*) FROM phi_read_log WHERE tenant_id = ? AND resource_type IN ('timeline','note')",
                Integer.class, t.id())).isEqualTo(40);
    }

    // ---- AC3: failed requests write nothing -------------------------------------------------------------------------

    @Test
    void failingRequestsWriteNoRow() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff doc = seedStaff(t, seedFullAccessRole(t.id()), "plt039-fail@a.com", "+919600039004", false);
        String token = loginAndGetAccessToken(doc);
        UUID patient = seedPatient(t.id(), 3);
        UUID noClinical = seedRole(t.id(), "FrontDeskOnly", false, fullGrant("patients"));
        String frontDesk = loginAndGetAccessToken(seedStaff(t, noClinical, "plt039-fd@a.com", "+919600039005", false));

        Hit notFound = get(token, "/v1/clinical/notes/" + UUID.randomUUID());
        Hit noPatient = get(token, "/v1/patients/" + UUID.randomUUID());
        Hit forbidden = get(frontDesk, "/v1/clinical/patients/" + patient + "/allergies");
        Hit unauth = get("not-a-token", "/v1/clinical/patients/" + patient + "/allergies");
        Hit badParam = get(token, "/v1/clinical/patients/" + patient + "/timeline?limit=0");
        assertThat(List.of(notFound.status(), noPatient.status(), forbidden.status(), unauth.status())).containsExactly(404, 404, 403, 401);
        assertThat(badParam.status() / 100).isEqualTo(4);
        Hit ok = get(token, "/v1/clinical/patients/" + patient + "/allergies"); // drained after it, so the failures had their chance
        drain();
        for (Hit h : List.of(notFound, noPatient, forbidden, unauth, badParam)) {
            assertThat(rows(h.requestId())).as(h.status() + " wrote nothing").isEmpty();
        }
        assertThat(rows(ok.requestId())).hasSize(1);
    }

    // ---- AC4: immutability, monthly partitions, chained sealing ----------------------------------------------------

    @Test
    void readLogRefusesUpdateDeleteTruncateForAppAndOwnerAlike() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff doc = seedStaff(t, seedFullAccessRole(t.id()), "plt039-imm@a.com", "+919600039006", false);
        String token = loginAndGetAccessToken(doc);
        UUID patient = seedPatient(t.id(), 4);
        get(token, "/v1/clinical/patients/" + patient + "/allergies");
        drain();
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM phi_read_log WHERE patient_id = ?", Integer.class, patient))).isEqualTo(1);

        assertThatThrownBy(() -> inTenantTx(t.id(), () -> jdbc.update("UPDATE phi_read_log SET purpose_code = 'billing'"))).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> inTenantTx(t.id(), () -> jdbc.update("DELETE FROM phi_read_log"))).rootCause().hasMessageContaining("permission denied");
        // even the table owner / a superuser is stopped by the triggers
        JdbcTemplate su = su();
        assertThatThrownBy(() -> su.update("UPDATE phi_read_log SET purpose_code = 'billing' WHERE tenant_id = ?", t.id())).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> su.update("UPDATE phi_read_log SET patient_id = gen_random_uuid(), batch_id = 99 WHERE tenant_id = ?", t.id())).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> su.update("DELETE FROM phi_read_log WHERE tenant_id = ?", t.id())).rootCause().hasMessageContaining("append-only");
        String part = su.queryForObject("SELECT tableoid::regclass::text FROM phi_read_log WHERE tenant_id = ?", String.class, t.id());
        assertThat(part).as("row lands in its month's partition").isEqualTo("phi_read_log_y" + LocalDate.now(ZoneOffset.UTC).getYear()
                + "m" + String.format("%02d", LocalDate.now(ZoneOffset.UTC).getMonthValue()));
        assertTruncateRefusedByTrigger("TRUNCATE phi_read_log", "append-only");
        assertTruncateRefusedByTrigger("TRUNCATE " + part, "append-only");
        assertTruncateRefusedByTrigger("TRUNCATE phi_read_batches", "append-only");
        assertThat(su.queryForObject("SELECT has_table_privilege('nabd_app', ?, 'SELECT')", Boolean.class, part)).as("partitions only via the parent").isFalse();
    }

    @Test
    void partitionsAreMonthlyAndCreatedAheadWithForcedRls() throws Exception {
        JdbcTemplate su = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(superuserConnection(), true));
        su.execute("SET TimeZone = 'UTC'"); // bounds are rendered in the session zone; assert them in UTC
        List<String> bounds = su.queryForList("""
                SELECT c.relname || ' ' || pg_get_expr(c.relpartbound, c.oid) FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'phi_read_log'::regclass ORDER BY 1""", String.class);
        LocalDate m = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        for (int i = 0; i <= 2; i++) {
            LocalDate from = m.plusMonths(i);
            String name = String.format("phi_read_log_y%dm%02d", from.getYear(), from.getMonthValue());
            assertThat(bounds).as(name).contains(name + " FOR VALUES FROM ('" + from + " 00:00:00+00') TO ('" + from.plusMonths(1) + " 00:00:00+00')");
        }
        assertThat(bounds).anyMatch(b -> b.startsWith("phi_read_log_default DEFAULT"));
        // the job's call is idempotent and extends the horizon; new partitions are forced, triggered, ungranted
        Integer created = jdbc.queryForObject("SELECT phi_read_log_ensure_partitions((now() AT TIME ZONE 'UTC')::date, 5)", Integer.class);
        assertThat(created).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT phi_read_log_ensure_partitions((now() AT TIME ZONE 'UTC')::date, 5)", Integer.class)).isZero();
        LocalDate far = m.plusMonths(5);
        String farName = String.format("phi_read_log_y%dm%02d", far.getYear(), far.getMonthValue());
        assertThat(su.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname = ?", Boolean.class, farName)).isTrue();
        assertThat(su.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgrelid = ?::regclass AND NOT tgisinternal", Integer.class, farName))
                .as("TRUNCATE guard + cloned row trigger").isGreaterThanOrEqualTo(2);
    }

    private void backdatedRows(UUID tenantId, int n) {
        su().update("""
                INSERT INTO phi_read_log (tenant_id, read_at, actor_type, actor_id, patient_id, resource_type, purpose_code, request_id)
                SELECT ?, now() - interval '2 minutes', 'staff', gen_random_uuid(), gen_random_uuid(), 'timeline', 'treatment', 'seal-' || g
                FROM generate_series(1, ?) g""", tenantId, n);
    }

    private static final String RECOMPUTE = """
            SELECT encode(sha256(convert_to(? || E'\\n' || string_agg(phi_read_row_text(id, read_at, actor_type, actor_id, session_id, patient_id,
                   resource_type, resource_id, endpoint, purpose_code, break_glass_grant_id, ip, request_id), E'\\n' ORDER BY id), 'UTF8')), 'hex')
            FROM phi_read_log WHERE tenant_id = ? AND batch_id = ?""";

    @Test
    void sealingChainsEachTenantsBatchesAndBatchesAreFinal() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        backdatedRows(a.id(), 3);
        backdatedRows(b.id(), 2);
        su().update("INSERT INTO phi_read_log (tenant_id, actor_type, patient_id, resource_type, purpose_code) VALUES (?, 'staff', gen_random_uuid(), 'timeline', 'treatment')", a.id());
        sealJob.run();
        JdbcTemplate su = su();
        Map<String, Object> a1 = su.queryForMap("SELECT * FROM phi_read_batches WHERE tenant_id = ? AND batch_id = 1", a.id());
        assertThat(a1.get("row_count")).isEqualTo(3);
        assertThat(a1.get("prev_batch_hash")).isEqualTo("genesis");
        assertThat(su.queryForObject(RECOMPUTE, String.class, "genesis", a.id(), 1L)).isEqualTo(a1.get("batch_hash"));
        assertThat(su.queryForObject("SELECT count(*) FROM phi_read_log WHERE tenant_id = ? AND batch_id IS NULL", Integer.class, a.id()))
                .as("the current minute's row waits for its minute to end").isEqualTo(1);
        assertThat(su.queryForObject("SELECT row_count FROM phi_read_batches WHERE tenant_id = ? AND batch_id = 1", Integer.class, b.id())).isEqualTo(2);

        backdatedRows(a.id(), 2);
        sealJob.run();
        Map<String, Object> a2 = su.queryForMap("SELECT * FROM phi_read_batches WHERE tenant_id = ? AND batch_id = 2", a.id());
        assertThat(a2.get("prev_batch_hash")).as("chained to batch 1").isEqualTo(a1.get("batch_hash"));
        assertThat(a2.get("row_count")).isEqualTo(2);
        assertThat(su.queryForObject(RECOMPUTE, String.class, a1.get("batch_hash"), a.id(), 2L)).isEqualTo(a2.get("batch_hash"));
        assertThat((Long) a2.get("from_id")).isGreaterThan((Long) a1.get("to_id"));

        assertThatThrownBy(() -> su.update("UPDATE phi_read_batches SET batch_hash = 'x' WHERE tenant_id = ?", a.id())).rootCause().hasMessageContaining("final");
        assertThatThrownBy(() -> su.update("DELETE FROM phi_read_batches WHERE tenant_id = ?", a.id())).rootCause().hasMessageContaining("final");
        assertThatThrownBy(() -> su.update("UPDATE phi_read_log SET batch_id = NULL WHERE tenant_id = ?", a.id())).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> inTenantTx(a.id(), () -> jdbc.update(
                "INSERT INTO phi_read_batches (tenant_id, batch_id, from_id, to_id, row_count, batch_hash, prev_batch_hash) VALUES (?,99,1,1,1,'x','y')", a.id())))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void readLogIsTenantIsolated() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        backdatedRows(a.id(), 2);
        assertThat(inTenantTx(a.id(), () -> jdbc.queryForObject("SELECT count(*) FROM phi_read_log", Integer.class))).isEqualTo(2);
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM phi_read_log WHERE tenant_id = ?", Integer.class, a.id()))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM phi_read_log", Integer.class)).as("no tenant context, no rows").isZero();
        assertThatThrownBy(() -> inTenantTx(b.id(), () -> jdbc.update(
                "INSERT INTO phi_read_log (tenant_id, actor_type, patient_id, resource_type, purpose_code) VALUES (?, 'staff', gen_random_uuid(), 'x', 'treatment')", a.id())))
                .rootCause().hasMessageContaining("row-level security");
    }

    // ---- AC5: 61 distinct patients in an hour by a receptionist -> owner in-app alert + security event -------------

    @Test
    void receptionistOpeningSixtyOnePatientsAlertsTheOwnerAndEmitsASecurityEvent() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "plt039-owner@a.com", "+919600039007", false);
        UUID recRole = seedRole(t.id(), "Receptionist", false, fullGrant("patients"), fullGrant("queue"),
                new ModuleGrant("clinical", true, false, false, false, false, false, false));
        SeededStaff rec = seedStaff(t, recRole, "plt039-rec@a.com", "+919600039008", false);
        String recToken = loginAndGetAccessToken(rec);
        String ownerToken = loginAndGetAccessToken(owner);
        List<UUID> patients = new ArrayList<>();
        for (int i = 0; i < 61; i++) patients.add(seedPatient(t.id(), 200 + i));

        for (int i = 0; i < 60; i++) {
            assertThat(get(recToken, "/v1/clinical/patients/" + patients.get(i) + "/allergies").status()).isEqualTo(200);
        }
        get(recToken, "/v1/clinical/patients/" + patients.get(0) + "/allergies"); // a repeat is not a new patient
        get(recToken, "/v1/patients?limit=100"); // a list view does not count as opening records
        drain();
        String alerts = "SELECT count(*) FROM phi_read_volume_alerts WHERE tenant_id = ? AND actor_id = ?";
        assertThat(su().queryForObject(alerts, Integer.class, t.id(), rec.id())).as("60 is within the limit").isZero();

        get(recToken, "/v1/clinical/patients/" + patients.get(60) + "/allergies");
        get(recToken, "/v1/clinical/patients/" + patients.get(5) + "/allergies");
        drain();
        assertThat(su().queryForObject(alerts, Integer.class, t.id(), rec.id())).as("one alert per actor per hour").isEqualTo(1);
        Map<String, Object> ev = su().queryForMap("SELECT payload::text AS p FROM outbox_events WHERE tenant_id = ? AND event_type = ?",
                t.id(), PhiReadRecorder.VOLUME_ALERT_EVENT);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue((String) ev.get("p"), Map.class);
        assertThat(payload).containsEntry("tenantId", t.id().toString()).containsEntry("actorId", rec.id().toString())
                .containsEntry("distinctPatients", 61).containsEntry("windowMinutes", 60);

        ResponseEntity<Map> inbox = exchange("/v1/phi-read/alerts", HttpMethod.GET, authed(ownerToken), Map.class);
        assertThat(inbox.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> items = (List<Map<String, Object>>) inbox.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("actorId", rec.id().toString()).containsEntry("distinctPatients", 61);
        assertThat(exchange("/v1/phi-read/alerts", HttpMethod.GET, authed(recToken), Map.class).getStatusCode().value()).isEqualTo(403);

        double before = meters.counter("nabd.security.events", "type", "phi_read_volume_alert").count();
        dispatcher.poll();
        assertThat(meters.counter("nabd.security.events", "type", "phi_read_volume_alert").count()).isEqualTo(before + 1);
        assertThat(su().queryForObject("SELECT status FROM outbox_events WHERE tenant_id = ? AND event_type = ?", String.class,
                t.id(), PhiReadRecorder.VOLUME_ALERT_EVENT)).isNotEqualTo("pending");
    }

    @Test
    void clinicianLimitIsHigher() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff doc = seedStaff(t, seedFullAccessRole(t.id()), "plt039-clin@a.com", "+919600039009", false);
        String token = loginAndGetAccessToken(doc);
        for (int i = 0; i < 61; i++) {
            get(token, "/v1/clinical/patients/" + seedPatient(t.id(), 300 + i) + "/allergies");
        }
        drain();
        assertThat(su().queryForObject("SELECT count(DISTINCT patient_id) FROM phi_read_log WHERE tenant_id = ? AND actor_id = ?", Integer.class, t.id(), doc.id())).isEqualTo(61);
        assertThat(su().queryForObject("SELECT count(*) FROM phi_read_volume_alerts WHERE tenant_id = ?", Integer.class, t.id()))
                .as("61 is under the clinician limit of 150").isZero();
    }

    @Test
    void breakGlassSessionRecordsBreakGlassPurposeAndGrant() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff doc = seedStaff(t, seedBreakGlassDoctorRole(t.id()), "plt039-bg@a.com", "+919600039010", false);
        String token = loginAndGetAccessToken(doc);
        ResponseEntity<Map> act = activateBreakGlass(token, "Unconscious patient brought in, no history available", null);
        assertThat(act.getStatusCode().is2xxSuccessful()).as(String.valueOf(act.getBody())).isTrue();
        String bgToken = (String) act.getBody().get("accessToken");
        UUID patient = seedPatient(t.id(), 5);
        Hit h = get(bgToken, "/v1/clinical/patients/" + patient + "/allergies");
        assertThat(h.status()).isEqualTo(200);
        drain();
        Map<String, Object> row = rows(h.requestId()).get(0);
        assertThat(row.get("purpose_code")).isEqualTo("break_glass");
        assertThat(row.get("break_glass_grant_id")).isNotNull();
    }

    @Test
    void bodyWalkFindsPatientsInRecordsListsAndPages() {
        record Item(UUID patientId, String other) { }
        record Page(List<Item> data, Object page) { }
        record Detail(UUID id, List<String> allergies) { }
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
        assertThat(PhiReadCapture.patientIds(new Item(p1, "x"), "patientId")).containsExactly(p1);
        assertThat(PhiReadCapture.patientIds(List.of(new Item(p1, "a"), new Item(p2, "b"), new Item(p1, "c")), "patientId")).containsExactly(p1, p2);
        assertThat(PhiReadCapture.patientIds(new Page(List.of(new Item(p2, "a")), null), "patientId")).containsExactly(p2);
        assertThat(PhiReadCapture.patientIds(new Detail(p1, List.of("x")), "id")).containsExactly(p1);
        assertThat(PhiReadCapture.patientIds(List.of(), "patientId")).isEmpty();
        assertThat(PhiReadCapture.patientIds("csv", "patientId")).isEmpty();
        assertThat(PhiReadCapture.patientIds(null, "patientId")).isEmpty();
    }
}

package com.nabd.hms.clinical;

import com.nabd.hms.support.ApiTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S11 IDC-001): consent / own-patients / verification gate and field grants on clinical reads, plus gate cost. */
@SuppressWarnings({"rawtypes", "unchecked"})
class S11QaIdc001ReadScopeTest extends ApiTestBase {

    // CLN-005/006: the doctors seeded here must be registered clinicians for check-in and note writes.
    @Override
    protected SeededStaff seedStaff(SeededTenant tenant, UUID roleId, String email, String mobilePhone, boolean mfaEnabled,
                                    boolean emailVerified, boolean mobileVerified) {
        return com.nabd.hms.support.ClinicianFixture.licensed(
                super.seedStaff(tenant, roleId, email, mobilePhone, mfaEnabled, emailVerified, mobileVerified));
    }

    private static final String NOT_FOUND = "https://api.nabd.health/errors/patient-not-found";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    ClinicalReadScope scopes;
    @Autowired
    TimelineService timelineService;
    @Autowired
    MeterRegistry meters;

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    private record Visit(String patientId, String queueEntryId) {
    }

    private String phone() {
        return "+9193" + (10_000_000 + SEQ.incrementAndGet());
    }

    private SeededStaff staff(SeededTenant t, UUID role) {
        return seedStaff(t, role, "q3i" + UUID.randomUUID() + "@a.com", phone(), false);
    }

    private String registerPatient(String token, String name) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone(), "dob", "1990-01-01", "gender", "female")), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as(String.valueOf(r.getBody())).isTrue();
        return (String) r.getBody().get("id");
    }

    private String checkIn(String token, String patientId, UUID doctorId) {
        ResponseEntity<Map> r = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as(String.valueOf(r.getBody())).isTrue();
        return (String) r.getBody().get("id");
    }

    /** Checked in to doctorId, note with a diagnosis, vitals-free; the visit stays open. */
    private Visit visit(String token, UUID doctorId, String name) {
        String p = registerPatient(token, name);
        String q = checkIn(token, p, doctorId);
        ResponseEntity<Map> note = exchange("/v1/clinical/notes/" + q, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "assessment", "Assessed " + name, "diagnosis", "QaDx-" + name)), Map.class);
        assertThat(note.getStatusCode()).as(String.valueOf(note.getBody())).isEqualTo(HttpStatus.OK);
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult")) {
            exchange("/v1/queue/" + q + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", s)), Map.class);
        }
        return new Visit(p, q);
    }

    private ResponseEntity<String> get(String token, String path) {
        return exchange(path, HttpMethod.GET, authed(token), String.class);
    }

    private void assertGated(ResponseEntity<String> r, String path, String... secrets) {
        assertThat(r.getStatusCode()).as(path + " " + r.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).as(path).contains(NOT_FOUND);
        for (String s : secrets) {
            assertThat(r.getBody()).as(path).doesNotContain(s);
        }
    }

    private int phiRows(UUID tenantId, String patientId) {
        return su.queryForObject("SELECT count(*) FROM phi_read_log WHERE tenant_id = ? AND patient_id = ?::uuid", Integer.class, tenantId, patientId);
    }

    /** PLT-039 writes off the request thread: wait until the count stops moving. */
    private int settledPhiRows(UUID tenantId, String patientId) throws InterruptedException {
        int last = -1;
        for (int i = 0; i < 50; i++) {
            int now = phiRows(tenantId, patientId);
            if (now == last) {
                return now;
            }
            last = now;
            Thread.sleep(100);
        }
        return last;
    }

    private Jwt jwtFor(SeededStaff s) {
        return Jwt.withTokenValue("t").header("alg", "none").subject(s.id().toString())
                .claim("tenantId", s.tenantId().toString()).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
    }

    // ---- AC1 ----
    @Test
    void ac1_withdrawnDataProcessingConsent_everyPatientAndVisitRead404_noPhiInBody_andNoReadLogRow() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff doc = staff(t, seedFullAccessRole(t.id()));
        String tok = loginAndGetAccessToken(doc);
        Visit v = visit(tok, doc.id(), "Qaconsent Zelda");
        String p = v.patientId(), q = v.queueEntryId();
        List<String> paths = List.of("/v1/clinical/patients/" + p + "/timeline", "/v1/clinical/notes/" + q,
                "/v1/clinical/vitals/" + q, "/v1/clinical/prescriptions/" + q, "/v1/clinical/patients/" + p + "/prescriptions",
                "/v1/clinical/patients/" + p + "/allergies", "/v1/clinical/patients/" + p + "/conditions",
                "/v1/clinical/patients/" + p + "/pregnancy-status", "/v1/clinical/visits/" + q + "/documents",
                "/v1/specialty/dental/patients/" + p + "/chart");
        for (String path : List.of(paths.get(0), paths.get(1), paths.get(5))) {
            assertThat(get(tok, path).getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        }
        assertThat(get(tok, "/v1/clinical/encounters").getBody()).contains(q);

        assertThat(exchange("/v1/patients/" + p + "/consent/withdraw", HttpMethod.POST,
                authedJsonBody(tok, Map.of("consentType", "data_processing")), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        int before = settledPhiRows(t.id(), p);
        assertThat(before).as("reads before withdrawal were logged").isGreaterThanOrEqualTo(3);

        for (String path : paths) {
            assertGated(get(tok, path), path, "Zelda", "QaDx-", "Assessed");
        }
        assertThat(get(tok, "/v1/clinical/encounters").getBody()).doesNotContain(q).doesNotContain("Zelda");
        assertThat(settledPhiRows(t.id(), p)).as("a gated 404 writes no read-log row").isEqualTo(before);
    }

    @Test
    void ac1_boundary_withdrawingOnlyMessagingConsent_doesNotGateClinicalReads() {
        SeededTenant t = seedTenant();
        SeededStaff doc = staff(t, seedFullAccessRole(t.id()));
        String tok = loginAndGetAccessToken(doc);
        Visit v = visit(tok, doc.id(), "Qamsg Yara");
        assertThat(exchange("/v1/patients/" + v.patientId() + "/consent/withdraw", HttpMethod.POST,
                authedJsonBody(tok, Map.of("consentType", "messaging")), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(get(tok, "/v1/clinical/patients/" + v.patientId() + "/timeline").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(tok, "/v1/clinical/notes/" + v.queueEntryId()).getBody()).contains("QaDx-Qamsg Yara");
    }

    @Test
    void ac1_tenantIsolation_otherTenantsPatientIsTheSame404() {
        SeededTenant a = seedTenant(), b = seedTenant();
        SeededStaff docA = staff(a, seedFullAccessRole(a.id()));
        Visit v = visit(loginAndGetAccessToken(docA), docA.id(), "Qaiso Xena");
        String tokB = loginAndGetAccessToken(staff(b, seedFullAccessRole(b.id())));
        assertGated(get(tokB, "/v1/clinical/patients/" + v.patientId() + "/timeline"), "timeline", "Xena");
        assertGated(get(tokB, "/v1/clinical/notes/" + v.queueEntryId()), "note", "QaDx-");
        // unknown id gives the identical answer (existence not leaked)
        assertGated(get(tokB, "/v1/clinical/patients/" + UUID.randomUUID() + "/timeline"), "unknown");
    }

    // ---- AC2 ----
    @Test
    void ac2_ownPatientsDoctor_stranger404_walkIn200_appointment200_andANewVisitGrantsAccess() {
        SeededTenant t = seedTenant();
        UUID role = seedFullAccessRole(t.id());
        SeededStaff desk = staff(t, role), other = staff(t, role), doc = staff(t, role);
        su.update("UPDATE staff SET scope = 'own_patients_only' WHERE id = ?", doc.id());
        String deskTok = loginAndGetAccessToken(desk), docTok = loginAndGetAccessToken(doc);

        Visit stranger = visit(deskTok, other.id(), "Qastranger Wynn");
        Visit walkIn = visit(deskTok, doc.id(), "Qawalkin Vera");
        String booked = registerPatient(deskTok, "Qabooked Uma");
        su.update("INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, status) VALUES (?,?,?,?,?,?,'completed')",
                UUID.randomUUID(), t.id(), UUID.fromString(booked), doc.id(),
                Timestamp.from(Instant.parse("2021-03-01T10:00:00Z")), Timestamp.from(Instant.parse("2021-03-01T10:15:00Z")));

        for (String path : List.of("/v1/clinical/patients/" + stranger.patientId() + "/timeline", "/v1/clinical/notes/" + stranger.queueEntryId(),
                "/v1/clinical/patients/" + stranger.patientId() + "/allergies", "/v1/clinical/vitals/" + stranger.queueEntryId())) {
            assertGated(get(docTok, path), path, "Wynn", "QaDx-");
        }
        assertThat(get(docTok, "/v1/clinical/encounters").getBody()).doesNotContain(stranger.queueEntryId()).contains(walkIn.queueEntryId());
        assertThat(get(docTok, "/v1/clinical/patients/" + walkIn.patientId() + "/timeline").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(docTok, "/v1/clinical/notes/" + walkIn.queueEntryId()).getBody()).contains("QaDx-Qawalkin Vera");
        assertThat(get(docTok, "/v1/clinical/patients/" + booked + "/timeline").getStatusCode()).isEqualTo(HttpStatus.OK);

        // the stranger becomes her patient the moment she is checked in to her
        checkIn(deskTok, stranger.patientId(), doc.id());
        assertThat(get(docTok, "/v1/clinical/patients/" + stranger.patientId() + "/timeline").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- AC3 ----
    @Test
    void ac3_unverifiedCaller_clinicalListsRefusedExactlyAsTheRegistry() {
        SeededTenant t = seedTenant();
        UUID role = seedFullAccessRole(t.id());
        SeededStaff doc = staff(t, role);
        visit(loginAndGetAccessToken(doc), doc.id(), "Qaunver Tia");
        SeededStaff unverified = seedStaff(t, role, "q3u" + UUID.randomUUID() + "@a.com", phone(), false, true, false);
        String tok = loginAndGetAccessToken(unverified);
        ResponseEntity<Map> registry = exchange("/v1/patients/registry", HttpMethod.GET, authed(tok), Map.class);
        ResponseEntity<Map> encounters = exchange("/v1/clinical/encounters", HttpMethod.GET, authed(tok), Map.class);
        ResponseEntity<Map> due = exchange("/v1/clinical/conditions/due", HttpMethod.GET, authed(tok), Map.class);
        assertThat(registry.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(encounters.getStatusCode()).isEqualTo(registry.getStatusCode());
        assertThat(due.getStatusCode()).isEqualTo(registry.getStatusCode());
        assertThat(encounters.getBody().get("type")).isEqualTo(registry.getBody().get("type"));
        assertThat(String.valueOf(encounters.getBody())).doesNotContain("Tia");
    }

    // ---- AC4 ----
    @Test
    void ac4_fieldGrantsWithoutDiagnosis_nullInNoteTimelineAndEncounterList_withDiagnosisGrantVisible() {
        SeededTenant t = seedTenant();
        UUID role = seedFullAccessRole(t.id());
        SeededStaff doc = staff(t, role), allowed = staff(t, role);
        String docTok0 = loginAndGetAccessToken(doc);
        Visit v = visit(docTok0, doc.id(), "Qafield Sana");
        // the timeline lists completed visits only: check the visit out
        exchange("/v1/queue/" + v.queueEntryId() + "/status", HttpMethod.PATCH, authedJsonBody(docTok0, Map.of("status", "checkout_pending")), Map.class);
        ResponseEntity<Map> co = exchange("/v1/billing/checkout/" + v.queueEntryId(), HttpMethod.POST, authedJsonBody(docTok0, Map.of(
                "lineItems", List.of(Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 100, "taxRatePercent", 0)))), Map.class);
        assertThat(co.getStatusCode().is2xxSuccessful()).as("checkout " + co.getBody()).isTrue();
        assertThat(get(docTok0, "/v1/clinical/patients/" + v.patientId() + "/timeline").getBody()).as("timeline before grant change").contains("QaDx-Qafield Sana");
        // the encounter list is the doctor's own visits (DOC-005), so the restricted reader is the visit's doctor herself
        assertThat(get(loginAndGetAccessToken(doc), "/v1/clinical/encounters").getBody()).as("listed before the grant change").contains(v.queueEntryId());
        su.update("UPDATE staff SET field_grants = '{nric}' WHERE id = ?", doc.id());
        su.update("UPDATE staff SET field_grants = '{diagnosis}' WHERE id = ?", allowed.id());
        String rTok = loginAndGetAccessToken(doc), aTok = loginAndGetAccessToken(allowed);

        Map note = exchange("/v1/clinical/notes/" + v.queueEntryId(), HttpMethod.GET, authed(rTok), Map.class).getBody();
        assertThat(note).containsEntry("diagnosis", null).containsEntry("assessment", "Assessed Qafield Sana");
        String timeline = get(rTok, "/v1/clinical/patients/" + v.patientId() + "/timeline").getBody();
        assertThat(timeline).contains(v.queueEntryId()).doesNotContain("QaDx-");
        assertThat(get(rTok, "/v1/clinical/encounters").getBody()).contains(v.queueEntryId()).doesNotContain("QaDx-");

        assertThat(exchange("/v1/clinical/notes/" + v.queueEntryId(), HttpMethod.GET, authed(aTok), Map.class).getBody())
                .containsEntry("diagnosis", "QaDx-Qafield Sana");
        assertThat(get(aTok, "/v1/clinical/patients/" + v.patientId() + "/timeline").getBody()).contains("QaDx-Qafield Sana");
    }

    // ---- AC6 / task (a): what the gate costs ----
    private double timerCount() {
        Timer timer = meters.find("hikaricp.connections.acquire").timer();
        assertThat(timer).as("Hikari pool metrics are bound").isNotNull();
        return timer.count();
    }

    @Test
    void ac6a_gateCostsExactlyTwoExtraPoolCheckoutsPerRead_andInOneTransactionItCostsNone() {
        SeededTenant t = seedTenant();
        SeededStaff doc = staff(t, seedFullAccessRole(t.id()));
        Visit v = visit(loginAndGetAccessToken(doc), doc.id(), "Qapool Rhea");
        UUID p = UUID.fromString(v.patientId());
        Jwt jwt = jwtFor(doc);

        double c0 = timerCount();
        timelineService.get(t.id(), p, 20, null);
        double ungated = timerCount() - c0;

        c0 = timerCount();
        ReadScope scope = scopes.resolve(jwt);
        scopes.requirePatient(scope, p);
        timelineService.get(t.id(), p, 20, null);
        double gated = timerCount() - c0;

        c0 = timerCount();
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            ReadScope sc = scopes.resolve(jwt);
            scopes.requirePatient(sc, p);
            timelineService.get(t.id(), p, 20, null);
        });
        double oneTx = timerCount() - c0;
        System.out.printf("QA3 IDC-001 pool checkouts per timeline read: ungated=%.0f gated=%.0f gated-in-one-tx=%.0f%n", ungated, gated, oneTx);
        assertThat(ungated).isEqualTo(1);
        assertThat(gated).as("resolve() and requirePatient() each open their own transaction").isEqualTo(3);
        assertThat(oneTx).isEqualTo(1);
    }

    private static long p95(List<Long> nanos) {
        List<Long> s = new ArrayList<>(nanos);
        Collections.sort(s);
        return s.get((int) Math.ceil(s.size() * 0.95) - 1);
    }

    private List<Long> parallel(int threads, int reads, Callable<Object> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Long>> fs = new ArrayList<>();
            for (int i = 0; i < reads; i++) {
                fs.add(pool.submit(() -> {
                    long t0 = System.nanoTime();
                    work.call();
                    return System.nanoTime() - t0;
                }));
            }
            List<Long> out = new ArrayList<>();
            for (Future<Long> f : fs) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** AC6: 50 parallel reads, p95 gated - p95 ungated <= 15 ms. Interleaved rounds; the median delta is asserted. */
    @Test
    @org.junit.jupiter.api.Tag("known-defect-IDC-001")
    void ac6b_fiftyParallelReads_gatedP95WithinFifteenMsOfUngated() throws Exception {
        SeededTenant t = seedTenant();
        SeededStaff doc = staff(t, seedFullAccessRole(t.id()));
        Visit v = visit(loginAndGetAccessToken(doc), doc.id(), "Qaperf Quinn");
        UUID p = UUID.fromString(v.patientId());
        Jwt jwt = jwtFor(doc);
        Callable<Object> ungated = () -> timelineService.get(t.id(), p, 20, null);
        Callable<Object> gated = () -> {
            ReadScope s = scopes.resolve(jwt);
            scopes.requirePatient(s, p);
            return timelineService.get(t.id(), p, 20, null);
        };
        Callable<Object> gatedOneTx = () -> new TransactionTemplate(txManager).execute(st -> {
            try {
                return gated.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        parallel(50, 200, gated); // warm-up
        List<Long> deltas = new ArrayList<>(), deltasOneTx = new ArrayList<>();
        StringBuilder rounds = new StringBuilder();
        for (int round = 0; round < 3; round++) {
            long base = p95(parallel(50, 200, ungated));
            long g = p95(parallel(50, 200, gated));
            long g1 = p95(parallel(50, 200, gatedOneTx));
            deltas.add(g - base);
            deltasOneTx.add(g1 - base);
            rounds.append(String.format("[ungated=%.1f gated=%.1f oneTx=%.1f]", base / 1e6, g / 1e6, g1 / 1e6));
        }
        Collections.sort(deltas);
        Collections.sort(deltasOneTx);
        String summary = String.format("rounds p95 ms %s; median p95 delta ms: gated=%.1f gated-one-tx=%.1f", rounds, deltas.get(1) / 1e6, deltasOneTx.get(1) / 1e6);
        System.out.println("QA3 IDC-001 " + summary);
        assertThat(deltas.get(1) / 1e6).as("median p95 delta (ms) of the gate as shipped; " + summary).isLessThanOrEqualTo(15.0);
    }
}

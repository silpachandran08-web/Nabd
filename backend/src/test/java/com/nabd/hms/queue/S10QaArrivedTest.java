package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent QA (S10) for ACC-003, written from the story's acceptance criteria. */
class S10QaArrivedTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(7000);

    private record Ctx(SeededTenant tenant, SeededStaff doctor, String token) { }

    private Ctx ctx() {
        SeededTenant t = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = 'Asia/Kolkata' WHERE id = ?", t.id());
        int n = SEQ.incrementAndGet();
        SeededStaff d = seedStaff(t, seedFullAccessRole(t.id()), "s10qa3" + n + "@a.com", "+9190" + (1000000 + n), false);
        return new Ctx(t, d, loginAndGetAccessToken(d));
    }

    private String patient(Ctx c) {
        int n = SEQ.incrementAndGet();
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "name", UUID.randomUUID().toString().replace("-", "").substring(0, 10) + " Zq", "phone", "+91989" + (1000000 + n), "dob", (1940 + n % 50) + "-0" + (1 + n % 9) + "-1" + (n % 9), "gender", "male")), Map.class);
        assertThat(p.getStatusCode()).as("patient " + p.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) p.getBody().get("id");
    }

    /** Appointment inserted as superuser, start offsetMinutes from now (bypasses booking rules). */
    private UUID appt(Ctx c, String patientId, Instant start, UUID doctor, String status) {
        UUID id = UUID.randomUUID();
        try (Connection su = superuserConnection();
             PreparedStatement ps = su.prepareStatement("INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, status) VALUES (?,?,?,?,?,?,?)")) {
            ps.setObject(1, id); ps.setObject(2, c.tenant.id()); ps.setObject(3, UUID.fromString(patientId)); ps.setObject(4, doctor);
            ps.setTimestamp(5, Timestamp.from(start)); ps.setTimestamp(6, Timestamp.from(start.plusSeconds(1))); ps.setString(7, status);
            ps.executeUpdate();
        } catch (Exception e) { throw new IllegalStateException(e); }
        return id;
    }

    private final AtomicInteger offset = new AtomicInteger(60);

    /** Today's appointment; 1 s long and a distinct start so rows of one doctor never overlap (ACC-004 constraint). */
    private UUID apptNow(Ctx c, String patientId) {
        return appt(c, patientId, Instant.now().plusSeconds(offset.incrementAndGet()), c.doctor.id(), "scheduled");
    }

    private ResponseEntity<Map> checkIn(String token, UUID doctor, String patientId, UUID appt) {
        return exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, appt == null
                ? Map.of("patientId", patientId, "doctorId", doctor.toString())
                : Map.of("patientId", patientId, "doctorId", doctor.toString(), "appointmentId", appt.toString())), Map.class);
    }

    private String sql(String q, Object... args) {
        try (Connection su = superuserConnection(); PreparedStatement ps = su.prepareStatement(q)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            var rs = ps.executeQuery();
            return rs.next() ? String.valueOf(rs.getObject(1)) : null;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String code(ResponseEntity<Map> r) {
        Object c = r.getBody().get("code");
        if (c != null) return c.toString();
        String t = String.valueOf(r.getBody().get("type"));
        return t.substring(t.lastIndexOf('/') + 1).toUpperCase().replace('-', '_');
    }

    // ---------- AC1 ----------
    @Test
    void ac1_twoDesksSameMoment_50Repetitions_oneTokenNeverTwo() throws Exception {
        Ctx c = ctx();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int rep = 0; rep < 50; rep++) {
                String p = patient(c);
                UUID a = apptNow(c, p);
                CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
                List<Future<ResponseEntity<Map>>> fs = new ArrayList<>();
                for (int i = 0; i < 2; i++) fs.add(pool.submit(() -> { ready.countDown(); go.await(); return checkIn(c.token, c.doctor.id(), p, a); }));
                ready.await(); go.countDown();
                int created = 0, dup = 0;
                for (Future<ResponseEntity<Map>> f : fs) {
                    ResponseEntity<Map> r = f.get(60, TimeUnit.SECONDS);
                    if (r.getStatusCode() == HttpStatus.CREATED) { created++; assertThat(r.getBody().get("tokenNumber")).isNotNull(); }
                    else if (r.getStatusCode() == HttpStatus.CONFLICT && "ALREADY_CHECKED_IN".equals(code(r))) dup++;
                }
                assertThat(created).as("rep " + rep + " 201s").isEqualTo(1);
                assertThat(dup).as("rep " + rep + " 409s").isEqualTo(1);
                assertThat(sql("SELECT count(*) FROM queue_entries WHERE appointment_id = ?", a)).isEqualTo("1");
            }
        } finally { pool.shutdownNow(); }
    }

    @Test
    void ac1_tenDesksAtOnce_oneEntryNineConflicts() throws Exception {
        Ctx c = ctx();
        String p = patient(c);
        UUID a = apptNow(c, p);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch ready = new CountDownLatch(10), go = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> fs = new ArrayList<>();
        for (int i = 0; i < 10; i++) fs.add(pool.submit(() -> { ready.countDown(); go.await(); return checkIn(c.token, c.doctor.id(), p, a); }));
        ready.await(); go.countDown();
        int created = 0, dup = 0;
        for (Future<ResponseEntity<Map>> f : fs) {
            ResponseEntity<Map> r = f.get(60, TimeUnit.SECONDS);
            if (r.getStatusCode() == HttpStatus.CREATED) created++;
            else if (r.getStatusCode() == HttpStatus.CONFLICT && "ALREADY_CHECKED_IN".equals(code(r))) dup++;
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        assertThat(dup).isEqualTo(9);
        assertThat(sql("SELECT count(*) FROM queue_entries WHERE appointment_id = ?", a)).isEqualTo("1");
    }

    @Test
    void ac1_sequentialSecondCheckInIs409AlreadyCheckedIn_withExistingToken() {
        Ctx c = ctx();
        String p = patient(c);
        UUID a = apptNow(c, p);
        ResponseEntity<Map> first = checkIn(c.token, c.doctor.id(), p, a);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> second = checkIn(c.token, c.doctor.id(), p, a);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(second)).isEqualTo("ALREADY_CHECKED_IN");
        assertThat(String.valueOf(second.getBody().get("tokenNumber"))).isEqualTo(String.valueOf(first.getBody().get("tokenNumber")));
    }

    // ---------- AC2 ----------
    @Test
    void ac2_checkInResponseAndAppointmentShowArrived() {
        Ctx c = ctx();
        String p = patient(c);
        UUID a = apptNow(c, p);
        assertThat(exchange("/v1/appointments/" + a, HttpMethod.GET, authed(c.token), Map.class).getBody().get("status")).isEqualTo("scheduled");
        ResponseEntity<Map> r = checkIn(c.token, c.doctor.id(), p, a);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("appointmentStatus")).isEqualTo("arrived");
        assertThat(exchange("/v1/appointments/" + a, HttpMethod.GET, authed(c.token), Map.class).getBody().get("status")).isEqualTo("arrived");
        assertThat(sql("SELECT arrived_at IS NOT NULL FROM appointments WHERE id = ?", a)).isEqualTo("true");
    }

    @Test
    void ac2_walkInCheckInStillWorks_noAppointment_andTwoWalkInsGetTwoTokens() {
        Ctx c = ctx();
        ResponseEntity<Map> a = checkIn(c.token, c.doctor.id(), patient(c), null);
        ResponseEntity<Map> b = checkIn(c.token, c.doctor.id(), patient(c), null);
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(a.getBody().get("tokenNumber")).isNotEqualTo(b.getBody().get("tokenNumber"));
    }

    // ---------- AC3 ----------
    @Test
    void ac3_cancelAndRescheduleArrivedAre409AppointmentArrived_waitlistNotOffered() {
        Ctx c = ctx();
        String p = patient(c);
        UUID a = apptNow(c, p);
        // someone is waiting for this doctor
        String waiter = patient(c);
        ResponseEntity<Map> join = exchange("/v1/waitlist", HttpMethod.POST, authedJsonBody(c.token, Map.of("doctorId", c.doctor.id().toString(), "patientId", waiter)), Map.class);
        assertThat(join.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(checkIn(c.token, c.doctor.id(), p, a).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> cancel = exchange("/v1/appointments/" + a + "/cancel", HttpMethod.POST, authedJsonBody(c.token, Map.of("reason", "patient asked")), Map.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(cancel)).isEqualTo("APPOINTMENT_ARRIVED");
        ResponseEntity<Map> resched = exchange("/v1/appointments/" + a + "/reschedule", HttpMethod.POST,
                authedJsonBody(c.token, Map.of("newStartTime", Instant.now().plusSeconds(3 * 86400).toString())), Map.class);
        assertThat(resched.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(resched)).isEqualTo("APPOINTMENT_ARRIVED");

        assertThat(sql("SELECT status FROM appointments WHERE id = ?", a)).isEqualTo("arrived");
        assertThat(sql("SELECT status FROM waitlist_entries WHERE id = ?", UUID.fromString((String) join.getBody().get("id")))).isEqualTo("waiting");
    }

    @Test
    void ac3_stillScheduledCancelStillWorks() {
        Ctx c = ctx();
        UUID a = apptNow(c, patient(c));
        ResponseEntity<Map> r = exchange("/v1/appointments/" + a + "/cancel", HttpMethod.POST, authedJsonBody(c.token, Map.of("reason", "patient asked")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("status")).isEqualTo("cancelled");
    }

    // ---------- AC4 ----------
    @Test
    void ac4_transferGeneralToDerm_lastLegCompletes_appointmentCompletedNeverLeftScheduled() {
        Ctx c = ctx();
        SeededStaff derm = seedStaff(c.tenant, c.doctor.roleId(), "derm" + SEQ.incrementAndGet() + "@a.com", "+9188" + (1000000 + SEQ.get()), false);
        String generalId = (String) ((List<Map>) exchange("/v1/departments", HttpMethod.GET, authed(c.token), List.class).getBody())
                .stream().filter(d -> Boolean.TRUE.equals(d.get("isDefault"))).findFirst().orElseThrow().get("id");
        String dermId = (String) exchange("/v1/departments", HttpMethod.POST, authedJsonBody(c.token, Map.of("name", "Dermatology", "active", true)), Map.class).getBody().get("id");
        exchange("/v1/departments/" + dermId + "/workflow", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "templateCode", "clinic_walkin", "toggles", Map.of("vitals_enabled", false))), Map.class);
        inTenantTx(c.tenant.id(), () -> jdbc.update("UPDATE staff SET department_id = ? WHERE id = ?", UUID.fromString(dermId), derm.id()));
        exchange("/v1/departments/transfers", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "edges", List.of(Map.of("fromDepartmentId", generalId, "toDepartmentId", dermId)))), List.class);

        String p = patient(c);
        UUID a = apptNow(c, p);
        String entry = (String) checkIn(c.token, c.doctor.id(), p, a).getBody().get("id");
        String prev = "checked_in";
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult")) {
            assertThat(exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(c.token, Map.of("status", s, "expectedStatus", prev)), Map.class).getStatusCode()).as(s).isEqualTo(HttpStatus.OK);
            prev = s;
        }
        ResponseEntity<Map> tr = exchange("/v1/queue/" + entry + "/transfer", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "toDepartmentId", dermId, "doctorId", derm.id().toString(), "reason", "other", "reasonNote", "skin")), Map.class);
        assertThat(tr.getStatusCode()).as("transfer " + tr.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(sql("SELECT status FROM appointments WHERE id = ?", a)).as("mid-visit after first leg ends").isEqualTo("arrived");
        String leg2 = (String) ((Map) tr.getBody().get("newLeg")).get("id");
        // walk leg 2 to a billing stop by whatever the dept flow allows, then checkout
        for (String s : List.of("in_consult", "billing_pending", "checkout_pending")) {
            exchange("/v1/queue/" + leg2 + "/status", HttpMethod.PATCH, authedJsonBody(c.token, Map.of("status", s)), Map.class); // steps this flow lacks are 400s
        }
        assertThat(sql("SELECT status FROM queue_entries WHERE id = ?", UUID.fromString(leg2))).isEqualTo("checkout_pending");
        assertThat(exchange("/v1/billing/checkout/" + leg2, HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "lineItems", List.of(Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "X", "chargeName", "X", "category", "Service", "quantity", 1, "unitPrice", 100, "taxRatePercent", 0)))), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sql("SELECT status FROM queue_entries WHERE id = ?", UUID.fromString(leg2))).isEqualTo("completed");
        assertThat(sql("SELECT status FROM appointments WHERE id = ?", a)).isEqualTo("completed");
    }

    @Test
    void ac4_noShowOfArrivedVisit_marksAppointmentNoShow() {
        Ctx c = ctx();
        String p = patient(c);
        UUID a = apptNow(c, p);
        String entry = (String) checkIn(c.token, c.doctor.id(), p, a).getBody().get("id");
        String prev = "checked_in";
        for (String s : List.of("waiting", "no_show")) {
            assertThat(exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(c.token, Map.of("status", s, "expectedStatus", prev)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
            prev = s;
        }
        assertThat(sql("SELECT status FROM appointments WHERE id = ?", a)).isEqualTo("no_show");
    }

    // ---------- AC5 ----------
    @Test
    void ac5_sameIdempotencyKeyRetry_returnsOriginal201_noSecondToken() {
        Ctx c = ctx();
        String p = patient(c);
        UUID a = apptNow(c, p);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(c.token);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        String body = "{\"patientId\":\"" + p + "\",\"doctorId\":\"" + c.doctor.id() + "\",\"appointmentId\":\"" + a + "\"}";
        var entity = new org.springframework.http.HttpEntity<>(body, h);
        ResponseEntity<Map> first = exchange("/v1/queue/check-in", HttpMethod.POST, entity, Map.class);
        ResponseEntity<Map> retry = exchange("/v1/queue/check-in", HttpMethod.POST, entity, Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).as("retry body " + retry.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(retry.getBody().get("tokenNumber")).isEqualTo(first.getBody().get("tokenNumber"));
        assertThat(sql("SELECT count(*) FROM queue_entries WHERE appointment_id = ?", a)).isEqualTo("1");
    }

    // ---------- AC6 (k6 not runnable; JUnit in-process proxy for the same shape) ----------
    @Test
    void ac6_proxy_20DesksFiveDoctors_checkInP95Under300ms() throws Exception {
        Ctx c = ctx();
        List<SeededStaff> docs = new ArrayList<>();
        docs.add(c.doctor);
        for (int i = 0; i < 4; i++) {
            int n = SEQ.incrementAndGet();
            docs.add(seedStaff(c.tenant, c.doctor.roleId(), "pd" + n + "@a.com", "+9187" + (1000000 + n), false));
        }
        int desks = 20;
        List<String> patients = new ArrayList<>();
        for (int i = 0; i < desks * 2; i++) patients.add(patient(c));
        List<Long> lat = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(desks);
        CountDownLatch ready = new CountDownLatch(desks), go = new CountDownLatch(1);
        List<Future<HttpStatus>> fs = new ArrayList<>();
        for (int d = 0; d < desks; d++) {
            final int desk = d;
            fs.add(pool.submit(() -> {
                ready.countDown(); go.await();
                HttpStatus last = null;
                for (int k = 0; k < 2; k++) {
                    long t = System.nanoTime();
                    ResponseEntity<Map> r = checkIn(c.token, docs.get(desk % 5).id(), patients.get(desk * 2 + k), null);
                    lat.add((System.nanoTime() - t) / 1_000_000);
                    last = (HttpStatus) r.getStatusCode();
                    if (last != HttpStatus.CREATED) return last;
                }
                return last;
            }));
        }
        ready.await(); go.countDown();
        for (Future<HttpStatus> f : fs) assertThat(f.get(120, TimeUnit.SECONDS)).isEqualTo(HttpStatus.CREATED);
        pool.shutdown();
        List<Long> s = new ArrayList<>(lat);
        Collections.sort(s);
        long p95 = s.get((int) Math.ceil(s.size() * 0.95) - 1);
        System.out.println("S10QA ACC-003 proxy check-in latencies ms (n=" + s.size() + "): p50=" + s.get(s.size() / 2) + " p95=" + p95 + " max=" + s.get(s.size() - 1));
        assertThat(p95).as("p95 check-in ms (JUnit proxy, not k6)").isLessThan(300);
    }

    // ---------- other API contract ----------
    @Test
    void notToday_notFound_andCrossTenantAppointment() {
        Ctx c = ctx();
        String p = patient(c);
        UUID tomorrow = appt(c, p, Instant.now().plusSeconds(2 * 86400), c.doctor.id(), "scheduled");
        ResponseEntity<Map> nt = checkIn(c.token, c.doctor.id(), p, tomorrow);
        assertThat(nt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(nt)).isEqualTo("APPOINTMENT_NOT_TODAY");
        assertThat(sql("SELECT status FROM appointments WHERE id = ?", tomorrow)).isEqualTo("scheduled");

        assertThat(checkIn(c.token, c.doctor.id(), p, UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Ctx other = ctx();
        UUID mine = apptNow(c, p);
        assertThat(checkIn(other.token, other.doctor.id(), patient(other), mine).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(sql("SELECT status FROM appointments WHERE id = ?", mine)).isEqualTo("scheduled");
        // other tenant also cannot cancel it
        assertThat(exchange("/v1/appointments/" + mine + "/cancel", HttpMethod.POST, authedJsonBody(other.token, Map.of("reason", "xxx")), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void checkInWritesAuditRow() {
        Ctx c = ctx();
        ResponseEntity<Map> r = checkIn(c.token, c.doctor.id(), patient(c), null);
        String id = (String) r.getBody().get("id");
        Integer n = inTenantTx(c.tenant.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'queue.check_in' AND entity_id = ?", Integer.class, UUID.fromString(id)));
        assertThat(n).isEqualTo(1);
    }

    @Test
    void dbBackstop_secondLiveQueueRowForSameAppointmentIsRejected_legacyDuplicateAllowed() throws Exception {
        Ctx c = ctx();
        String p = patient(c);
        UUID a = apptNow(c, p);
        assertThat(checkIn(c.token, c.doctor.id(), p, a).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String copy = "INSERT INTO queue_entries (id, tenant_id, appointment_id, patient_id, doctor_id, department_id, queue_date, token_number, source, status, legacy_duplicate) " +
                "SELECT gen_random_uuid(), tenant_id, appointment_id, patient_id, doctor_id, department_id, queue_date, token_number + 100, source, status, ? FROM queue_entries WHERE appointment_id = ? AND legacy_duplicate = false";
        try (Connection su = superuserConnection()) {
            assertThatThrownBy(() -> { try (PreparedStatement ps = su.prepareStatement(copy)) { ps.setBoolean(1, false); ps.setObject(2, a); ps.executeUpdate(); } })
                    .hasMessageContaining("uq_queue_entries_appointment");
            try (PreparedStatement ps = su.prepareStatement(copy)) { ps.setBoolean(1, true); ps.setObject(2, a); assertThat(ps.executeUpdate()).isEqualTo(1); }
        }
    }

    // ---------- events listed in the story ----------
    @Test
    // known defect (ACC-003): checked_in/arrived events are not yet published to the outbox
    @Tag("known-defect-ACC-003")
    void events_checkedInAndArrivedArePublishedToOutbox() {
        Ctx c = ctx();
        String p = patient(c);
        UUID a = apptNow(c, p);
        ResponseEntity<Map> r = checkIn(c.token, c.doctor.id(), p, a);
        String id = (String) r.getBody().get("id");
        Integer checkedIn = inTenantTx(c.tenant.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = 'visit.checked_in.v1' AND payload->>'queueEntryId' = ?", Integer.class, id));
        Integer arrived = inTenantTx(c.tenant.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = 'scheduling.appointment.arrived.v1' AND payload->>'appointmentId' = ?", Integer.class, a.toString()));
        assertThat(checkedIn).as("visit.checked_in.v1").isEqualTo(1);
        assertThat(arrived).as("scheduling.appointment.arrived.v1").isEqualTo(1);
    }
}

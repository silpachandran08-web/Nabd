package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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

/** ACC-003: an appointment is checked in once and then shows 'arrived'. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Acc003ArrivedTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(5000);
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private record Ctx(SeededTenant tenant, SeededStaff doctor, String token) { }

    private Ctx ctx() {
        SeededTenant tenant = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", ZONE.getId(), tenant.id());
        int n = SEQ.incrementAndGet();
        SeededStaff doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), "acc3" + n + "@a.com", "+9195" + (2000000 + n), false);
        return new Ctx(tenant, doctor, loginAndGetAccessToken(doctor));
    }

    private String patient(Ctx c) {
        int n = SEQ.incrementAndGet();
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "name", UUID.randomUUID().toString().replace("-", "").substring(0, 10) + " Zq", "phone", "+9197" + (2000000 + n),
                "dob", "1980-01-15", "gender", "male")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private Instant noonToday(int plusMinutes) {
        return LocalDate.now(ZONE).atTime(12, 0).atZone(ZONE).toInstant().plusSeconds(60L * plusMinutes);
    }

    /** Inserted as superuser (bypasses booking rules such as "not in the past"). */
    private UUID appt(Ctx c, String patientId, Instant start, String doctorStatus) {
        UUID id = UUID.randomUUID();
        try (Connection su = superuserConnection();
             PreparedStatement ps = su.prepareStatement("INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, status, cancel_reason) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setObject(1, id);
            ps.setObject(2, c.tenant.id());
            ps.setObject(3, UUID.fromString(patientId));
            ps.setObject(4, c.doctor.id());
            ps.setTimestamp(5, Timestamp.from(start));
            ps.setTimestamp(6, Timestamp.from(start.plusSeconds(900)));
            ps.setString(7, doctorStatus);
            ps.setString(8, "cancelled".equals(doctorStatus) ? "x" : null);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return id;
    }

    private ResponseEntity<Map> checkIn(Ctx c, String patientId, UUID apptId) {
        return exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token, Map.of("patientId", patientId,
                "doctorId", c.doctor.id().toString(), "appointmentId", apptId.toString())), Map.class);
    }

    private String sql(String query, Object... args) {
        try (Connection su = superuserConnection(); PreparedStatement ps = su.prepareStatement(query)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            var rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<HttpStatus> race(Ctx c, String patientId, UUID apptId, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<Map>>> fs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                fs.add(pool.submit(() -> {
                    start.await();
                    return checkIn(c, patientId, apptId);
                }));
            }
            start.countDown();
            List<HttpStatus> out = new ArrayList<>();
            for (var f : fs) {
                ResponseEntity<Map> r = f.get(60, TimeUnit.SECONDS);
                out.add((HttpStatus) r.getStatusCode());
                if (r.getStatusCode() == HttpStatus.CONFLICT) {
                    assertThat(r.getBody().get("code")).isEqualTo("ALREADY_CHECKED_IN");
                    assertThat(r.getBody().get("type").toString()).endsWith("already-checked-in");
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // AC1: two desks at once -> one 201, the other 409; 50 repetitions, zero duplicates
    @Test
    void ac1_twoDesksAtOnce_50repetitions_exactlyOneToken() throws Exception {
        Ctx c = ctx();
        String p = patient(c);
        for (int i = 0; i < 50; i++) {
            UUID a = appt(c, p, noonToday(i), "scheduled");
            List<HttpStatus> s = race(c, p, a, 2);
            assertThat(s).as("rep " + i).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
            assertThat(sql("SELECT count(*) FROM queue_entries WHERE appointment_id = ?", a)).isEqualTo("1");
            assertThat(sql("SELECT status FROM appointments WHERE id = ?", a)).isEqualTo("arrived");
        }
        assertThat(sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'queue.check_in'", c.tenant.id())).isEqualTo("50");
    }

    @Test
    void ac1_tenDesksAtOnce_exactlyOneToken() throws Exception {
        Ctx c = ctx();
        String p = patient(c);
        UUID a = appt(c, p, noonToday(0), "scheduled");
        List<HttpStatus> s = race(c, p, a, 10);
        assertThat(s.stream().filter(x -> x == HttpStatus.CREATED).count()).isEqualTo(1);
        assertThat(s.stream().filter(x -> x == HttpStatus.CONFLICT).count()).isEqualTo(9);
        assertThat(sql("SELECT count(*) FROM queue_entries WHERE appointment_id = ?", a)).isEqualTo("1");
    }

    // AC2: arrived shown; 409 body names the first token
    @Test
    void ac2_checkInShowsArrivedAndSecondAttemptNamesTheToken() {
        Ctx c = ctx();
        String p = patient(c);
        UUID a = appt(c, p, noonToday(0), "scheduled");
        ResponseEntity<Map> first = checkIn(c, p, a);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().get("appointmentStatus")).isEqualTo("arrived");
        assertThat(sql("SELECT arrived_at IS NOT NULL FROM appointments WHERE id = ?", a)).isEqualTo("t");
        ResponseEntity<Map> second = checkIn(c, p, a);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("tokenNumber")).isEqualTo(first.getBody().get("tokenNumber"));
        assertThat(second.getBody().get("queueEntryId")).isEqualTo(first.getBody().get("id"));
        assertThat(second.getBody().get("checkedInAt")).isNotNull();
        // the calendar board reads the same appointment
        ResponseEntity<Map> list = exchange("/v1/appointments?patientId=" + p, HttpMethod.GET, authed(c.token), Map.class);
        assertThat(((List<Map>) list.getBody().get("data")).get(0).get("status")).isEqualTo("arrived");
    }

    @Test
    void ac2_cancelledOrUnknownAppointmentIsStill404_walkInUnaffected() {
        Ctx c = ctx();
        String p = patient(c);
        assertThat(checkIn(c, p, appt(c, p, noonToday(0), "cancelled")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(checkIn(c, p, UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> walkIn = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "patientId", p, "doctorId", c.doctor.id().toString())), Map.class);
        assertThat(walkIn.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(walkIn.getBody().get("appointmentStatus")).isNull();
    }

    // AC3: cancel / reschedule refused once arrived
    @Test
    void ac3_cancelAndRescheduleArrivedAre409_slotStaysTaken() {
        Ctx c = ctx();
        String p = patient(c);
        Instant start = noonToday(0);
        UUID a = appt(c, p, start, "scheduled");
        assertThat(checkIn(c, p, a).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> cancel = exchange("/v1/appointments/" + a + "/cancel", HttpMethod.POST,
                authedJsonBody(c.token, Map.of("reason", "changed mind")), Map.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(cancel.getBody().get("code")).isEqualTo("APPOINTMENT_ARRIVED");
        assertThat(cancel.getBody().get("type").toString()).endsWith("appointment-arrived");
        ResponseEntity<Map> move = exchange("/v1/appointments/" + a + "/reschedule", HttpMethod.POST,
                authedJsonBody(c.token, Map.of("newStartTime", start.plusSeconds(7200).toString())), Map.class);
        assertThat(move.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(move.getBody().get("code")).isEqualTo("APPOINTMENT_ARRIVED");
        assertThat(sql("SELECT status FROM appointments WHERE id = ?", a)).isEqualTo("arrived");
        assertThat(sql("SELECT count(*) FROM appointments WHERE doctor_id = ?", c.doctor.id())).isEqualTo("1");

        // an arrived appointment still occupies its slot (uq_appointments_doctor_slot covers arrived)
        try {
            appt(c, p, start, "scheduled");
            org.junit.jupiter.api.Assertions.fail("slot of an arrived appointment was re-offered");
        } catch (IllegalStateException e) {
            assertThat(e.getCause()).isInstanceOf(SQLException.class);
            assertThat(((SQLException) e.getCause()).getSQLState()).isEqualTo("23505");
        }
        // a still-scheduled appointment can be cancelled as before
        UUID other = appt(c, p, noonToday(30), "scheduled");
        assertThat(exchange("/v1/appointments/" + other + "/cancel", HttpMethod.POST,
                authedJsonBody(c.token, Map.of("reason", "patient called")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // AC4: transfer General -> Dermatology, last leg completes -> appointment completed
    @Test
    void ac4_transferThenCompleteLastLeg_appointmentCompleted() {
        Ctx c = ctx();
        SeededStaff derm = seedStaff(c.tenant, c.doctor.roleId(), "derm" + SEQ.incrementAndGet() + "@a.com", "+9195" + (3000000 + SEQ.get()), false);
        String generalId = (String) ((List<Map>) exchange("/v1/departments", HttpMethod.GET, authed(c.token), List.class).getBody())
                .stream().filter(d -> Boolean.TRUE.equals(d.get("isDefault"))).findFirst().orElseThrow().get("id");
        String dermId = (String) exchange("/v1/departments", HttpMethod.POST, authedJsonBody(c.token,
                Map.of("name", "Dermatology", "active", true)), Map.class).getBody().get("id");
        exchange("/v1/departments/" + dermId + "/workflow", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "templateCode", "clinic_walkin", "toggles", Map.of("vitals_enabled", false))), Map.class);
        inTenantTx(c.tenant.id(), () -> jdbc.update("UPDATE staff SET department_id = ? WHERE id = ?", UUID.fromString(dermId), derm.id()));
        exchange("/v1/departments/transfers", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "edges", List.of(Map.of("fromDepartmentId", generalId, "toDepartmentId", dermId)))), List.class);

        String p = patient(c);
        UUID a = appt(c, p, noonToday(0), "scheduled");
        String entry = (String) checkIn(c, p, a).getBody().get("id");
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult")) {
            assertThat(patchStatus(c, entry, s)).as(s).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<Map> tr = exchange("/v1/queue/" + entry + "/transfer", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "toDepartmentId", dermId, "doctorId", derm.id().toString(), "reason", "other", "reasonNote", "skin")), Map.class);
        assertThat(tr.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sql("SELECT status FROM appointments WHERE id = ?", a)).as("first leg closed, visit not over").isEqualTo("arrived");

        String leg2 = (String) ((Map) tr.getBody().get("newLeg")).get("id");
        String last = null;
        for (String s : List.of("in_consult", "billing_pending", "checkout_pending", "completed")) {
            patchStatus(c, leg2, s); // illegal steps for this flow are 400s and change nothing
            last = sql("SELECT status FROM queue_entries WHERE id = ?", UUID.fromString(leg2));
        }
        assertThat(last).isEqualTo("completed");
        assertThat(sql("SELECT status FROM appointments WHERE id = ?", a)).isEqualTo("completed");
    }

    private HttpStatus patchStatus(Ctx c, String entryId, String status) {
        return (HttpStatus) exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH,
                authedJsonBody(c.token, Map.of("status", status)), Map.class).getStatusCode();
    }

    // DB backstop: bypassing the API, a second live queue entry for one appointment is refused
    @Test
    void db_uniqueIndexRefusesSecondEntry_butLegacyDuplicateRowsAreAllowed() throws Exception {
        Ctx c = ctx();
        String p = patient(c);
        UUID a = appt(c, p, noonToday(0), "scheduled");
        assertThat(checkIn(c, p, a).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String copy = "INSERT INTO queue_entries (id, tenant_id, appointment_id, patient_id, doctor_id, department_id, queue_date, token_number, source, status, legacy_duplicate) " +
                "SELECT gen_random_uuid(), tenant_id, appointment_id, patient_id, doctor_id, department_id, queue_date, token_number + 1, source, status, ? FROM queue_entries WHERE appointment_id = ?";
        try (Connection su = superuserConnection(); PreparedStatement ps = su.prepareStatement(copy)) {
            ps.setBoolean(1, false);
            ps.setObject(2, a);
            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, ps::executeUpdate);
        }
        try (Connection su = superuserConnection(); PreparedStatement ps = su.prepareStatement(copy)) {
            ps.setBoolean(1, true);
            ps.setObject(2, a);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    // Migration on a snapshot with seeded duplicates: flagged not deleted, scheduled-with-live-entry -> arrived. Rolled back.
    @Test
    void migration_flagsDuplicatesKeepsRowsAndMarksArrived() throws Exception {
        Ctx c = ctx();
        String p = patient(c);
        UUID a = appt(c, p, noonToday(0), "scheduled");
        UUID live = UUID.fromString((String) checkIn(c, p, a).getBody().get("id"));
        UUID scheduledOnly = appt(c, p, noonToday(30), "scheduled");
        Path dir = Path.of("..", "db", "migrations");
        if (!Files.exists(dir)) {
            dir = Path.of("db", "migrations");
        }
        String v310 = Files.readString(dir.resolve("V310__acc_appointment_arrived.sql"));
        String v311 = Files.readString(dir.resolve("V311__acc_checkin_unique_indexes.sql"));
        try (Connection su = superuserConnection()) {
            su.setAutoCommit(false);
            try (var st = su.createStatement()) {
                // rewind to the pre-fix state: no unique index, appointment still 'scheduled', a second row for it
                st.execute("DROP INDEX uq_queue_entries_appointment");
                st.execute("UPDATE appointments SET status = 'scheduled', arrived_at = NULL WHERE id = '" + a + "'");
                st.execute("INSERT INTO queue_entries (id, tenant_id, appointment_id, patient_id, doctor_id, department_id, queue_date, token_number, source, status) " +
                        "SELECT gen_random_uuid(), tenant_id, appointment_id, patient_id, doctor_id, department_id, queue_date, token_number + 1, source, status FROM queue_entries WHERE id = '" + live + "'");
                st.execute(v310);
                st.execute(v311); // would fail on an unflagged duplicate
                var rs = st.executeQuery("SELECT count(*) FILTER (WHERE legacy_duplicate), count(*) FILTER (WHERE NOT legacy_duplicate), " +
                        "(SELECT legacy_duplicate FROM queue_entries WHERE id = '" + live + "') FROM queue_entries WHERE appointment_id = '" + a + "'");
                rs.next();
                assertThat(rs.getInt(1)).as("flagged duplicates").isEqualTo(1);
                assertThat(rs.getInt(2)).as("kept row").isEqualTo(1);
                assertThat(rs.getBoolean(3)).as("earliest row is the kept one").isFalse();
                rs = st.executeQuery("SELECT status FROM appointments WHERE id = '" + a + "'");
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("arrived");
                rs = st.executeQuery("SELECT status FROM appointments WHERE id = '" + scheduledOnly + "'");
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("scheduled");
            } finally {
                su.rollback();
            }
        }
    }

    // cross-tenant: another tenant's staff cannot check in this appointment (RLS), and nothing changes
    @Test
    void crossTenant_checkInOfAnotherTenantsAppointmentIs404() {
        Ctx c = ctx();
        Ctx other = ctx();
        String p = patient(c);
        UUID a = appt(c, p, noonToday(0), "scheduled");
        ResponseEntity<Map> r = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(other.token, Map.of(
                "patientId", p, "doctorId", other.doctor.id().toString(), "appointmentId", a.toString())), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(sql("SELECT status FROM appointments WHERE id = ?", a)).isEqualTo("scheduled");
    }
}

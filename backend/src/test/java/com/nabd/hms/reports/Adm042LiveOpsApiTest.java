package com.nabd.hms.reports;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADM-042: GET /v1/reports/live and PUT /v1/reports/live/thresholds against real Postgres: the doctor-late alert and its
 * 'broadcast delay' action through the existing ACC-024 endpoints, the pharmacy and booking-request alerts, thresholds that apply
 * from the next read, permissions and tenant isolation. The push on SSE is Adm042LiveOpsSseApiTest.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class Adm042LiveOpsApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 100_000);

    @Autowired ClinicClock clock;

    private record Fx(SeededTenant tenant, SeededStaff doctor, String token) {
    }

    private static String phone() {
        return "+9195" + String.format("%08d", SEQ.incrementAndGet());
    }

    private Fx fixture() {
        SeededTenant t = seedTenant();
        SeededStaff doc = seedStaff(t, seedFullAccessRole(t.id()), "a42-" + SEQ.incrementAndGet() + "@a.com", phone(), false);
        return new Fx(t, doc, loginAndGetAccessToken(doc));
    }

    private void workingHours(Fx f, UUID doctor, LocalTime from, LocalTime to) {
        inTenantTx(f.tenant.id(), () -> jdbc.update("INSERT INTO doctor_working_hours (id, tenant_id, doctor_id, day_of_week, start_time, end_time, slot_minutes, max_patients) VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), f.tenant.id(), doctor, clock.today(f.tenant.id()).getDayOfWeek().getValue() % 7, Time.valueOf(from), Time.valueOf(to), 15, 100));
    }

    /** A patient booked with the doctor later today (still to arrive), with or without messaging consent. */
    private UUID booked(Fx f, UUID doctor, int slot, boolean consent) {
        return inTenantTx(f.tenant.id(), () -> {
            UUID p = jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,?,'1990-01-01','other') RETURNING id",
                    UUID.class, f.tenant.id(), "Zzpatient Booked" + slot, phone());
            if (consent) {
                jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'messaging')", f.tenant.id(), p);
            }
            Instant start = ClinicClock.startOf(clock.today(f.tenant.id()), clock.zone(f.tenant.id())).plusSeconds(900L * (slot + 1));
            jdbc.update("INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time) VALUES (?,?,?,?,?)",
                    f.tenant.id(), p, doctor, Timestamp.from(start), Timestamp.from(start.plusSeconds(900)));
            return p;
        });
    }

    private String walkIn(Fx f, UUID doctor) {
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(f.token, Map.of("name", "Zzpatient Walkin", "phone", phone(),
                "dob", "1980-02-02", "gender", "male")), Map.class).getBody().get("id");
        ResponseEntity<Map> r = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(f.token, Map.of("patientId", patient, "doctorId", doctor.toString())), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("check-in %s", r.getBody()).isTrue();
        return (String) r.getBody().get("id");
    }

    private void template(UUID tenant, String name, int params) {
        jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, ?, ?, 'en', 'UTILITY', 'Clinic', 'Body', ?, 'approved')""", tenant, name, "m_" + UUID.randomUUID().toString().replace("-", ""), params);
    }

    private Map live(String token) {
        ResponseEntity<Map> r = exchange("/v1/reports/live", HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).as("live %s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private static Map alert(Map body, String code) {
        return ((List<Map>) body.get("alerts")).stream().filter(a -> code.equals(a.get("code"))).findFirst().orElse(null);
    }

    private static Map doctorRow(Map body, UUID id) {
        return ((List<Map>) body.get("doctors")).stream().filter(d -> id.toString().equals(d.get("id"))).findFirst().orElse(null);
    }

    private ResponseEntity<Map> putThresholds(String token, Map<String, Object> body) {
        return exchange("/v1/reports/live/thresholds", HttpMethod.PUT, authedJsonBody(token, body), Map.class);
    }

    private static Map<String, Object> thresholds(int pharmacy, int requests, int doctorLate) {
        return Map.of("pharmacyWaitMin", pharmacy, "labTatBreach", true, "requestWaitMin", requests, "doctorLateMin", doctorLate);
    }

    private UUID rxWaiting(Fx f, String age) {
        return inTenantTx(f.tenant.id(), () -> jdbc.queryForObject("INSERT INTO pharmacy_worklist_entries (tenant_id, prescription_id, patient_id, signed_at, lines, created_at) "
                + "VALUES (?, gen_random_uuid(), gen_random_uuid(), now(), '[]'::jsonb, now() - ?::interval) RETURNING id", UUID.class, f.tenant.id(), age));
    }

    private UUID request(Fx f, int slot, String age) {
        return inTenantTx(f.tenant.id(), () -> {
            UUID p = jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,?,'1990-01-01','other') RETURNING id",
                    UUID.class, f.tenant.id(), "Zzpatient Request" + slot, phone());
            Instant start = ClinicClock.startOf(clock.today(f.tenant.id()).plusDays(1), clock.zone(f.tenant.id())).plusSeconds(900L * (slot + 1));
            UUID id = jdbc.queryForObject("INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time, status, channel, created_at) "
                    + "VALUES (?,?,?,?,?,'requested','whatsapp', now() - ?::interval) RETURNING id", UUID.class, f.tenant.id(), p, f.doctor.id(),
                    Timestamp.from(start), Timestamp.from(start.plusSeconds(900)), age);
            jdbc.update("INSERT INTO booking_request_details (appointment_id, tenant_id, review_reason, sla_due_at) VALUES (?,?,'new_patient', now())", id, f.tenant.id());
            return id;
        });
    }

    // ---- AC1: a doctor running 25 minutes late ----

    @Test
    void anAnnounced25MinuteDelayAlertsWithWaitingAndToldCountsAndTheBroadcastActionGoesThroughTheDelayEndpoint() {
        Fx f = fixture();
        LocalDate today = clock.today(f.tenant.id());
        workingHours(f, f.doctor.id(), LocalTime.of(0, 0), LocalTime.of(23, 45));
        inTenantTx(f.tenant.id(), () -> {
            template(f.tenant.id(), "doctor_delay", 3);
            jdbc.update("INSERT INTO doctor_sessions (tenant_id, doctor_id, clinic_date, state, started_at) VALUES (?,?,?,'in', now())", f.tenant.id(), f.doctor.id(), Date.valueOf(today));
        });
        for (int i = 0; i < 3; i++) booked(f, f.doctor.id(), i, true);
        booked(f, f.doctor.id(), 3, false);
        walkIn(f, f.doctor.id());

        Map quiet = live(f.token);
        assertThat(alert(quiet, "doctor_late")).as("in session, nothing announced: not late").isNull();
        assertThat(doctorRow(quiet, f.doctor.id())).containsEntry("status", "in").containsEntry("waiting", 1).containsEntry("booked", 4).containsEntry("delayMin", 0);

        ResponseEntity<Map> announced = exchange("/v1/doctors/" + f.doctor.id() + "/delay", HttpMethod.POST,
                authedJsonBody(f.token, Map.of("minutes", 25, "reasonCode", "running_late", "broadcast", true)), Map.class);
        assertThat(announced.getStatusCode()).as("announce %s", announced.getBody()).isEqualTo(HttpStatus.CREATED);
        int told = (Integer) announced.getBody().get("recipients");
        assertThat(told).as("the three consenting booked patients were told (the walk-in too when registration recorded consent)").isBetween(3, 4);

        Map body = live(f.token);
        Map row = doctorRow(body, f.doctor.id());
        assertThat(row).containsEntry("name", "Test Staff").containsEntry("status", "in").containsEntry("waiting", 1).containsEntry("booked", 4)
                .containsEntry("delayMin", 25).containsEntry("told", told);
        assertThat((Integer) row.get("longestWaitMin")).isBetween(0, 1);
        Map a = alert(body, "doctor_late");
        assertThat(a).as("alerts %s", body.get("alerts")).isNotNull();
        assertThat(a).containsEntry("severity", "warning").containsEntry("minutes", 25).containsEntry("waiting", 1).containsEntry("told", told)
                .containsEntry("doctorId", f.doctor.id().toString()).containsEntry("delayId", announced.getBody().get("delayId").toString());
        assertThat((List<String>) a.get("actions")).containsExactly("broadcast_delay");
        assertThat((String) a.get("summary")).isEqualTo("Test Staff is running 25 min late: 1 waiting, 4 still to arrive, " + told + " already told.");
        assertThat(body.toString()).as("no patient on the board").doesNotContain("Zzpatient");

        // the action on an announced delay: PATCH its minutes, which broadcasts again (ACC-024; the 20-minute rule holds repeats)
        ResponseEntity<Map> changed = exchange("/v1/doctors/" + f.doctor.id() + "/delay/" + a.get("delayId"), HttpMethod.PATCH,
                authedJsonBody(f.token, Map.of("minutes", 40)), Map.class);
        assertThat(changed.getStatusCode()).as("change %s", changed.getBody()).isEqualTo(HttpStatus.OK);
        Map after = alert(live(f.token), "doctor_late");
        assertThat(after).containsEntry("minutes", 40).containsEntry("severity", "critical").containsEntry("delayId", a.get("delayId")).containsEntry("told", told);

        // cleared: the alert goes with it
        assertThat(exchange("/v1/doctors/" + f.doctor.id() + "/delay/clear", HttpMethod.POST, authedJsonBody(f.token, Map.of()), Map.class).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(alert(live(f.token), "doctor_late")).isNull();
    }

    @Test
    void aDoctorWhoHasNotStarted25MinutesAfterTheSessionStartAlertsAndBroadcastDelayAnnouncesIt() {
        LocalTime nowUtc = LocalTime.now(ZoneOffset.UTC);
        Assumptions.assumeTrue(nowUtc.isAfter(LocalTime.of(0, 40)) && nowUtc.isBefore(LocalTime.of(23, 30)), "needs a session that started 25 minutes ago on the same UTC day");
        Fx f = fixture();
        LocalTime start = nowUtc.minusMinutes(25).withNano(0);
        workingHours(f, f.doctor.id(), start, LocalTime.of(23, 59));
        inTenantTx(f.tenant.id(), () -> template(f.tenant.id(), "doctor_delay", 3));
        booked(f, f.doctor.id(), 0, true);
        walkIn(f, f.doctor.id());
        // a second doctor on whole-day leave with a booking still on the books: on the board, never late
        SeededStaff away = seedStaff(f.tenant, f.doctor.roleId(), "a42-" + SEQ.incrementAndGet() + "@a.com", phone(), false);
        workingHours(f, away.id(), start, LocalTime.of(23, 59));
        inTenantTx(f.tenant.id(), () -> jdbc.update("INSERT INTO doctor_leave (tenant_id, doctor_id, date_from, date_to) VALUES (?,?,?,?)",
                f.tenant.id(), away.id(), Date.valueOf(clock.today(f.tenant.id())), Date.valueOf(clock.today(f.tenant.id()))));
        booked(f, away.id(), 1, false);

        Map body = live(f.token);
        Map row = doctorRow(body, f.doctor.id());
        assertThat(row).containsEntry("status", "not_started").containsEntry("waiting", 1).containsEntry("booked", 1).containsEntry("told", 0);
        assertThat((Integer) row.get("delayMin")).isBetween(25, 27);
        assertThat(doctorRow(body, away.id())).containsEntry("status", "not_started").containsEntry("delayMin", 0);
        List<Map> late = ((List<Map>) body.get("alerts")).stream().filter(x -> "doctor_late".equals(x.get("code"))).toList();
        assertThat(late).as("only the doctor who is due").hasSize(1);
        Map a = late.get(0);
        assertThat(a).containsEntry("doctorId", f.doctor.id().toString()).containsEntry("waiting", 1).containsEntry("told", 0);
        assertThat(a.get("delayId")).as("nothing announced yet").isNull();
        assertThat((List<String>) a.get("actions")).containsExactly("broadcast_delay");

        // the action without an announced delay: announce it with the broadcast (ACC-024)
        ResponseEntity<Map> announced = exchange("/v1/doctors/" + f.doctor.id() + "/delay", HttpMethod.POST,
                authedJsonBody(f.token, Map.of("minutes", a.get("minutes"), "broadcast", true)), Map.class);
        assertThat(announced.getStatusCode()).as("announce %s", announced.getBody()).isEqualTo(HttpStatus.CREATED);
        int told = (Integer) announced.getBody().get("recipients");
        assertThat(told).as("the consenting booked patient was told").isBetween(1, 2);
        Map after = alert(live(f.token), "doctor_late");
        assertThat(after).containsEntry("told", told).containsEntry("delayId", announced.getBody().get("delayId").toString());

        // once the first patient is called the doctor has started: only the announced minutes count
        exchange("/v1/doctors/" + f.doctor.id() + "/delay/clear", HttpMethod.POST, authedJsonBody(f.token, Map.of()), Map.class);
        inTenantTx(f.tenant.id(), () -> jdbc.update("INSERT INTO doctor_sessions (tenant_id, doctor_id, clinic_date, state, started_at) VALUES (?,?,?,'in', now())",
                f.tenant.id(), f.doctor.id(), Date.valueOf(clock.today(f.tenant.id()))));
        Map started = live(f.token);
        assertThat(doctorRow(started, f.doctor.id())).containsEntry("status", "in").containsEntry("delayMin", 0);
        assertThat(alert(started, "doctor_late")).isNull();
    }

    // ---- AC2 + AC4: pharmacy wait over the target, and the edited target ----

    @Test
    void pharmacyWaitOverEightMinutesAlertsAndAnEditedTargetAppliesFromTheNextRead() {
        Fx f = fixture();
        assertThat(live(f.token).get("thresholds")).isEqualTo(new HashMap<>(Map.of("pharmacyWaitMin", 8, "labTatBreach", true, "requestWaitMin", 10, "doctorLateMin", 15)));
        UUID entry = rxWaiting(f, "7 minutes");
        Map under = live(f.token);
        assertThat(alert(under, "pharmacy_wait")).as("7 min is under the 8 min target").isNull();
        assertThat((Map) under.get("pharmacy")).containsEntry("open", 1).containsEntry("longestWaitMin", 7);
        assertThat(under.get("lab")).as("lab turnaround is a reserved slot").isNull();

        inTenantTx(f.tenant.id(), () -> jdbc.update("UPDATE pharmacy_worklist_entries SET created_at = now() - interval '9 minutes 5 seconds' WHERE id = ?", entry));
        Map a = alert(live(f.token), "pharmacy_wait");
        assertThat(a).isNotNull();
        assertThat(a).containsEntry("severity", "warning").containsEntry("minutes", 9).containsEntry("waiting", 1);
        assertThat((List<String>) a.get("actions")).containsExactly("open_pharmacy");
        assertThat((String) a.get("summary")).isEqualTo("Pharmacy wait is 9 min, over the 8 min target (1 open).");

        ResponseEntity<Map> put = putThresholds(f.token, Map.of("pharmacyWaitMin", 10, "labTatBreach", false, "requestWaitMin", 10, "doctorLateMin", 15));
        assertThat(put.getStatusCode()).as("put %s", put.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(put.getBody()).containsEntry("pharmacyWaitMin", 10).containsEntry("labTatBreach", false);
        Map edited = live(f.token);
        assertThat((Map) edited.get("thresholds")).containsEntry("pharmacyWaitMin", 10).containsEntry("labTatBreach", false);
        assertThat(alert(edited, "pharmacy_wait")).as("9 min is under the new 10 min target").isNull();

        inTenantTx(f.tenant.id(), () -> jdbc.update("UPDATE pharmacy_worklist_entries SET created_at = now() - interval '11 minutes 5 seconds' WHERE id = ?", entry));
        assertThat((String) alert(live(f.token), "pharmacy_wait").get("summary")).isEqualTo("Pharmacy wait is 11 min, over the 10 min target (1 open).");

        // claimed by a pharmacist: still a patient waiting; dispensed: gone
        inTenantTx(f.tenant.id(), () -> jdbc.update("UPDATE pharmacy_worklist_entries SET status = 'in_progress', claimed_by = ?, claimed_at = now() WHERE id = ?", f.doctor.id(), entry));
        assertThat(alert(live(f.token), "pharmacy_wait")).isNotNull();
        inTenantTx(f.tenant.id(), () -> jdbc.update("UPDATE pharmacy_worklist_entries SET status = 'dispensed' WHERE id = ?", entry));
        Map done = live(f.token);
        assertThat(alert(done, "pharmacy_wait")).isNull();
        assertThat((Map) done.get("pharmacy")).containsEntry("open", 0);
        assertThat(((Map) done.get("pharmacy")).get("longestWaitMin")).isNull();

        // the change is audited once, with before and after
        Map<String, Object> audit = inTenantTx(f.tenant.id(), () -> jdbc.queryForMap(
                "SELECT before->>'pharmacyWaitMin' AS b, after->>'pharmacyWaitMin' AS a, actor_id FROM audit_log WHERE tenant_id = ? AND action = 'ops_alert_thresholds.updated'", f.tenant.id()));
        assertThat(audit.get("b")).isEqualTo("8");
        assertThat(audit.get("a")).isEqualTo("10");
        assertThat(audit.get("actor_id")).isEqualTo(f.doctor.id());
    }

    // ---- AC3: booking requests waiting over 10 minutes ----

    @Test
    void twoRequestsWaitingOverTenMinutesAlertWithTheIdsTheInboxShows() {
        Fx f = fixture();
        UUID oldest = request(f, 0, "14 minutes"), second = request(f, 1, "11 minutes"), fresh = request(f, 2, "3 minutes");
        Map body = live(f.token);
        assertThat((Map) body.get("requests")).containsEntry("open", 3).containsEntry("longestWaitMin", 14);
        Map a = alert(body, "requests_waiting");
        assertThat(a).isNotNull();
        assertThat(a).containsEntry("waiting", 2).containsEntry("minutes", 14).containsEntry("severity", "warning");
        assertThat((List<String>) a.get("requestIds")).containsExactly(oldest.toString(), second.toString());
        assertThat((List<String>) a.get("actions")).containsExactly("open_requests");
        assertThat((String) a.get("summary")).isEqualTo("2 booking requests have waited over 10 min.");
        assertThat(body.toString()).doesNotContain("Zzpatient");

        // the ids are the inbox's own: the screen filters its list to them
        List<Map> inbox = (List<Map>) exchange("/v1/booking-requests", HttpMethod.GET, authed(f.token), Map.class).getBody().get("items");
        assertThat(inbox).extracting(i -> i.get("appointmentId")).contains(oldest.toString(), second.toString(), fresh.toString());

        assertThat(putThresholds(f.token, thresholds(8, 12, 15)).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map twelve = alert(live(f.token), "requests_waiting");
        assertThat((List<String>) twelve.get("requestIds")).as("only the 14 minute request is over 12").containsExactly(oldest.toString());
        assertThat((String) twelve.get("summary")).isEqualTo("1 booking request has waited over 12 min.");

        // decided: no longer waiting
        inTenantTx(f.tenant.id(), () -> jdbc.update("UPDATE appointments SET status = 'scheduled' WHERE id = ?", oldest));
        assertThat(alert(live(f.token), "requests_waiting")).isNull();
    }

    // ---- permissions, validation, tenant isolation ----

    @Test
    void readingNeedsReportsViewEditingNeedsSetupEditAndBadThresholdsAreRefused() {
        Fx f = fixture();
        String viewer = loginAndGetAccessToken(seedStaff(f.tenant, seedRole(f.tenant.id(), "Reports viewer", false,
                new ModuleGrant("reports", true, false, false, false, false, false, false),
                new ModuleGrant("setup", true, false, false, false, false, false, false)), "a42v-" + SEQ.incrementAndGet() + "@a.com", phone(), false));
        String desk = loginAndGetAccessToken(seedStaff(f.tenant, seedRole(f.tenant.id(), "Desk", false,
                new ModuleGrant("queue", true, true, true, false, false, false, false),
                new ModuleGrant("setup", true, true, true, false, false, false, false)), "a42d-" + SEQ.incrementAndGet() + "@a.com", phone(), false));

        assertThat(exchange("/v1/reports/live", HttpMethod.GET, authed(desk), Map.class).getStatusCode()).as("no reports:view").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/reports/live", HttpMethod.GET, authed(viewer), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(putThresholds(viewer, thresholds(30, 30, 30)).getStatusCode()).as("setup:view is not setup:edit").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/reports/live/thresholds", HttpMethod.PUT, jsonBody(thresholds(30, 30, 30)), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(putThresholds(f.token, thresholds(0, 10, 15)).getStatusCode()).as("0 minutes").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(putThresholds(f.token, thresholds(8, 241, 15)).getStatusCode()).as("over 240 minutes").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(putThresholds(f.token, Map.of("pharmacyWaitMin", 9, "labTatBreach", true, "requestWaitMin", 10)).getStatusCode()).as("a field missing").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map) live(f.token).get("thresholds")).as("nothing was saved").containsEntry("pharmacyWaitMin", 8).containsEntry("requestWaitMin", 10).containsEntry("doctorLateMin", 15);
        assertThat(inTenantTx(f.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM ops_alert_thresholds", Integer.class))).isZero();
    }

    @Test
    void aClinicSeesOnlyItsOwnBoardAndThresholds() {
        Fx a = fixture(), b = fixture();
        rxWaiting(a, "20 minutes");
        request(a, 0, "20 minutes");
        workingHours(a, a.doctor.id(), LocalTime.of(0, 0), LocalTime.of(23, 45));
        walkIn(a, a.doctor.id());
        assertThat(putThresholds(a.token, thresholds(5, 5, 5)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List) live(a.token).get("alerts")).isNotEmpty();

        Map other = live(b.token);
        assertThat((List) other.get("alerts")).isEmpty();
        assertThat((List) other.get("doctors")).isEmpty();
        assertThat((Map) other.get("pharmacy")).containsEntry("open", 0);
        assertThat((Map) other.get("requests")).containsEntry("open", 0);
        assertThat((Map) other.get("thresholds")).as("B keeps the defaults").containsEntry("pharmacyWaitMin", 8).containsEntry("doctorLateMin", 15);

        assertThat(exchange("/v1/reports/live?facility=" + a.tenant.id(), HttpMethod.GET, authed(b.token), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/reports/live?facility=" + b.tenant.id(), HttpMethod.GET, authed(b.token), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        // RLS on the new table: B's transaction cannot see A's row, and cannot write one for A
        assertThat(inTenantTx(b.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM ops_alert_thresholds", Integer.class))).isZero();
        assertThat(inTenantTx(a.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM ops_alert_thresholds", Integer.class))).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> inTenantTx(b.tenant.id(), () -> jdbc.update(
                "INSERT INTO ops_alert_thresholds (tenant_id, thresholds) VALUES (?, '{}'::jsonb)", a.tenant.id()))).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}

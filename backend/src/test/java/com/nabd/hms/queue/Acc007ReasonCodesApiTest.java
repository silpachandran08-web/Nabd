package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-007: one coded reason list for cancel / no_show / reschedule / override / refusal, tenant additions, and the owner report. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Acc007ReasonCodesApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(8000);
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired JwtEncoder encoder;

    private record Clinic(SeededTenant tenant, SeededStaff doctor, String token) {
    }

    private Clinic clinic() {
        SeededTenant tenant = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", ZONE.getId(), tenant.id());
        int n = SEQ.incrementAndGet();
        SeededStaff doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), "rc" + n + "@a.com", "+9195" + (4000000 + n), false);
        return new Clinic(tenant, doctor, loginAndGetAccessToken(doctor));
    }

    private UUID patient(Clinic c) {
        int n = SEQ.incrementAndGet();
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token, Map.of(
                "name", UUID.randomUUID().toString().replace("-", "").substring(0, 10) + " Zq", "phone", "+91995" + (3000000 + n),
                "dob", "1980-01-15", "gender", "male")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private void sql(String query, Object... args) {
        try (Connection su = superuserConnection(); PreparedStatement ps = su.prepareStatement(query)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID appt(Clinic c, UUID patient, LocalDate day, String time, String status, String code) {
        UUID id = UUID.randomUUID();
        Instant start = day.atTime(LocalTime.parse(time)).atZone(ZONE).toInstant();
        sql("INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, status, cancel_reason_code) VALUES (?,?,?,?,?,?,?,?)",
                id, c.tenant.id(), patient, c.doctor.id(), Timestamp.from(start), Timestamp.from(start.plusSeconds(900)), status, code);
        return id;
    }

    private UUID future(Clinic c) {
        return appt(c, patient(c), LocalDate.now(ZONE).plusDays(20 + SEQ.incrementAndGet() % 50), "10:00", "scheduled", null);
    }

    private ResponseEntity<Map> post(Clinic c, String path, Object body) {
        return exchange(path, HttpMethod.POST, authedJsonBody(c.token, body), Map.class);
    }

    private ResponseEntity<Map> get(Clinic c, String path) {
        return exchange(path, HttpMethod.GET, authed(c.token), Map.class);
    }

    private static String code(ResponseEntity<Map> r) {
        return (String) r.getBody().get("code");
    }

    private List<String> codes(ResponseEntity<Map> r) {
        return ((List<Map<String, Object>>) r.getBody().get("items")).stream().map(i -> (String) i.get("code")).toList();
    }

    // ---- AC1: cancel validation ----

    @Test
    void ac1_cancelNeedsACodeFromTheList() {
        Clinic c = clinic();
        UUID a = future(c);
        String path = "/v1/appointments/" + a + "/cancel";

        ResponseEntity<Map> none = post(c, path, Map.of());
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(none)).isEqualTo("REASON_REQUIRED");
        assertThat(post(c, path, Map.of("reason", "patient called")).getStatusCode()).as("the old free-text body alone is refused").isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> otherNoNote = post(c, path, Map.of("reasonCode", "other"));
        assertThat(otherNoNote.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(otherNoNote)).isEqualTo("NOTE_REQUIRED");
        assertThat(post(c, path, Map.of("reasonCode", "other", "note", "   ")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(code(post(c, path, Map.of("reasonCode", "nonsense")))).isEqualTo("REASON_INVALID");
        assertThat(code(post(c, path, Map.of("reasonCode", "illness", "note", "flu")))).as("free text only with other").isEqualTo("NOTE_NOT_ALLOWED");
        assertThat(code(post(c, path, Map.of("reasonCode", "other", "note", "x".repeat(201))))).isEqualTo("NOTE_TOO_LONG");
        assertThat(code(post(c, path, Map.of("reasonCode", "doctor_unavailable_x")))).isEqualTo("REASON_INVALID");
        assertThat(inTenantTx(c.tenant.id(), () -> jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, a))).isEqualTo("scheduled");

        ResponseEntity<Map> ok = post(c, path, Map.of("reasonCode", "illness"));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("status", "cancelled").containsEntry("cancelReasonCode", "illness").containsEntry("cancelReason", null);

        UUID b = future(c);
        ResponseEntity<Map> withNote = post(c, "/v1/appointments/" + b + "/cancel", Map.of("reasonCode", "other", "note", "x".repeat(200)));
        assertThat(withNote.getStatusCode()).as("boundary: 200 characters").isEqualTo(HttpStatus.OK);
        assertThat(withNote.getBody()).containsEntry("cancelReasonCode", "other");
        assertThat((String) withNote.getBody().get("cancelReason")).hasSize(200);
    }

    @Test
    void rescheduleKeepsItsOwnCode() {
        Clinic c = clinic();
        UUID a = future(c);
        LocalDate day = LocalDate.now(ZONE).plusDays(3);
        assertThat(post(c, "/v1/doctors/" + c.doctor.id() + "/working-hours", Map.of("dayOfWeek", day.getDayOfWeek().getValue() % 7,
                "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> r = post(c, "/v1/appointments/" + a + "/reschedule", Map.of("newStartTime", day.atTime(11, 0).atZone(ZONE).toInstant().toString()));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(inTenantTx(c.tenant.id(), () -> jdbc.queryForObject("SELECT cancel_reason_code FROM appointments WHERE id = ?", String.class, a))).isEqualTo("rescheduled");
    }

    // ---- the list, locales, tenant additions (AC2 server side, AC3) ----

    @Test
    void theListHasLabelsInEveryLanguageAndFallsBackToEnglish() {
        Clinic c = clinic();
        ResponseEntity<Map> en = get(c, "/v1/reason-codes?kind=cancel");
        assertThat(en.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(codes(en)).containsExactly("clinic_initiated", "cost", "doctor_unavailable", "illness", "rescheduled", "schedule_clash", "transport", "went_elsewhere", "other");
        List<Map<String, Object>> items = (List<Map<String, Object>>) en.getBody().get("items");
        assertThat(items.get(3)).containsEntry("label", "Illness").containsEntry("requiresNote", false);
        assertThat(items.get(8)).containsEntry("label", "Other").containsEntry("requiresNote", true);
        for (String lang : List.of("hi", "kn", "te", "ar")) {
            List<Map<String, Object>> l = (List<Map<String, Object>>) get(c, "/v1/reason-codes?kind=cancel&locale=" + lang).getBody().get("items");
            assertThat(l).extracting(i -> i.get("code")).isEqualTo(items.stream().map(i -> i.get("code")).toList());
            assertThat(l).extracting(i -> i.get("label")).as(lang).doesNotContainNull().isNotEqualTo(items.stream().map(i -> i.get("label")).toList());
        }
        assertThat(((List<Map<String, Object>>) get(c, "/v1/reason-codes?kind=cancel&locale=hi-IN").getBody().get("items")).get(3)).containsEntry("label", "बीमारी");
        assertThat(((List<Map<String, Object>>) get(c, "/v1/reason-codes?kind=cancel&locale=fr").getBody().get("items")).get(3)).containsEntry("label", "Illness");
        for (String kind : List.of("cancel", "no_show", "reschedule", "override", "refusal")) {
            assertThat(codes(get(c, "/v1/reason-codes?kind=" + kind))).as(kind).isNotEmpty().contains("other");
        }
        assertThat(get(c, "/v1/reason-codes?kind=bogus").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(c, "/v1/reason-codes").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac3_aTenantAddsWeatherForItselfOnlyAndPlatformCodesStayUnchanged() {
        Clinic a = clinic();
        Clinic b = clinic();
        List<String> platform = codes(get(a, "/v1/reason-codes?kind=cancel"));

        ResponseEntity<Map> added = post(a, "/v1/reason-codes", Map.of("kind", "cancel", "code", "weather", "labels", Map.of("en", "Weather", "ar", "الطقس")));
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(added.getBody()).containsEntry("code", "weather");
        assertThat(codes(get(a, "/v1/reason-codes?kind=cancel"))).containsAll(platform).contains("weather").hasSize(platform.size() + 1);
        assertThat(codes(get(b, "/v1/reason-codes?kind=cancel"))).isEqualTo(platform);
        assertThat(codes(get(a, "/v1/reason-codes?kind=no_show"))).as("only the kind it was added to").doesNotContain("weather");
        ResponseEntity<Map> ar = get(a, "/v1/reason-codes?kind=cancel&locale=ar");
        assertThat((List<Map<String, Object>>) ar.getBody().get("items")).anySatisfy(i -> assertThat(i).containsEntry("code", "weather").containsEntry("label", "الطقس"));

        assertThat(post(a, "/v1/appointments/" + future(a) + "/cancel", Map.of("reasonCode", "weather")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(code(post(b, "/v1/appointments/" + future(b) + "/cancel", Map.of("reasonCode", "weather")))).as("not on clinic B's list").isEqualTo("REASON_INVALID");

        ResponseEntity<Map> dup = post(a, "/v1/reason-codes", Map.of("kind", "cancel", "code", "weather", "labels", Map.of("en", "Weather")));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(dup)).isEqualTo("CODE_EXISTS");
        assertThat(code(post(a, "/v1/reason-codes", Map.of("kind", "cancel", "code", "illness", "labels", Map.of("en", "Sick"))))).as("a platform code cannot be redefined").isEqualTo("CODE_EXISTS");
        assertThat(codes(get(a, "/v1/reason-codes?kind=cancel&locale=en"))).containsAll(platform);
        assertThat(((List<Map<String, Object>>) get(a, "/v1/reason-codes?kind=cancel").getBody().get("items")).stream().filter(i -> "illness".equals(i.get("code"))).findFirst().get())
                .containsEntry("label", "Illness");

        assertThat(post(a, "/v1/reason-codes", Map.of("kind", "cancel", "code", "Bad Code", "labels", Map.of("en", "x"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(a, "/v1/reason-codes", Map.of("kind", "cancel", "code", "no_english", "labels", Map.of("hi", "x"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(a, "/v1/reason-codes", Map.of("kind", "nope", "code", "valid_code", "labels", Map.of("en", "x"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(inTenantTx(b.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM tenant_reason_codes", Integer.class))).as("RLS: clinic B sees none").isZero();
        assertThat(inTenantTx(a.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'reason_code.added'", Integer.class))).isEqualTo(1);
    }

    @Test
    void aTenantCanHideAPlatformReasonForItself() {
        Clinic a = clinic();
        sql("INSERT INTO tenant_reason_codes (tenant_id, kind, code, labels, hidden) VALUES (?,?,?,?::jsonb,true)", a.tenant.id(), "cancel", "cost", "{\"en\":\"Cost\"}");
        assertThat(codes(get(a, "/v1/reason-codes?kind=cancel"))).doesNotContain("cost");
        assertThat(code(post(a, "/v1/appointments/" + future(a) + "/cancel", Map.of("reasonCode", "cost")))).isEqualTo("REASON_INVALID");
    }

    @Test
    void addingNeedsSetupEditAndListingNeedsQueueView() {
        Clinic a = clinic();
        UUID noQueue = seedRole(a.tenant.id(), "None " + UUID.randomUUID().toString().substring(0, 6), false, new com.nabd.hms.common.ModuleGrant("patients", true, false, false, false, false, false, false));
        UUID viewOnly = seedRole(a.tenant.id(), "View " + UUID.randomUUID().toString().substring(0, 6), false, new com.nabd.hms.common.ModuleGrant("queue", true, false, false, false, false, false, false));
        int n = SEQ.incrementAndGet();
        Clinic none = new Clinic(a.tenant, a.doctor, loginAndGetAccessToken(seedStaff(a.tenant, noQueue, "n" + n + "@a.com", "+9195" + (4500000 + n), false)));
        Clinic view = new Clinic(a.tenant, a.doctor, loginAndGetAccessToken(seedStaff(a.tenant, viewOnly, "w" + n + "@a.com", "+9195" + (4600000 + n), false)));
        assertThat(get(none, "/v1/reason-codes?kind=cancel").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(view, "/v1/reason-codes?kind=cancel").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(view, "/v1/reason-codes", Map.of("kind", "cancel", "code", "weather", "labels", Map.of("en", "Weather"))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(view, "/v1/reports/booking-reasons?from=2026-03-02&to=2026-03-08").getStatusCode()).as("reports:view").isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aBillingOnlyDeskCanListDiscountReasons() {
        // FIN-018: discounts need a coded reason, so billing:create without queue:view must still read the list
        Clinic a = clinic();
        UUID billingOnly = seedRole(a.tenant.id(), "Bill " + UUID.randomUUID().toString().substring(0, 6), false, new com.nabd.hms.common.ModuleGrant("billing", true, true, false, false, false, false, false));
        int n = SEQ.incrementAndGet();
        Clinic desk = new Clinic(a.tenant, a.doctor, loginAndGetAccessToken(seedStaff(a.tenant, billingOnly, "b" + n + "@a.com", "+9195" + (4700000 + n), false)));
        assertThat(get(desk, "/v1/reason-codes?kind=discount").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(codes(get(desk, "/v1/reason-codes?kind=discount"))).isNotEmpty();
        assertThat(post(desk, "/v1/reason-codes", Map.of("kind", "discount", "code", "x1", "labels", Map.of("en", "X"))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theEdgeReadsTheSameListInThePatientsLanguage() {
        Clinic a = clinic();
        Clinic b = clinic();
        String edge = InternalTestTokens.mint(encoder, "patient-access-edge", a.tenant.id());
        String url = "/internal/v1/tenants/" + a.tenant.id() + "/reason-codes?kind=cancel&locale=hi";
        ResponseEntity<Map> r = exchange(url, HttpMethod.GET, authed(edge), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(codes(r)).isEqualTo(codes(get(a, "/v1/reason-codes?kind=cancel")));
        assertThat((List<Map<String, Object>>) r.getBody().get("items")).anySatisfy(i -> assertThat(i).containsEntry("code", "illness").containsEntry("label", "बीमारी"));
        assertThat(exchange("/internal/v1/tenants/" + b.tenant.id() + "/reason-codes?kind=cancel", HttpMethod.GET, authed(edge), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- AC4: data ----

    @Test
    void ac4_everyCancelledAppointmentHasACodeAndNoFreeTextIsLost() {
        Clinic c = clinic();
        UUID p = patient(c);
        // a legacy-style direct write: free text only; the database gives it the code 'other' and keeps the text as the note
        UUID legacy = appt(c, p, LocalDate.now(ZONE).plusDays(5), "09:00", "scheduled", null);
        sql("UPDATE appointments SET status = 'cancelled', cancel_reason = ? WHERE id = ?", "patient moved abroad", legacy);
        Map<String, Object> row = inTenantTx(c.tenant.id(), () -> jdbc.queryForMap("SELECT cancel_reason, cancel_reason_code FROM appointments WHERE id = ?", legacy));
        assertThat(row).containsEntry("cancel_reason", "patient moved abroad").containsEntry("cancel_reason_code", "other");
        UUID resched = appt(c, p, LocalDate.now(ZONE).plusDays(6), "09:00", "scheduled", null);
        sql("UPDATE appointments SET status = 'cancelled', cancel_reason = 'rescheduled', cancel_reason_code = 'rescheduled' WHERE id = ?", resched);

        assertThat(inTenantTx(c.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM appointments WHERE status = 'cancelled' AND cancel_reason_code IS NULL", Integer.class))).isZero();
        // the CHECK is two-way: a live appointment cannot carry a cancel code
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> sql("UPDATE appointments SET cancel_reason_code = 'illness' WHERE id = ?",
                appt(c, p, LocalDate.now(ZONE).plusDays(7), "09:00", "scheduled", null)));
        // the platform table is read-only for the API role
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('nabd_app', 'reason_code_catalogue', 'INSERT')", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT market) FROM reason_code_catalogue", Integer.class)).isEqualTo(4);
    }

    // ---- AC5: the report ----

    @Test
    void ac5_theOwnerReportMatchesTheSqlFixture() {
        Clinic c = clinic();
        Clinic other = clinic();
        UUID p = patient(c);
        LocalDate w1 = LocalDate.of(2026, 3, 2), w2 = LocalDate.of(2026, 3, 9); // Mondays
        UUID doctor2 = UUID.randomUUID();
        sql("INSERT INTO staff (id, tenant_id, role_id, email, name, mobile_phone, pin_hash, status, email_verified, mobile_verified, mfa_enabled) "
                        + "SELECT ?, tenant_id, role_id, 'd2" + SEQ.incrementAndGet() + "@a.com', 'Dr Second', '+9195" + (4900000 + SEQ.get()) + "', pin_hash, 'active', true, true, false FROM staff WHERE id = ?",
                doctor2, c.doctor.id());
        // doctor 1, week of 2 March: 2 completed + 1 no-show + 1 no-show whose patient came late (not counted) + 1 cancelled (not a booking) -> 4 bookings, 1 no-show = 25.0
        appt(c, p, w1, "09:00", "completed", null);
        appt(c, p, w1.plusDays(1), "09:00", "completed", null);
        appt(c, p, w1.plusDays(2), "09:00", "no_show", null);
        UUID late = appt(c, p, w1.plusDays(3), "09:00", "no_show", null);
        sql("UPDATE appointments SET late_arrival_queue_entry_id = ? WHERE id = ?", UUID.randomUUID(), late);
        // doctor 1, week of 9 March: 1 scheduled (last second of the range, 15 March 23:00 local) + 1 no-show -> 2 bookings, 1 no-show = 50.0
        appt(c, p, w2.plusDays(6), "23:00", "scheduled", null);
        appt(c, p, w2, "09:00", "no_show", null);
        // outside the range: before from, after to
        appt(c, p, w1.minusDays(1), "23:30", "no_show", null);
        appt(c, p, w2.plusDays(7), "09:00", "no_show", null);
        // doctor 2 (inserted directly with the doctor id)
        for (Object[] r : new Object[][] {{w1, "10:00", "no_show"}, {w1, "10:15", "completed"}, {w1, "10:30", "completed"}, {w2, "10:00", "completed"}, {w2, "10:15", "completed"},
                {w2, "10:30", "completed"}, {w2, "10:45", "declined"}, {w2, "11:00", "requested"}}) {
            Instant s = ((LocalDate) r[0]).atTime(LocalTime.parse((String) r[1])).atZone(ZONE).toInstant();
            sql("INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time, status) VALUES (?,?,?,?,?,?)",
                    c.tenant.id(), p, doctor2, Timestamp.from(s), Timestamp.from(s.plusSeconds(900)), r[2]);
        }
        // cancel reasons: illness 3, cost 2, transport 1, schedule_clash 1, other 1, went_elsewhere 1 (+ 1 outside the range)
        String[] codes = {"illness", "illness", "illness", "cost", "cost", "transport", "schedule_clash", "other", "went_elsewhere"};
        for (int i = 0; i < codes.length; i++) {
            appt(c, p, w1.plusDays(i % 5), "14:" + (i < 4 ? "00" : "15"), "cancelled", codes[i]);
        }
        appt(c, p, w1.plusDays(30), "14:00", "cancelled", "illness");
        appt(other, patient(other), w1, "09:00", "no_show", null); // another clinic: invisible

        ResponseEntity<Map> r = get(c, "/v1/reports/booking-reasons?from=2026-03-02&to=2026-03-15");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> weeks = (List<Map<String, Object>>) r.getBody().get("noShowByDoctorWeek");
        assertThat(weeks).hasSize(4);
        assertThat(weeks).extracting(m -> m.get("weekStart") + "|" + (c.doctor.id().toString().equals(m.get("doctorId")) ? "d1" : "d2") + "|" + m.get("bookings") + "|" + m.get("noShows") + "|" + m.get("noShowPct"))
                .containsExactlyInAnyOrder("2026-03-02|d1|4|1|25.0", "2026-03-09|d1|2|1|50.0", "2026-03-02|d2|3|1|33.3", "2026-03-09|d2|3|0|0.0");
        assertThat(weeks).anySatisfy(m -> assertThat(m).containsEntry("doctorName", "Dr Second"));
        List<Map<String, Object>> top = (List<Map<String, Object>>) r.getBody().get("topCancelReasons");
        assertThat(top).extracting(m -> m.get("code") + "=" + m.get("count") + "=" + m.get("label")).containsExactly(
                "illness=3=Illness", "cost=2=Cost", "other=1=Other", "schedule_clash=1=Schedule clash", "transport=1=Transport");

        ResponseEntity<Map> empty = get(other, "/v1/reports/booking-reasons?from=2026-04-01&to=2026-04-07");
        assertThat((List) empty.getBody().get("noShowByDoctorWeek")).isEmpty();
        assertThat((List) empty.getBody().get("topCancelReasons")).isEmpty();
        assertThat(get(c, "/v1/reports/booking-reasons?from=2026-03-09&to=2026-03-02").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(c, "/v1/reports/booking-reasons?from=2025-01-01&to=2026-12-31").getStatusCode()).as("more than 366 days").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(c, "/v1/reports/booking-reasons?from=2026-03-02").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

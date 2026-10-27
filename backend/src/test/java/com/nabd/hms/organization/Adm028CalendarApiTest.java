package com.nabd.hms.organization;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADM-028: holiday templates, a holiday's effect on slots and bookings, bookings to move, the calendar event and quiet hours. */
@SuppressWarnings({"rawtypes", "unchecked"})
class Adm028CalendarApiTest extends ApiTestBase {

    @Autowired ClinicClock clock;
    @Autowired NotificationPort port;

    private record Fx(SeededTenant tenant, SeededStaff doctor, String token, ZoneId zone) {
    }

    private Fx fixture() {
        SeededTenant t = seedTenant();
        String tag = UUID.randomUUID().toString().substring(0, 6);
        SeededStaff doc = seedStaff(t, seedFullAccessRole(t.id()), "h" + tag + "@a.com", "+9196" + (10000000 + (int) (Math.random() * 8999999)), false);
        String token = loginAndGetAccessToken(doc);
        for (int d = 0; d < 7; d++) {
            exchange("/v1/doctors/" + doc.id() + "/working-hours", HttpMethod.POST, authedJsonBody(token, Map.of(
                    "dayOfWeek", d, "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        }
        return new Fx(t, doc, token, clock.zone(t.id()));
    }

    private LocalDate ahead(Fx f, int days) {
        return clock.today(f.tenant.id()).plusDays(days);
    }

    private Map addHoliday(Fx f, LocalDate d) {
        ResponseEntity<Map> r = exchange("/v1/setup/holidays", HttpMethod.POST, authedJsonBody(f.token, Map.of(
                "holidayDate", d.toString(), "name", "Test day", "recurring", false)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private ResponseEntity<Map> effect(Fx f, Object id, Map<String, Object> body) {
        return exchange("/v1/setup/holidays/" + id, HttpMethod.PATCH, authedJsonBody(f.token, body), Map.class);
    }

    private List<Instant> slots(Fx f, LocalDate d) {
        List<String> raw = exchange("/v1/doctors/" + f.doctor.id() + "/availability?date=" + d, HttpMethod.GET, authed(f.token), List.class).getBody();
        return raw.stream().map(Instant::parse).toList();
    }

    private int calendarEvents(Fx f) {
        return inTenantTx(f.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'organization.calendar.changed.v1'", Integer.class));
    }

    /** Reference data is platform-owned and read-only for the API role, so a test seeds a template as superuser. */
    private String template(String market, String regionCode, int year, String source, String... dates) {
        String code = market + "-" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        var su = new org.springframework.jdbc.core.JdbcTemplate(superuserDataSource());
        for (int i = 0; i < dates.length; i++) {
            su.update("INSERT INTO holiday_templates (code, market, region_code, year, holiday_date, name, kind, source, template_name) VALUES (?,?,?,?,?::date,?,'state',?,?)",
                    code, market, regionCode, year, dates[i], "Holiday " + i, source, "Template " + regionCode);
        }
        return code;
    }

    @Test
    void applyingATemplateListsItsHolidaysWithTheirSourceAndIsIdempotent() {
        Fx f = fixture();
        String code = template("IN", "KA", 2031, "Karnataka gazette 2031 (test data)", "2031-03-04", "2031-05-06", "2031-10-12");
        List<Map> templates = exchange("/v1/setup/calendar/templates?market=IN", HttpMethod.GET, authed(f.token), List.class).getBody();
        assertThat(templates).anyMatch(t -> code.equals(t.get("code")) && Integer.valueOf(2031).equals(t.get("year")) && "Karnataka gazette 2031 (test data)".equals(t.get("source")));

        addHoliday(f, LocalDate.of(2031, 5, 6)); // the clinic already has one of the dates
        ResponseEntity<Map> first = exchange("/v1/setup/calendar/templates/" + code + "/apply", HttpMethod.POST, authedJsonBody(f.token, Map.of("year", 2031)), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("added", 2).containsEntry("skipped", 1);
        ResponseEntity<Map> again = exchange("/v1/setup/calendar/templates/" + code + "/apply", HttpMethod.POST, authedJsonBody(f.token, Map.of("year", 2031)), Map.class);
        assertThat(again.getBody()).containsEntry("added", 0).containsEntry("skipped", 3);

        List<Map> holidays = exchange("/v1/setup/holidays", HttpMethod.GET, authed(f.token), List.class).getBody();
        assertThat(holidays).hasSize(3);
        assertThat(holidays.stream().filter(h -> "Karnataka gazette 2031 (test data)".equals(h.get("source"))).count()).isEqualTo(2);
        assertThat(holidays).allMatch(h -> "closed".equals(h.get("effect")));
        assertThat(exchange("/v1/setup/calendar/templates/NOPE/apply", HttpMethod.POST, authedJsonBody(f.token, Map.of("year", 2031)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theShippedNationalDaysCanBeApplied() {
        Fx f = fixture();
        ResponseEntity<Map> r = exchange("/v1/setup/calendar/templates/IN/apply", HttpMethod.POST, authedJsonBody(f.token, Map.of("year", 2026)), Map.class);
        assertThat(r.getBody()).containsEntry("added", 3);
        List<Map> holidays = exchange("/v1/setup/holidays", HttpMethod.GET, authed(f.token), List.class).getBody();
        assertThat(holidays.stream().map(h -> h.get("holidayDate"))).contains("2026-01-26", "2026-08-15", "2026-10-02");
    }

    @Test
    void closedRemovesTheSlotsOpenRestoresThemAndShortKeepsOnlyItsWindowAndEachChangeIsPublished() {
        Fx f = fixture();
        LocalDate day = ahead(f, 5);
        assertThat(slots(f, day)).isNotEmpty();
        Map h = addHoliday(f, day);
        int events = calendarEvents(f);
        assertThat(slots(f, day)).isEmpty(); // closed by default

        ResponseEntity<Map> open = effect(f, h.get("id"), Map.of("effect", "open"));
        assertThat(open.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(open.getBody()).containsEntry("effect", "open");
        assertThat(slots(f, day)).isNotEmpty();

        ResponseEntity<Map> shortDay = effect(f, h.get("id"), Map.of("effect", "short", "hours", List.of(Map.of("from", "09:00", "to", "13:00"))));
        assertThat(shortDay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List) shortDay.getBody().get("hours")).hasSize(1);
        List<Instant> window = slots(f, day);
        assertThat(window).hasSize(16); // 09:00 .. 12:45 on a 15-minute grid
        assertThat(window.stream().map(i -> i.atZone(f.zone).toLocalTime()))
                .allMatch(t -> !t.isBefore(LocalTime.of(9, 0)) && t.isBefore(LocalTime.of(13, 0)));

        assertThat(calendarEvents(f)).isEqualTo(events + 2); // open, short
        assertThat(effect(f, h.get("id"), Map.of("effect", "closed")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(slots(f, day)).isEmpty();
        assertThat(calendarEvents(f)).isEqualTo(events + 3);
    }

    @Test
    void aShortDayRefusesABookingOutsideItsWindowAndAcceptsOneInside() {
        Fx f = fixture();
        LocalDate day = ahead(f, 3);
        Map h = addHoliday(f, day);
        effect(f, h.get("id"), Map.of("effect", "short", "hours", List.of(Map.of("from", "09:00", "to", "13:00"))));
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(f.token, Map.of("name", "Short Day", "phone", "+919400" + (100000 + (int) (Math.random() * 899999)),
                "dob", "1990-01-01", "gender", "male")), Map.class).getBody().get("id");
        Map<String, Object> outside = Map.of("patientId", patient, "doctorId", f.doctor.id().toString(), "startTime", day.atTime(14, 0).atZone(f.zone).toInstant().toString());
        ResponseEntity<Map> refused = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(f.token, outside), Map.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().get("type")).asString().contains("clinic-closed");
        Map<String, Object> inside = Map.of("patientId", patient, "doctorId", f.doctor.id().toString(), "startTime", day.atTime(10, 0).atZone(f.zone).toInstant().toString());
        assertThat(exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(f.token, inside), Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void theOwnerSeesHowManyBookingsToMoveOnAClosedDay() {
        Fx f = fixture();
        LocalDate day = ahead(f, 30);
        List<Instant> starts = new ArrayList<>();
        for (int i = 0; i < 12; i++) starts.add(ClinicClock.startOf(day, f.zone).plus(Duration.ofHours(9)).plus(Duration.ofMinutes(15L * i)));
        UUID patient = inTenantTx(f.tenant.id(), () -> jdbc.queryForObject(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,?,'1990-01-01','other') RETURNING id", UUID.class,
                f.tenant.id(), "Move Me", "+9190001" + String.format("%05d", (int) (Math.random() * 99999))));
        inTenantTx(f.tenant.id(), () -> starts.forEach(s -> jdbc.update(
                "INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time) VALUES (?,?,?,?,?)",
                f.tenant.id(), patient, f.doctor.id(), Timestamp.from(s), Timestamp.from(s.plusSeconds(900)))));
        addHoliday(f, day);
        List<Map> holidays = exchange("/v1/setup/holidays", HttpMethod.GET, authed(f.token), List.class).getBody();
        assertThat(holidays.get(0).get("bookingsToMove")).isEqualTo(12);
    }

    @Test
    void anEffectNeedsTheRightHoursAndAHolidayOfAnotherClinicIsNotFound() {
        Fx f = fixture();
        Map h = addHoliday(f, ahead(f, 9));
        Object id = h.get("id");
        assertThat(effect(f, id, Map.of("effect", "short")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(effect(f, id, Map.of("effect", "closed", "hours", List.of(Map.of("from", "09:00", "to", "13:00")))).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(effect(f, id, Map.of("effect", "short", "hours", List.of(Map.of("from", "13:00", "to", "09:00")))).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(effect(f, id, Map.of("effect", "short", "hours", List.of(Map.of("from", "09:00", "to", "12:00"), Map.of("from", "11:00", "to", "13:00")))).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(effect(f, id, Map.of("effect", "sometimes")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(effect(f, UUID.randomUUID(), Map.of("effect", "open")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Fx other = fixture();
        assertThat(effect(other, id, Map.of("effect", "open")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inTenantTx(other.tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM clinic_holidays", Integer.class))).isZero();
    }

    @Test
    void aRoleWithoutSetupEditCannotApplyOrChange() {
        Fx f = fixture();
        UUID viewer = seedRole(f.tenant.id(), "NoSetup", false, new com.nabd.hms.common.ModuleGrant("patients", true, false, false, false, false, false, false));
        SeededStaff s = seedStaff(f.tenant, viewer, "v" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", "+9196" + (10000000 + (int) (Math.random() * 8999999)), false);
        String tok = loginAndGetAccessToken(s);
        assertThat(exchange("/v1/setup/calendar/templates/IN/apply", HttpMethod.POST, authedJsonBody(tok, Map.of("year", 2026)), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/setup/calendar/templates?market=IN", HttpMethod.GET, authed(tok), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void quietHoursHoldARecallUntilTheyEndButNotADelayUpdate() {
        Fx f = fixture();
        ZoneId zone = f.zone;
        LocalTime now = clock.now().atZone(zone).toLocalTime();
        ResponseEntity<Map> put = exchange("/v1/setup/calendar/quiet-hours", HttpMethod.PUT, authedJsonBody(f.token, Map.of(
                "enabled", true, "from", now.minusHours(1).withSecond(0).withNano(0).toString(), "to", now.plusHours(2).withSecond(0).withNano(0).toString())), Map.class);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.getBody()).containsEntry("enabled", true);
        UUID p = inTenantTx(f.tenant.id(), () -> {
            jdbc.update("""
                    INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                    VALUES (?, 'recall_due', ?, 'en', 'UTILITY', 'Clinic', 'Body', 1, 'approved')""", f.tenant.id(), "m_" + UUID.randomUUID().toString().replace("-", ""));
            jdbc.update("""
                    INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                    VALUES (?, 'doctor_delay', ?, 'en', 'UTILITY', 'Clinic', 'Body', 1, 'approved')""", f.tenant.id(), "m_" + UUID.randomUUID().toString().replace("-", ""));
            UUID pid = jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,'+919000000555','1990-01-01','other') RETURNING id", UUID.class, f.tenant.id(), "Quiet Pat");
            jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'messaging')", f.tenant.id(), pid);
            return pid;
        });
        Instant before = clock.now();
        inTenantTx(f.tenant.id(), () -> {
            port.request(new NotificationRequest(NotificationPurpose.RECALL_DUE, "patient", p, p, null, "en", List.of("Quiet"), null, null, null, "quiet-recall"));
            port.request(new NotificationRequest(NotificationPurpose.DOCTOR_DELAY, "patient", p, p, null, "en", List.of("Quiet"), null, null, null, "quiet-delay"));
        });
        Instant recallNotBefore = inTenantTx(f.tenant.id(), () -> jdbc.queryForObject(
                "SELECT not_before FROM notification_requests WHERE purpose = 'recall_due'", Timestamp.class)).toInstant();
        Object delayNotBefore = inTenantTx(f.tenant.id(), () -> jdbc.queryForObject(
                "SELECT not_before FROM notification_requests WHERE purpose = 'doctor_delay'", Timestamp.class));
        assertThat(recallNotBefore).isAfter(before.plus(Duration.ofMinutes(100))).isBefore(before.plus(Duration.ofMinutes(125))); // the end of the window, about two hours away
        assertThat(delayNotBefore).isNull();

        exchange("/v1/setup/calendar/quiet-hours", HttpMethod.PUT, authedJsonBody(f.token, Map.of("enabled", false)), Map.class);
        assertThat(exchange("/v1/setup/calendar/quiet-hours", HttpMethod.GET, authed(f.token), Map.class).getBody()).containsEntry("enabled", false);
    }
}

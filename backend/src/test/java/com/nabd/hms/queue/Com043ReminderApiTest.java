package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** COM-043: T-24 h / T-2 h reminders in their windows, none after a cancel, new ones after a reschedule, the unconfirmed cascade
 * (nudge, then flagged in the exceptions inbox), and the patient's Confirm reaching the desk. */
class Com043ReminderApiTest extends ApiTestBase {

    @Autowired JwtEncoder encoder;
    @Autowired ClinicClock clinicClock;
    @Autowired TenantScope tenantScope;
    @Autowired AppointmentReminders reminders;

    private record Clinic(SeededTenant tenant, UUID doctorId, String staffToken, String edgeToken, String ref, String patientId, Instant slot) {
        String base() {
            return "/internal/v1/tenants/" + tenant.id();
        }
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static String sha256(String phone) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(phone.replaceAll("\\D", "").getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private Clinic clinic(String tag) throws Exception {
        SeededTenant tenant = seedTenant();
        SeededStaff doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), tag + "@a.com", "+9195" + String.format("%08d", Math.abs(tag.hashCode()) % 100000000), false);
        String staff = loginAndGetAccessToken(doctor);
        var zone = clinicClock.zone(tenant.id());
        // the bookings start on day+2 (at least ~34 h ahead), so moving one to "now + up to 30 h" never overlaps another
        LocalDate day = clinicClock.today(tenant.id()).plusDays(2);
        for (LocalDate d : List.of(clinicClock.today(tenant.id()), clinicClock.today(tenant.id()).plusDays(1), day)) {
            exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST, authedJsonBody(staff, Map.of(
                    "dayOfWeek", d.getDayOfWeek().getValue() % 7, "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        }
        String phone = "+9199" + String.format("%06d", Math.abs(tag.hashCode()) % 900000) + "01";
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(staff, Map.of("name", "Asha Rao", "phone", phone, "dob", "1990-01-01", "gender", "female")), Map.class);
        String patientId = (String) p.getBody().get("id");
        su().update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?, ?::uuid, 'messaging')", tenant.id(), patientId);
        for (Object[] t : new Object[][]{
                {"appt_reminder_24h", "Hello {{1}}, a reminder of your appointment tomorrow, {{2}}, at {{3}}.", List.of("Asha", "12 March", "10:30")},
                {"appt_reminder_2h", "Hello {{1}}, your appointment is today at {{2}}.", List.of("Asha", "10:30")},
                {"appt_nudge", "Hello {{1}}, please confirm your appointment on {{2}} at {{3}}.", List.of("Asha", "12 March", "10:30")}}) {
            assertThat(exchange("/v1/setup/whatsapp-templates", HttpMethod.POST, authedJsonBody(staff, Map.of("name", t[0], "language", "en",
                    "category", "UTILITY", "body", t[1], "examples", t[2])), Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        String edge = InternalTestTokens.mint(encoder, "patient-access-edge", tenant.id());
        ResponseEntity<Map> h = exchange("/internal/v1/tenants/" + tenant.id() + "/households/" + sha256(phone), HttpMethod.GET, authed(edge), Map.class);
        String ref = (String) ((List<Map<String, Object>>) h.getBody().get("members")).get(0).get("patientRef");
        return new Clinic(tenant, doctor.id(), staff, edge, ref, patientId, day.atTime(10, 0).atZone(zone).toInstant());
    }

    private UUID book(Clinic c, Instant slot) {
        ResponseEntity<Map> r = exchange(c.base() + "/appointments", HttpMethod.POST, authedJsonBody(c.edgeToken(),
                Map.of("patientRef", c.ref(), "doctorId", c.doctorId().toString(), "startTime", slot.toString())), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("appointmentId"));
    }

    /** Time travel: the appointment now starts {@code fromNow} from now and was booked a day ago. */
    private void startsIn(UUID id, Duration fromNow) {
        Instant start = Instant.now().plus(fromNow);
        su().update("UPDATE appointments SET start_time = ?, end_time = ?, created_at = now() - interval '1 day' WHERE id = ?",
                Timestamp.from(start), Timestamp.from(start.plusSeconds(900)), id);
    }

    private int sweep(Clinic c) {
        return tenantScope.run(c.tenant().id(), reminders::sweep);
    }

    private List<Map<String, Object>> requests(UUID appointmentId) {
        return su().queryForList("SELECT purpose, status, not_after, dedupe_key FROM notification_requests WHERE subject_type = 'appointment' AND subject_id = ? ORDER BY created_at",
                appointmentId);
    }

    private String state(UUID id) {
        return su().queryForObject("SELECT confirmation_state FROM appointments WHERE id = ?", String.class, id);
    }

    private ResponseEntity<Map> act(Clinic c, UUID id, String action, Map<String, Object> extra) {
        Map<String, Object> body = new java.util.HashMap<>(extra);
        body.put("patientRef", c.ref());
        return exchange(c.base() + "/appointments/" + id + "/" + action, HttpMethod.POST,
                authedJsonBodyWithHeader(c.edgeToken(), "Idempotency-Key", UUID.randomUUID().toString(), body), Map.class);
    }

    @Test
    void theDayBeforeReminderIsRequestedOnceInsideItsSixHourWindowAndNotAfterACancel() throws Exception {
        Clinic c = clinic("com043-24h");
        UUID kept = book(c, c.slot());
        UUID cancelled = book(c, c.slot().plusSeconds(3600));
        UUID later = book(c, c.slot().plusSeconds(7200));
        startsIn(kept, Duration.ofHours(23));
        startsIn(cancelled, Duration.ofMinutes(23 * 60 + 30));
        startsIn(later, Duration.ofHours(30)); // the window has not opened
        assertThat(act(c, cancelled, "cancel", Map.of("reasonCode", "schedule_clash")).getStatusCode()).isEqualTo(HttpStatus.OK);

        sweep(c);
        sweep(c); // the minute sweep never asks twice
        List<Map<String, Object>> r = requests(kept);
        assertThat(r).singleElement().satisfies(m -> {
            assertThat(m).containsEntry("purpose", "appt_reminder_24h").containsEntry("status", "requested").containsEntry("dedupe_key", "appt-t24h:" + kept);
            Instant start = su().queryForObject("SELECT start_time FROM appointments WHERE id = ?", Timestamp.class, kept).toInstant();
            assertThat(((Timestamp) m.get("not_after")).toInstant()).as("window: T-24 h + 6 h").isCloseTo(start.minus(Duration.ofHours(18)), within(Duration.ofSeconds(1)));
        });
        assertThat(requests(cancelled)).as("cancelled meanwhile: no reminder").isEmpty();
        assertThat(requests(later)).isEmpty();
    }

    @Test
    void theTwoHourReminderUsesItsFortyFiveMinuteWindowAndAnUnansweredBookingIsFlaggedForTheDesk() throws Exception {
        Clinic c = clinic("com043-2h");
        UUID id = book(c, c.slot());
        startsIn(id, Duration.ofMinutes(100));
        sweep(c);
        List<Map<String, Object>> r = requests(id);
        assertThat(r).extracting(m -> m.get("purpose")).containsExactly("appt_reminder_2h");
        Instant start = su().queryForObject("SELECT start_time FROM appointments WHERE id = ?", Timestamp.class, id).toInstant();
        assertThat(((Timestamp) r.get(0).get("not_after")).toInstant()).isCloseTo(start.minus(Duration.ofMinutes(75)), within(Duration.ofSeconds(1)));
        assertThat(state(id)).isEqualTo("unconfirmed_flagged");
        assertThat(su().queryForObject("SELECT count(*) FROM desk_exceptions WHERE ref_id = ? AND kind = 'unconfirmed_booking' AND resolved_at IS NULL",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void noAnswerByTheCutoffSendsOneNudgeThenFlagsAndTheConfirmTapReachesTheDesk() throws Exception {
        Clinic c = clinic("com043-cascade");
        UUID id = book(c, c.slot());
        startsIn(id, Duration.ofHours(23));
        sweep(c);
        assertThat(state(id)).isEqualTo("none");

        startsIn(id, Duration.ofHours(10)); // past the default 12 h cutoff, more than 2 h left
        sweep(c);
        sweep(c);
        assertThat(requests(id)).extracting(m -> m.get("purpose")).containsExactly("appt_reminder_24h", "appt_nudge");
        assertThat(state(id)).isEqualTo("nudged");

        startsIn(id, Duration.ofMinutes(100));
        sweep(c);
        assertThat(state(id)).isEqualTo("unconfirmed_flagged");
        assertThat(su().queryForObject("SELECT count(*) FROM desk_exceptions WHERE ref_id = ? AND kind = 'unconfirmed_booking' AND resolved_at IS NULL",
                Integer.class, id)).isEqualTo(1);

        long t0 = System.nanoTime();
        ResponseEntity<Map> confirmed = act(c, id, "confirm", Map.of());
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> desk = exchange("/v1/appointments/" + id, HttpMethod.GET, authed(c.staffToken()), Map.class).getBody();
        assertThat((System.nanoTime() - t0) / 1_000_000).as("tap to desk read, ms").isLessThan(2000);
        assertThat(desk.get("confirmationState")).isEqualTo("confirmed");
        assertThat(desk.get("patientConfirmedAt")).isNotNull();
        assertThat(su().queryForObject("SELECT count(*) FROM desk_exceptions WHERE ref_id = ? AND resolved_at IS NULL", Integer.class, id)).isZero();
        assertThat(su().queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'scheduling.appointment.confirmed.v1' "
                + "AND payload->>'appointmentId' = ? AND payload->>'source' = 'whatsapp_button'", Integer.class, c.tenant().id(), id.toString())).isEqualTo(1);
        sweep(c);
        assertThat(state(id)).as("confirmed stops the cascade").isEqualTo("confirmed");
    }

    @Test
    void aRescheduleVoidsTheOldRemindersAndTheNewBookingGetsItsOwn() throws Exception {
        Clinic c = clinic("com043-move");
        UUID old = book(c, c.slot());
        startsIn(old, Duration.ofHours(23));
        sweep(c);
        assertThat(requests(old)).singleElement().satisfies(m -> assertThat(m).containsEntry("status", "requested"));

        Instant newSlot = clinicClock.today(c.tenant().id()).plusDays(2).atTime(16, 0).atZone(clinicClock.zone(c.tenant().id())).toInstant();
        ResponseEntity<Map> moved = act(c, old, "reschedule", Map.of("newStartTime", newSlot.toString()));
        assertThat(moved.getStatusCode()).as("%s", moved.getBody()).isEqualTo(HttpStatus.OK);
        UUID moved1 = UUID.fromString((String) moved.getBody().get("appointmentId"));
        assertThat(requests(old)).singleElement().satisfies(m -> assertThat(m).containsEntry("status", "cancelled"));

        startsIn(moved1, Duration.ofHours(22));
        sweep(c);
        assertThat(requests(moved1)).extracting(m -> m.get("purpose")).containsExactly("appt_reminder_24h");
    }

    @Test
    void confirmingACancelledAppointmentIs409AndTheBoardShowsTheState() throws Exception {
        Clinic c = clinic("com043-409");
        UUID id = book(c, c.slot());
        UUID other = book(c, c.slot().plusSeconds(3600));
        assertThat(act(c, id, "cancel", Map.of("reasonCode", "transport")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(su().queryForObject("SELECT cancel_reason_code FROM appointments WHERE id = ?", String.class, id)).isEqualTo("transport");
        ResponseEntity<Map> late = act(c, id, "confirm", Map.of());
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getBody()).containsEntry("code", "ALREADY_CANCELLED");

        assertThat(act(c, other, "confirm", Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        String day = clinicClock.today(c.tenant().id()).plusDays(2).toString();
        String board = exchange("/v1/schedule/day?date=" + day, HttpMethod.GET, authed(c.staffToken()), String.class).getBody();
        assertThat(board).contains("\"appointmentId\":\"" + other + "\"").contains("\"confirmationState\":\"confirmed\"");
    }

    @Test
    void anotherClinicsSweepNeverTouchesThisClinic() throws Exception {
        Clinic a = clinic("com043-tenant-a");
        Clinic b = clinic("com043-tenant-b");
        UUID id = book(a, a.slot());
        startsIn(id, Duration.ofHours(23));
        sweep(b);
        assertThat(requests(id)).isEmpty();
        sweep(a);
        assertThat(requests(id)).hasSize(1);
    }
}

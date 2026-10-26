package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-026: waitlist offers only future slots, every booking path honours a live offer, offers expire on time. */
class Acc026WaitlistOffersApiTest extends ApiTestBase {

    @Autowired com.nabd.hms.common.ClinicClock clinicClock;
    @Autowired WaitlistExpiryJob expiryJob;

    private int seq = 0;

    private record Setup(SeededTenant tenant, SeededStaff doctor, String token) {
    }

    private Setup setup() {
        SeededTenant t = seedTenant();
        SeededStaff doctor = seedStaff(t, seedFullAccessRole(t.id()), "doc" + t.slug() + "@a.com", "+9197" + (10000000 + (int) (Math.random() * 89999999)), false);
        String token = loginAndGetAccessToken(doctor);
        LocalDate day = clinicClock.today(t.id()).plusDays(1);
        exchange("/v1/doctors/" + doctor.id() + "/working-hours", HttpMethod.POST, authedJsonBody(token, Map.of(
                "dayOfWeek", day.getDayOfWeek().getValue() % 7, "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        return new Setup(t, doctor, token);
    }

    private Instant slot(Setup s, int hour) {
        return clinicClock.today(s.tenant().id()).plusDays(1).atTime(hour, 0).atZone(clinicClock.zone(s.tenant().id())).toInstant();
    }

    private static int dobSeq = 0;

    private String patient(Setup s) {
        int n = dobSeq++;
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(s.token(), Map.of("name", "Waiter" + n, "phone",
                "+9197" + String.format("%08d", 10000000 + n * 7919 % 80000000), "dob", java.time.LocalDate.of(1940, 1, 1).plusDays(n * 53L).toString(), "gender", "male")), Map.class);
        assertThat(r.getStatusCode()).as("register patient: %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private String book(Setup s, String patientId, Instant start) {
        ResponseEntity<Map> r = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(s.token(), Map.of(
                "doctorId", s.doctor().id().toString(), "patientId", patientId, "startTime", start.toString())), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private String join(Setup s) {
        return (String) exchange("/v1/waitlist", HttpMethod.POST, authedJsonBody(s.token(), Map.of(
                "doctorId", s.doctor().id().toString(), "patientId", patient(s))), Map.class).getBody().get("id");
    }

    private void cancel(Setup s, String appointmentId) {
        assertThat(exchange("/v1/appointments/" + appointmentId + "/cancel", HttpMethod.POST,
                authedJsonBody(s.token(), Map.of("reason", "cancelled")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> entry(Setup s, String id) {
        List<Map<String, Object>> all = exchange("/v1/waitlist?doctorId=" + s.doctor().id(), HttpMethod.GET, authed(s.token()), List.class).getBody();
        return all.stream().filter(e -> e.get("id").equals(id)).findFirst().orElseThrow();
    }

    /** Raw row read, no lazy expiry: what the DB says without anyone opening the list. */
    private String statusInDb(Setup s, String id) {
        return inTenantTx(s.tenant().id(), () -> jdbc.queryForObject("SELECT status FROM waitlist_entries WHERE id = ?::uuid", String.class, id));
    }

    /** Moves a booked appointment to start `minutes` from now (negative = past); the booking API itself would refuse that. */
    private void moveAppointment(String appointmentId, int minutes) {
        new org.springframework.jdbc.core.JdbcTemplate(superuserDataSource()).update(
                "UPDATE appointments SET start_time = now() + make_interval(mins => ?), end_time = now() + make_interval(mins => ?) WHERE id = ?::uuid",
                minutes, minutes + 15, appointmentId);
    }

    @Test
    void aSlotInThePastOrWithinTheLeadTimeIsNotOffered() {
        Setup s = setup();
        String waiter = join(s);
        for (int minutes : new int[]{-240, 10, 25}) { // missed 10:00 cancelled at 14:00; starting too soon (< 30 min lead)
            String a = book(s, patient(s), slot(s, 5 + (minutes < 0 ? 0 : minutes < 20 ? 1 : 2)));
            moveAppointment(a, minutes);
            cancel(s, a);
            assertThat(statusInDb(s, waiter)).as("offer for slot %d min from now", minutes).isEqualTo("waiting");
        }
        String ok = book(s, patient(s), slot(s, 9));
        moveAppointment(ok, 40); // clearly beyond the lead time
        cancel(s, ok);
        assertThat(statusInDb(s, waiter)).isEqualTo("offered");
    }

    @Test
    void bookingASlotOnLiveOfferIsRefusedOnEveryPathWithTheOfferExpiry() {
        Setup s = setup();
        Instant slot = slot(s, 10);
        String a = book(s, patient(s), slot);
        String other = book(s, patient(s), slot(s, 12));
        String waiter = join(s);
        cancel(s, a);
        Map<String, Object> offered = entry(s, waiter);
        assertThat(offered.get("status")).isEqualTo("offered");
        Instant expiresAt = Instant.parse((String) offered.get("offerExpiresAt"));

        ResponseEntity<Map> desk = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(s.token(), Map.of(
                "doctorId", s.doctor().id().toString(), "patientId", patient(s), "startTime", slot.toString())), Map.class);
        assertThat(desk.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(desk.getBody()).containsEntry("code", "SLOT_ON_OFFER");
        assertThat(Instant.parse((String) desk.getBody().get("expiresAt"))).isEqualTo(expiresAt);

        ResponseEntity<Map> moved = exchange("/v1/appointments/" + other + "/reschedule", HttpMethod.POST,
                authedJsonBody(s.token(), Map.of("newStartTime", slot.toString())), Map.class);
        assertThat(moved.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(moved.getBody()).containsEntry("code", "SLOT_ON_OFFER");
        assertThat(statusInDb(s, waiter)).isEqualTo("offered");

        // a different, free slot is unaffected; and the waiter can still accept the protected one
        book(s, patient(s), slot(s, 11));
        ResponseEntity<Map> accept = exchange("/v1/waitlist/" + waiter + "/accept", HttpMethod.POST, authed(s.token()), Map.class);
        assertThat(accept.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accept.getBody()).containsEntry("status", "booked");
    }

    @Test
    void theExpiryJobExpiresAndReoffersWithoutAnyoneOpeningTheList() {
        Setup s = setup();
        String a = book(s, patient(s), slot(s, 10));
        String first = join(s);
        String second = join(s);
        cancel(s, a);
        assertThat(statusInDb(s, first)).isEqualTo("offered");

        inTenantTx(s.tenant().id(), () -> jdbc.update("UPDATE waitlist_entries SET offer_expires_at = now() - interval '1 minute' WHERE id = ?::uuid", first));
        expiryJob.runOnce();
        assertThat(statusInDb(s, first)).isEqualTo("expired");
        assertThat(statusInDb(s, second)).isEqualTo("offered");
        assertThat(inTenantTx(s.tenant().id(), () -> jdbc.queryForObject("SELECT offered_slot_start FROM waitlist_entries WHERE id = ?::uuid",
                java.sql.Timestamp.class, second)).toInstant()).isEqualTo(slot(s, 10));

        expiryJob.runOnce(); // a second pass changes nothing
        assertThat(statusInDb(s, first)).isEqualTo("expired");
        assertThat(statusInDb(s, second)).isEqualTo("offered");
    }

    @Test
    void anExpiredOfferWhoseSlotIsNowTooSoonIsNotPassedOn() {
        Setup s = setup();
        String a = book(s, patient(s), slot(s, 10));
        String first = join(s);
        String second = join(s);
        cancel(s, a);
        inTenantTx(s.tenant().id(), () -> jdbc.update("UPDATE waitlist_entries SET offer_expires_at = now() - interval '1 minute', " +
                "offered_slot_start = now() + interval '10 minutes' WHERE id = ?::uuid", first));
        expiryJob.runOnce();
        assertThat(statusInDb(s, first)).isEqualTo("expired");
        assertThat(statusInDb(s, second)).isEqualTo("waiting");
    }

    @Test
    void acceptingAfterExpiryIsGoneAndAfterTheSlotStartedIsInPast() {
        Setup s = setup();
        String a = book(s, patient(s), slot(s, 10));
        String waiter = join(s);
        cancel(s, a);
        inTenantTx(s.tenant().id(), () -> jdbc.update("UPDATE waitlist_entries SET offer_expires_at = now() - interval '1 second' WHERE id = ?::uuid", waiter));
        ResponseEntity<Map> late = exchange("/v1/waitlist/" + waiter + "/accept", HttpMethod.POST, authed(s.token()), Map.class);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(late.getBody()).containsEntry("code", "OFFER_EXPIRED");

        String b = book(s, patient(s), slot(s, 14));
        String waiter2 = join(s);
        cancel(s, b);
        assertThat(statusInDb(s, waiter2)).isEqualTo("offered");
        inTenantTx(s.tenant().id(), () -> jdbc.update("UPDATE waitlist_entries SET offered_slot_start = now() - interval '5 minutes' WHERE id = ?::uuid", waiter2));
        ResponseEntity<Map> past = exchange("/v1/waitlist/" + waiter2 + "/accept", HttpMethod.POST, authed(s.token()), Map.class);
        assertThat(past.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(past.getBody()).containsEntry("code", "IN_PAST");
    }

    @Test
    void cancelOfferAcceptCompletesWellUnderThirtySeconds() {
        Setup s = setup();
        String a = book(s, patient(s), slot(s, 10));
        String waiter = join(s);
        long t0 = System.nanoTime();
        cancel(s, a);
        assertThat(entry(s, waiter).get("status")).isEqualTo("offered");
        ResponseEntity<Map> accept = exchange("/v1/waitlist/" + waiter + "/accept", HttpMethod.POST, authed(s.token()), Map.class);
        assertThat(accept.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((System.nanoTime() - t0) / 1_000_000_000.0).isLessThan(30.0);
        assertThat(accept.getBody().get("bookedAppointmentId")).isNotNull();
    }

    @Test
    void offersAndExpiryStayInsideTheirTenant() {
        Setup a = setup();
        Setup b = setup();
        String appt = book(a, patient(a), slot(a, 10));
        String waiter = join(a);
        cancel(a, appt);
        inTenantTx(a.tenant().id(), () -> jdbc.update("UPDATE waitlist_entries SET offer_expires_at = now() - interval '1 minute' WHERE id = ?::uuid", waiter));
        assertThat(inTenantTx(b.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM waitlist_entries", Integer.class))).isZero();
        expiryJob.runOnce();
        assertThat(statusInDb(a, waiter)).isEqualTo("expired");
    }
}

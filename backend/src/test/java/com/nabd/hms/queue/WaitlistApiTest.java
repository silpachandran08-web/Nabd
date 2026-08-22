package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WaitlistApiTest extends ApiTestBase {

    private static int pgDayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue() % 7;
    }

    private static Instant nearFutureSlot() {
        Instant now = Instant.now();
        Instant candidate = now.plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        Instant capAt = LocalDate.now(ZoneOffset.UTC).atTime(23, 30).toInstant(ZoneOffset.UTC);
        return candidate.isAfter(capAt) ? now.plus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES) : candidate;
    }

    private void addWorkingHours(String token, UUID doctorId) {
        exchange("/v1/doctors/" + doctorId + "/working-hours", HttpMethod.POST, authedJsonBody(token, Map.of(
                "dayOfWeek", pgDayOfWeek(LocalDate.now(ZoneOffset.UTC)), "startTime", "00:00:00",
                "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
    }

    private static int dobYearCounter = 1970;

    private String registerPatient(String token, String name, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", (dobYearCounter++) + "-01-01", "gender", "male")), Map.class);
        return (String) resp.getBody().get("id");
    }

    private String bookAppointment(String token, UUID doctorId, String patientId, Instant start) {
        ResponseEntity<Map> resp = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", doctorId.toString(), "patientId", patientId, "startTime", start.toString())), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) resp.getBody().get("id");
    }

    @Test
    void cancellingAnAppointmentOffersItToTheOldestWaitingPatient() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc1@a.com", "+919700000001", false);
        String token = loginAndGetAccessToken(doctor);
        addWorkingHours(token, doctor.id());

        String bookedPatient = registerPatient(token, "Booked", "+919700000101");
        Instant slot = nearFutureSlot();
        String appointmentId = bookAppointment(token, doctor.id(), bookedPatient, slot);

        String waiter1 = registerPatient(token, "Waiter1", "+919700000102");
        String waiter2 = registerPatient(token, "Waiter2", "+919700000103");
        ResponseEntity<Map> join1 = exchange("/v1/waitlist", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", doctor.id().toString(), "patientId", waiter1)), Map.class);
        assertThat(join1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        exchange("/v1/waitlist", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", doctor.id().toString(), "patientId", waiter2)), Map.class);
        String waitlistId1 = (String) join1.getBody().get("id");

        exchange("/v1/appointments/" + appointmentId + "/cancel", HttpMethod.POST,
                authedJsonBody(token, Map.of("reason", "patient cancelled")), Map.class);

        ResponseEntity<List> listResp = exchange("/v1/waitlist?doctorId=" + doctor.id(), HttpMethod.GET, authed(token), List.class);
        List<Map<String, Object>> entries = listResp.getBody();
        Map<String, Object> first = entries.stream().filter(e -> e.get("id").equals(waitlistId1)).findFirst().orElseThrow();
        assertThat(first.get("status")).isEqualTo("offered");
        assertThat(first.get("offeredSlotStart")).isEqualTo(slot.toString());
        Map<String, Object> second = entries.stream().filter(e -> !e.get("id").equals(waitlistId1)).findFirst().orElseThrow();
        assertThat(second.get("status")).isEqualTo("waiting");
    }

    @Test
    void acceptingAnOfferBooksTheAppointment() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc2@a.com", "+919700000004", false);
        String token = loginAndGetAccessToken(doctor);
        addWorkingHours(token, doctor.id());

        String bookedPatient = registerPatient(token, "Booked2", "+919700000201");
        Instant slot = nearFutureSlot();
        String appointmentId = bookAppointment(token, doctor.id(), bookedPatient, slot);
        String waiter = registerPatient(token, "Waiter3", "+919700000202");
        ResponseEntity<Map> join = exchange("/v1/waitlist", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", doctor.id().toString(), "patientId", waiter)), Map.class);
        String waitlistId = (String) join.getBody().get("id");

        exchange("/v1/appointments/" + appointmentId + "/cancel", HttpMethod.POST,
                authedJsonBody(token, Map.of("reason", "cancelled")), Map.class);

        ResponseEntity<Map> acceptResp = exchange("/v1/waitlist/" + waitlistId + "/accept", HttpMethod.POST, authed(token), Map.class);
        assertThat(acceptResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(acceptResp.getBody().get("status")).isEqualTo("booked");
        String bookedAppointmentId = (String) acceptResp.getBody().get("bookedAppointmentId");
        assertThat(bookedAppointmentId).isNotNull();

        ResponseEntity<Map> apptResp = exchange("/v1/appointments/" + bookedAppointmentId, HttpMethod.GET, authed(token), Map.class);
        assertThat(apptResp.getBody().get("patientId")).isEqualTo(waiter);
        assertThat(apptResp.getBody().get("status")).isEqualTo("scheduled");

        // accepting again (double allocation) is rejected — the offer is already claimed
        ResponseEntity<Map> secondAccept = exchange("/v1/waitlist/" + waitlistId + "/accept", HttpMethod.POST, authed(token), Map.class);
        assertThat(secondAccept.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void expiredOfferIsReofferedToTheNextPersonInLine() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc3@a.com", "+919700000005", false);
        String token = loginAndGetAccessToken(doctor);
        addWorkingHours(token, doctor.id());

        String bookedPatient = registerPatient(token, "Booked3", "+919700000301");
        Instant slot = nearFutureSlot();
        String appointmentId = bookAppointment(token, doctor.id(), bookedPatient, slot);
        String waiter1 = registerPatient(token, "Waiter4", "+919700000302");
        String waiter2 = registerPatient(token, "Waiter5", "+919700000303");
        ResponseEntity<Map> join1 = exchange("/v1/waitlist", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", doctor.id().toString(), "patientId", waiter1)), Map.class);
        ResponseEntity<Map> join2 = exchange("/v1/waitlist", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", doctor.id().toString(), "patientId", waiter2)), Map.class);
        String waitlistId1 = (String) join1.getBody().get("id");
        String waitlistId2 = (String) join2.getBody().get("id");

        exchange("/v1/appointments/" + appointmentId + "/cancel", HttpMethod.POST,
                authedJsonBody(token, Map.of("reason", "cancelled")), Map.class);

        // force the first offer into the past
        inTenantTx(tenant.id(), () -> jdbc.update(
                "UPDATE waitlist_entries SET offer_expires_at = now() - interval '1 minute' WHERE id = ?",
                UUID.fromString(waitlistId1)));

        ResponseEntity<List> listResp = exchange("/v1/waitlist?doctorId=" + doctor.id(), HttpMethod.GET, authed(token), List.class);
        List<Map<String, Object>> entries = listResp.getBody();
        Map<String, Object> e1 = entries.stream().filter(e -> e.get("id").equals(waitlistId1)).findFirst().orElseThrow();
        Map<String, Object> e2 = entries.stream().filter(e -> e.get("id").equals(waitlistId2)).findFirst().orElseThrow();
        assertThat(e1.get("status")).isEqualTo("expired");
        assertThat(e2.get("status")).isEqualTo("offered");
        assertThat(e2.get("offeredSlotStart")).isEqualTo(slot.toString());

        // the expired holder can no longer accept
        ResponseEntity<Map> lateAccept = exchange("/v1/waitlist/" + waitlistId1 + "/accept", HttpMethod.POST, authed(token), Map.class);
        assertThat(lateAccept.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void joiningTheSameDoctorsWaitlistTwiceIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc4@a.com", "+919700000006", false);
        String token = loginAndGetAccessToken(doctor);
        String waiter = registerPatient(token, "Waiter6", "+919700000401");

        exchange("/v1/waitlist", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", doctor.id().toString(), "patientId", waiter)), Map.class);
        ResponseEntity<Map> resp = exchange("/v1/waitlist", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", doctor.id().toString(), "patientId", waiter)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().get("type")).asString().contains("already-waitlisted");
    }
}

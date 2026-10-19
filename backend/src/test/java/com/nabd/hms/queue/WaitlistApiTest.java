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

    /** REC-028: the front desk books a waitlisted patient into a slot it chose; conflicts are clear 409s. */
    @Test
    void acceptWithAChosenSlotBooksItAndRefusesTakenOrOfferedSlots() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "doc28@a.com", "+919700000028", false);
        String token = loginAndGetAccessToken(doctor);
        addWorkingHours(token, doctor.id());
        Instant base = nearFutureSlot();

        String waiterA = registerPatient(token, "WaiterA", "+919700002801");
        String waiterB = registerPatient(token, "WaiterB", "+919700002802");
        String entryA = (String) exchange("/v1/waitlist", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", doctor.id().toString(), "patientId", waiterA)), Map.class).getBody().get("id");
        String entryB = (String) exchange("/v1/waitlist", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", doctor.id().toString(), "patientId", waiterB)), Map.class).getBody().get("id");

        // a slot already booked by someone else: 409 slot-taken, entry stays waiting
        String other = registerPatient(token, "Taken", "+919700002803");
        bookAppointment(token, doctor.id(), other, base);
        ResponseEntity<Map> taken = exchange("/v1/waitlist/" + entryA + "/accept", HttpMethod.POST,
                authedJsonBody(token, Map.of("slotStart", base.toString())), Map.class);
        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(taken.getBody().get("type")).asString().contains("slot-taken");

        // a slot in the past: 400
        ResponseEntity<Map> past = exchange("/v1/waitlist/" + entryA + "/accept", HttpMethod.POST,
                authedJsonBody(token, Map.of("slotStart", Instant.now().minus(1, ChronoUnit.HOURS).toString())), Map.class);
        assertThat(past.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // a free chosen slot for a waiting (never offered) entry: booked
        Instant chosen = base.plus(30, ChronoUnit.MINUTES);
        ResponseEntity<Map> ok = exchange("/v1/waitlist/" + entryA + "/accept", HttpMethod.POST,
                authedJsonBody(token, Map.of("slotStart", chosen.toString())), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("status")).isEqualTo("booked");
        String apptId = (String) ok.getBody().get("bookedAppointmentId");
        Map<String, Object> appt = exchange("/v1/appointments/" + apptId, HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat(appt).containsEntry("patientId", waiterA).containsEntry("startTime", chosen.toString())
                .containsEntry("status", "scheduled");
        Integer audits = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'waitlist.booked_chosen_slot' AND entity_id = ?::uuid "
                        + "AND after::text NOT LIKE '%WaiterA%'", Integer.class, entryA));
        assertThat(audits).isEqualTo(1);

        // the same entry again: already booked -> 409
        ResponseEntity<Map> again = exchange("/v1/waitlist/" + entryA + "/accept", HttpMethod.POST,
                authedJsonBody(token, Map.of("slotStart", base.plus(45, ChronoUnit.MINUTES).toString())), Map.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("type")).asString().contains("waitlist-entry-closed");

        // a freed slot on live offer to B cannot be grabbed for a third waiter: 409 slot-on-offer
        String waiterC = registerPatient(token, "WaiterC", "+919700002804");
        String entryC = (String) exchange("/v1/waitlist", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", doctor.id().toString(), "patientId", waiterC)), Map.class).getBody().get("id");
        Instant freed = base.plus(60, ChronoUnit.MINUTES);
        String toCancel = bookAppointment(token, doctor.id(), other, freed);
        exchange("/v1/appointments/" + toCancel + "/cancel", HttpMethod.POST,
                authedJsonBody(token, Map.of("reason", "patient cancelled")), Map.class);
        ResponseEntity<Map> onOffer = exchange("/v1/waitlist/" + entryC + "/accept", HttpMethod.POST,
                authedJsonBody(token, Map.of("slotStart", freed.toString())), Map.class);
        assertThat(onOffer.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(onOffer.getBody().get("type")).asString().contains("slot-on-offer");

        // B accepting its own offered slot with slotStart = the offer is the plain accept
        ResponseEntity<Map> own = exchange("/v1/waitlist/" + entryB + "/accept", HttpMethod.POST,
                authedJsonBody(token, Map.of("slotStart", freed.toString())), Map.class);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(own.getBody().get("status")).isEqualTo("booked");
        assertThat(own.getBody().get("offeredSlotStart")).isEqualTo(freed.toString());

        // tenant isolation: another clinic cannot accept (or even see) this entry
        SeededTenant otherTenant = seedTenant();
        SeededStaff otherStaff = seedStaff(otherTenant, seedFullAccessRole(otherTenant.id()), "doc28b@a.com", "+919700000029", false);
        String otherToken = loginAndGetAccessToken(otherStaff);
        ResponseEntity<Map> foreign = exchange("/v1/waitlist/" + entryC + "/accept", HttpMethod.POST,
                authedJsonBody(otherToken, Map.of("slotStart", base.plus(90, ChronoUnit.MINUTES).toString())), Map.class);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<List> foreignList = exchange("/v1/waitlist?doctorId=" + doctor.id(), HttpMethod.GET, authed(otherToken), List.class);
        assertThat(foreignList.getBody()).isEmpty();
    }
}

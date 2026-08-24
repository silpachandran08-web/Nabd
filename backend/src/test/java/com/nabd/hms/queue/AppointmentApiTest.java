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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AppointmentApiTest extends ApiTestBase {

    private static int pgDayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue() % 7;
    }

    /** A slot start a couple hours out, always inside today's 00:00-23:45 working-hours window used below. */
    private static Instant nearFutureSlot() {
        Instant now = Instant.now();
        Instant candidate = now.plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        Instant capAt = LocalDate.now(ZoneOffset.UTC).atTime(23, 30).toInstant(ZoneOffset.UTC);
        return candidate.isAfter(capAt) ? now.plus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES) : candidate;
    }

    private String addWorkingHours(String token, UUID doctorId, Integer maxPatients) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("dayOfWeek", pgDayOfWeek(LocalDate.now(ZoneOffset.UTC)),
                "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15));
        if (maxPatients != null) {
            body.put("maxPatients", maxPatients);
        }
        ResponseEntity<Map> resp = exchange("/v1/doctors/" + doctorId + "/working-hours", HttpMethod.POST,
                authedJsonBody(token, body), Map.class);
        return (String) resp.getBody().get("id");
    }

    private String registerPatient(String token, String name, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "male")), Map.class);
        return (String) resp.getBody().get("id");
    }

    @Test
    void bookAppointmentSucceeds() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919500000001", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);
        String patientId = registerPatient(token, "P1", "+919999900001");

        ResponseEntity<Map> resp = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", staff.id().toString(), "patientId", patientId, "startTime", nearFutureSlot().toString())),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("status")).isEqualTo("scheduled");
    }

    @Test
    void doubleBookingSameSlotConflicts() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner2@a.com", "+919500000002", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);
        String patientA = registerPatient(token, "P2", "+919999900002");
        String patientB = registerPatient(token, "P3", "+919999900003");
        String slot = nearFutureSlot().toString();

        ResponseEntity<Map> first = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", staff.id().toString(), "patientId", patientA, "startTime", slot)), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> second = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", staff.id().toString(), "patientId", patientB, "startTime", slot)), Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("type")).asString().contains("slot-unavailable");
    }

    @Test
    void bookingBeyondSessionCapacityIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner3@a.com", "+919500000003", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), 1);
        String patientA = registerPatient(token, "P4", "+919999900004");
        String patientB = registerPatient(token, "P5", "+919999900005");
        Instant slotA = nearFutureSlot();
        Instant slotB = slotA.plus(30, ChronoUnit.MINUTES); // different time, same capped session

        ResponseEntity<Map> first = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", staff.id().toString(), "patientId", patientA, "startTime", slotA.toString())), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> second = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", staff.id().toString(), "patientId", patientB, "startTime", slotB.toString())), Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("type")).asString().contains("session-full");
    }

    @Test
    void getReturns404ForUnknownAppointment() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner4@a.com", "+919500000004", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/appointments/" + UUID.randomUUID(), HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listReturnsBookedAppointments() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner5@a.com", "+919500000005", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);
        String patientId = registerPatient(token, "P6", "+919999900006");
        exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", staff.id().toString(), "patientId", patientId, "startTime", nearFutureSlot().toString())), Map.class);

        ResponseEntity<Map> resp = exchange("/v1/appointments?doctorId=" + staff.id(), HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((java.util.List<?>) resp.getBody().get("data")).isNotEmpty();
    }

    @Test
    void cancelAppointmentSucceeds() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner6@a.com", "+919500000006", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);
        String patientId = registerPatient(token, "P7", "+919999900007");
        ResponseEntity<Map> booked = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", staff.id().toString(), "patientId", patientId, "startTime", nearFutureSlot().toString())), Map.class);
        String id = (String) booked.getBody().get("id");

        ResponseEntity<Map> resp = exchange("/v1/appointments/" + id + "/cancel", HttpMethod.POST,
                authedJsonBody(token, Map.of("reason", "patient request")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("cancelled");

        // cancelling an already-cancelled appointment is no longer "scheduled" -> 404
        ResponseEntity<Map> again = exchange("/v1/appointments/" + id + "/cancel", HttpMethod.POST,
                authedJsonBody(token, Map.of("reason", "patient request")), Map.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rescheduleMovesAppointmentToNewSlot() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner7@a.com", "+919500000007", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);
        String patientId = registerPatient(token, "P8", "+919999900008");
        Instant original = nearFutureSlot();
        ResponseEntity<Map> booked = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "doctorId", staff.id().toString(), "patientId", patientId, "startTime", original.toString())), Map.class);
        String id = (String) booked.getBody().get("id");
        Instant newTime = original.plus(1, ChronoUnit.HOURS);

        ResponseEntity<Map> resp = exchange("/v1/appointments/" + id + "/reschedule", HttpMethod.POST,
                authedJsonBody(token, Map.of("newStartTime", newTime.toString())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("scheduled");
        assertThat(resp.getBody().get("id")).isNotEqualTo(id);
    }

    // ---- NB-116: follow-up callback list ----

    @Test
    void callbackListSurfacesMissedFollowUpsButNotOrdinaryOrRecentAppointments() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner9@a.com", "+919500000009", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);

        String noShowFollowUp = registerPatient(token, "Zephyr", "+919999900010");
        String recentFollowUp = registerPatient(token, "Quokka", "+919999900011");
        String ordinaryOldNoShow = registerPatient(token, "Wombat", "+919999900012");

        UUID noShowFollowUpAppt = UUID.randomUUID();
        UUID recentFollowUpAppt = UUID.randomUUID();
        UUID ordinaryNoShowAppt = UUID.randomUUID();
        Instant longAgo = Instant.now().minus(20, ChronoUnit.DAYS);
        inTenantTx(tenant.id(), () -> {
            jdbc.update("INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, status, is_follow_up) " +
                            "VALUES (?,?,?,?,?,?,'no_show',true)",
                    noShowFollowUpAppt, tenant.id(), UUID.fromString(noShowFollowUp), staff.id(), java.sql.Timestamp.from(longAgo),
                    java.sql.Timestamp.from(longAgo.plus(15, ChronoUnit.MINUTES)));
            jdbc.update("INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, status, is_follow_up) " +
                            "VALUES (?,?,?,?,?,?,'scheduled',true)",
                    recentFollowUpAppt, tenant.id(), UUID.fromString(recentFollowUp), staff.id(),
                    java.sql.Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)),
                    java.sql.Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS).plus(15, ChronoUnit.MINUTES)));
            jdbc.update("INSERT INTO appointments (id, tenant_id, patient_id, doctor_id, start_time, end_time, status, is_follow_up) " +
                            "VALUES (?,?,?,?,?,?,'no_show',false)",
                    ordinaryNoShowAppt, tenant.id(), UUID.fromString(ordinaryOldNoShow), staff.id(), java.sql.Timestamp.from(longAgo),
                    java.sql.Timestamp.from(longAgo.plus(15, ChronoUnit.MINUTES)));
        });

        ResponseEntity<java.util.List> resp = exchange("/v1/appointments/callback-list", HttpMethod.GET, authed(token), java.util.List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        java.util.List<?> body = resp.getBody();
        assertThat(body).hasSize(1);
        Map<?, ?> entry = (Map<?, ?>) body.get(0);
        assertThat(entry.get("patientName")).isEqualTo("Zephyr");
    }
}

package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueueApiTest extends ApiTestBase {

    private static int pgDayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue() % 7;
    }

    private void addWorkingHours(String token, UUID doctorId, Integer maxPatients) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("dayOfWeek", pgDayOfWeek(LocalDate.now(ZoneOffset.UTC)),
                "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15));
        if (maxPatients != null) {
            body.put("maxPatients", maxPatients);
        }
        exchange("/v1/doctors/" + doctorId + "/working-hours", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private String registerPatient(String token, String name, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "male")), Map.class);
        return (String) resp.getBody().get("id");
    }

    /** Distinct dob per call — several same-named/similarly-named patients in one tenant with the
     * same dob trip NB-060's fuzzy-duplicate detection and return a DuplicateCandidatesResponse
     * (no "id" field) instead of registering. */
    private static int dobYearCounter = 1970;

    private String registerPatientWithDob(String token, String name, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", (dobYearCounter++) + "-01-01", "gender", "male")), Map.class);
        return (String) resp.getBody().get("id");
    }

    @Test
    void walkInCheckInAssignsToken() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919600000001", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);
        String patientId = registerPatient(token, "Q1", "+919999910001");

        ResponseEntity<Map> resp = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", staff.id().toString())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("status")).isEqualTo("checked_in");
        assertThat(((Number) resp.getBody().get("tokenNumber")).intValue()).isGreaterThan(0);
        assertThat(resp.getBody().get("createdAt")).isNotNull(); // NB-095: arrivals board needs this for the "Wait" column
        assertThat(resp.getBody().get("source")).isEqualTo("walk_in"); // NB-079: default when omitted
    }

    @Test
    void checkInCapturesAnExplicitSourceAndRejectsAnUnknownOne() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner1b@a.com", "+919600000011", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);
        String patientId = registerPatient(token, "Q1b", "+919999910011");

        ResponseEntity<Map> resp = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", staff.id().toString(), "source", "referral")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("source")).isEqualTo("referral");

        // a distinct dob avoids NB-060's fuzzy duplicate-candidate match against "Q1b" registered above
        ResponseEntity<Map> reg2 = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Q1c", "phone", "+919999910012", "dob", "1975-06-15", "gender", "male")), Map.class);
        String patientId2 = (String) reg2.getBody().get("id");
        ResponseEntity<Map> rejected = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId2, "doctorId", staff.id().toString(), "source", "billboard")), Map.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); // not one of the enforced single-axis values
    }

    @Test
    void walkInCheckInBeyondCapacityIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner2@a.com", "+919600000002", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), 1);
        String patientA = registerPatient(token, "Q2", "+919999910002");
        String patientB = registerPatient(token, "Q3", "+919999910003");

        ResponseEntity<Map> first = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientA, "doctorId", staff.id().toString())), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> second = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientB, "doctorId", staff.id().toString())), Map.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("type")).asString().contains("session-full");
    }

    @Test
    void checkInWithMismatchedAppointmentFails() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner3@a.com", "+919600000003", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "Q4", "+919999910004");

        ResponseEntity<Map> resp = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "appointmentId", UUID.randomUUID().toString(), "patientId", patientId, "doctorId", staff.id().toString())),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listReturnsCheckedInEntries() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner4@a.com", "+919600000004", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);
        String patientId = registerPatient(token, "Q5", "+919999910005");
        exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", staff.id().toString())), Map.class);

        ResponseEntity<java.util.List> resp = exchange("/v1/queue?doctorId=" + staff.id(), HttpMethod.GET, authed(token),
                java.util.List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void legalTransitionSucceedsAndIllegalTransitionIsBlocked() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner5@a.com", "+919600000005", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);
        String patientId = registerPatient(token, "Q6", "+919999910006");
        ResponseEntity<Map> checkin = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", staff.id().toString())), Map.class);
        String entryId = (String) checkin.getBody().get("id");

        ResponseEntity<Map> illegal = exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "completed")), Map.class);
        assertThat(illegal.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(illegal.getBody().get("type")).asString().contains("illegal-transition");

        ResponseEntity<Map> legal = exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "waiting")), Map.class);
        assertThat(legal.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(legal.getBody().get("status")).isEqualTo("waiting");
    }

    @Test
    void reorderSetsPriority() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner6@a.com", "+919600000006", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);
        String patientId = registerPatient(token, "Q7", "+919999910007");
        ResponseEntity<Map> checkin = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", staff.id().toString())), Map.class);
        String entryId = (String) checkin.getBody().get("id");

        ResponseEntity<Map> resp = exchange("/v1/queue/" + entryId + "/reorder", HttpMethod.POST, authedJsonBody(token, Map.of(
                "priority", true, "reason", "elderly patient")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("priority")).isEqualTo(true);
        assertThat(resp.getBody().get("priorityReason")).isEqualTo("elderly patient");
    }

    // NB-101: estimate must land within +/-25% of the true historical average x patients ahead.
    @Test
    void waitEstimateIsWithin25PercentOfHistoricalAverage() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "owner7@a.com", "+919600000007", false);
        String token = loginAndGetAccessToken(doctor);

        // ten past visits alternating 10 and 20 minutes check-in-to-invoice -> true average 15 min
        for (int i = 0; i < 10; i++) {
            int minutes = (i % 2 == 0) ? 10 : 20;
            String patientId = registerPatientWithDob(token, "Hist" + i, "+91960000" + (1100 + i));
            seedCompletedVisit(tenant.id(), doctor.id(), patientId, minutes);
        }

        // two patients currently ahead in today's queue
        String p1 = registerPatientWithDob(token, "Ahead1", "+919600001200");
        String p2 = registerPatientWithDob(token, "Ahead2", "+919600001201");
        exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", p1, "doctorId", doctor.id().toString())), Map.class);
        exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", p2, "doctorId", doctor.id().toString())), Map.class);

        ResponseEntity<Map> resp = exchange("/v1/queue/wait-estimate?doctorId=" + doctor.id(), HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("patientsAhead")).isEqualTo(2);
        assertThat(resp.getBody().get("basedOnHistory")).isEqualTo(true);
        int estimated = ((Number) resp.getBody().get("estimatedMinutes")).intValue();
        int trueExpected = 30; // 15 min average x 2 patients ahead
        assertThat(estimated).isBetween((int) (trueExpected * 0.75), (int) (trueExpected * 1.25));
    }

    @Test
    void waitEstimateFallsBackToADefaultWithNoHistory() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doctor = seedStaff(tenant, roleId, "owner8@a.com", "+919600000008", false);
        String token = loginAndGetAccessToken(doctor);

        ResponseEntity<Map> resp = exchange("/v1/queue/wait-estimate?doctorId=" + doctor.id(), HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("patientsAhead")).isEqualTo(0);
        assertThat(resp.getBody().get("basedOnHistory")).isEqualTo(false);
        assertThat(resp.getBody().get("estimatedMinutes")).isEqualTo(0);
    }

    /** Directly seeds a completed, billed visit whose invoice landed exactly `minutesLater` after check-in. */
    private void seedCompletedVisit(UUID tenantId, UUID doctorId, String patientId, int minutesLater) {
        UUID queueEntryId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        inTenantTx(tenantId, () -> {
            jdbc.update("INSERT INTO queue_entries (id, tenant_id, patient_id, doctor_id, queue_date, token_number, status, created_at) " +
                            "VALUES (?,?,?,?,CURRENT_DATE - INTERVAL '1 day', " +
                            "(SELECT COALESCE(MAX(token_number),0)+1 FROM queue_entries WHERE doctor_id = ?), 'completed', now() - INTERVAL '1 day')",
                    queueEntryId, tenantId, UUID.fromString(patientId), doctorId, doctorId);
            jdbc.update("INSERT INTO invoices (id, tenant_id, queue_entry_id, patient_id, doctor_id, subtotal, tax, total, created_by, created_at) " +
                            "VALUES (?,?,?,?,?,100,0,100,?, (SELECT created_at FROM queue_entries WHERE id = ?) + (? || ' minutes')::interval)",
                    invoiceId, tenantId, queueEntryId, UUID.fromString(patientId), doctorId, doctorId, queueEntryId, minutesLater);
            return null;
        });
    }
}

package com.nabd.hms.queue;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
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

    /** seedTenant() seeds one default department per tenant (mirrors V37's migration backfill) —
     * the fallback check-in target for a doctor nobody's assigned a department to yet. */
    private Map<String, Object> defaultDepartment(String token) {
        ResponseEntity<List> listResp = exchange("/v1/departments", HttpMethod.GET, authed(token), List.class);
        List<Map<String, Object>> departments = listResp.getBody();
        return departments.stream().filter(d -> Boolean.TRUE.equals(d.get("isDefault"))).findFirst().orElseThrow();
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
    void waitingToVitalsPendingIsLegalWhenDepartmentRequiresVitals() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner6@a.com", "+919600000007", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);
        String patientId = registerPatient(token, "Q7", "+919999910007");
        // staff has no department_id assigned -> falls back to the tenant's seeded default (requiresVitals=true)
        ResponseEntity<Map> checkin = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", staff.id().toString())), Map.class);
        String entryId = (String) checkin.getBody().get("id");
        exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", "waiting")), Map.class);

        ResponseEntity<Map> toVitalsPending = exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "vitals_pending")), Map.class);
        assertThat(toVitalsPending.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> skipToConsult = exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "in_consult")), Map.class);
        assertThat(skipToConsult.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void waitingSkipsStraightToInConsultWhenDepartmentDoesNotRequireVitals() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner7@a.com", "+919600000008", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);

        String dentalId = (String) exchange("/v1/departments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Dental", "requiresVitals", false, "active", true)), Map.class).getBody().get("id");
        UUID dentalUuid = UUID.fromString(dentalId);
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE staff SET department_id = ? WHERE id = ?", dentalUuid, staff.id()));

        String patientId = registerPatient(token, "Q8", "+919999910008");
        ResponseEntity<Map> checkin = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", staff.id().toString())), Map.class);
        String entryId = (String) checkin.getBody().get("id");
        assertThat(checkin.getBody().get("departmentId")).isEqualTo(dentalId);

        exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", "waiting")), Map.class);

        ResponseEntity<Map> vitalsBlocked = exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "vitals_pending")), Map.class);
        assertThat(vitalsBlocked.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> straightToConsult = exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("status", "in_consult")), Map.class);
        assertThat(straightToConsult.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void transferOpensANewLegInTheTargetDepartmentAndClosesTheCurrentOne() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff generalDoctor = seedStaff(tenant, roleId, "owner8@a.com", "+919600000009", false);
        SeededStaff dentist = seedStaff(tenant, roleId, "dentist2@a.com", "+919600000010", false);
        String token = loginAndGetAccessToken(generalDoctor);
        addWorkingHours(token, generalDoctor.id(), null);
        addWorkingHours(token, dentist.id(), null);

        String generalId = (String) defaultDepartment(token).get("id");
        String dentalId = (String) exchange("/v1/departments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Dental", "requiresVitals", false, "active", true)), Map.class).getBody().get("id");
        UUID dentalUuid = UUID.fromString(dentalId);
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE staff SET department_id = ? WHERE id = ?", dentalUuid, dentist.id()));
        exchange("/v1/departments/transfers", HttpMethod.POST, authedJsonBody(token, Map.of(
                "edges", List.of(Map.of("fromDepartmentId", generalId, "toDepartmentId", dentalId)))), List.class);

        String patientId = registerPatient(token, "Q9", "+919999910009");
        ResponseEntity<Map> checkin = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", generalDoctor.id().toString())), Map.class);
        String entryId = (String) checkin.getBody().get("id");
        exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", "waiting")), Map.class);
        exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", "vitals_pending")), Map.class);
        exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", "vitals_done")), Map.class);
        exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", "in_consult")), Map.class);

        ResponseEntity<Map> transfer = exchange("/v1/queue/" + entryId + "/transfer", HttpMethod.POST, authedJsonBody(token, Map.of(
                "toDepartmentId", dentalId, "doctorId", dentist.id().toString())), Map.class);
        assertThat(transfer.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> closedLeg = (Map<String, Object>) transfer.getBody().get("closedLeg");
        Map<String, Object> newLeg = (Map<String, Object>) transfer.getBody().get("newLeg");
        assertThat(closedLeg.get("status")).isEqualTo("transferred_out");
        assertThat(newLeg.get("status")).isEqualTo("waiting");
        assertThat(newLeg.get("departmentId")).isEqualTo(dentalId);
        assertThat(newLeg.get("parentQueueEntryId")).isEqualTo(entryId);
        assertThat(newLeg.get("doctorId")).isEqualTo(dentist.id().toString());
    }

    @Test
    void transferIsBlockedWhenNoTransferEdgeExists() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner9@a.com", "+919600000011", false);
        SeededStaff dentist = seedStaff(tenant, roleId, "dentist3@a.com", "+919600000012", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);

        String dentalId = (String) exchange("/v1/departments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Dental", "requiresVitals", false, "active", true)), Map.class).getBody().get("id");
        // deliberately no transfer edge created between the caller's department and Dental

        String patientId = registerPatient(token, "Q10", "+919999910013");
        ResponseEntity<Map> checkin = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", staff.id().toString())), Map.class);
        String entryId = (String) checkin.getBody().get("id");
        exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", "waiting")), Map.class);
        exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", "vitals_pending")), Map.class);
        exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", "vitals_done")), Map.class);
        exchange("/v1/queue/" + entryId + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", "in_consult")), Map.class);

        ResponseEntity<Map> transfer = exchange("/v1/queue/" + entryId + "/transfer", HttpMethod.POST, authedJsonBody(token, Map.of(
                "toDepartmentId", dentalId, "doctorId", dentist.id().toString())), Map.class);
        assertThat(transfer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(transfer.getBody().get("type")).asString().contains("transfer-not-allowed");
    }

    @Test
    void transferIsBlockedWhenEntryIsNotInConsult() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner10@a.com", "+919600000014", false);
        String token = loginAndGetAccessToken(staff);
        addWorkingHours(token, staff.id(), null);

        String generalId = (String) defaultDepartment(token).get("id");
        String dentalId = (String) exchange("/v1/departments", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Dental", "requiresVitals", false, "active", true)), Map.class).getBody().get("id");
        exchange("/v1/departments/transfers", HttpMethod.POST, authedJsonBody(token, Map.of(
                "edges", List.of(Map.of("fromDepartmentId", generalId, "toDepartmentId", dentalId)))), List.class);

        String patientId = registerPatient(token, "Q11", "+919999910015");
        ResponseEntity<Map> checkin = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", staff.id().toString())), Map.class);
        String entryId = (String) checkin.getBody().get("id");
        // still "checked_in" — never reached in_consult

        ResponseEntity<Map> transfer = exchange("/v1/queue/" + entryId + "/transfer", HttpMethod.POST, authedJsonBody(token, Map.of(
                "toDepartmentId", dentalId, "doctorId", staff.id().toString())), Map.class);
        assertThat(transfer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(transfer.getBody().get("type")).asString().contains("illegal-transition");
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
            jdbc.update("INSERT INTO queue_entries (id, tenant_id, patient_id, doctor_id, department_id, queue_date, token_number, status, created_at) " +
                            "VALUES (?,?,?,?,(SELECT id FROM departments WHERE tenant_id = ? AND is_default),CURRENT_DATE - INTERVAL '1 day', " +
                            "(SELECT COALESCE(MAX(token_number),0)+1 FROM queue_entries WHERE doctor_id = ?), 'completed', now() - INTERVAL '1 day')",
                    queueEntryId, tenantId, UUID.fromString(patientId), doctorId, tenantId, doctorId);
            jdbc.update("INSERT INTO invoices (id, tenant_id, queue_entry_id, patient_id, doctor_id, subtotal, tax, total, created_by, created_at) " +
                            "VALUES (?,?,?,?,?,100,0,100,?, (SELECT created_at FROM queue_entries WHERE id = ?) + (? || ' minutes')::interval)",
                    invoiceId, tenantId, queueEntryId, UUID.fromString(patientId), doctorId, doctorId, queueEntryId, minutesLater);
            return null;
        });
    }
}

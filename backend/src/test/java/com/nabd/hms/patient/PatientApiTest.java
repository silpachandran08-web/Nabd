package com.nabd.hms.patient;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PatientApiTest extends ApiTestBase {

    @Test
    void registerAdultPatientSucceeds() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner@a.com", "+919300000001", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Adult Patient", "phone", "+919888800001", "dob", "1990-01-01", "gender", "male")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).containsKey("mrn");
    }

    @Test
    void registerMinorWithoutGuardianIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner2@a.com", "+919300000002", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Minor Patient", "phone", "+919888800002", "dob",
                java.time.LocalDate.now().minusYears(10).toString(), "gender", "female")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("guardian-required");
    }

    @Test
    void registerMinorWithGuardianSucceeds() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner3@a.com", "+919300000003", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> guardianResp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Parent", "phone", "+919888800003", "dob", "1980-01-01", "gender", "female")), Map.class);
        String guardianId = (String) guardianResp.getBody().get("id");

        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Child", "phone", "+919888800004", "dob",
                java.time.LocalDate.now().minusYears(5).toString(), "gender", "male", "guardianId", guardianId)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void registerWithUnknownGuardianIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner4@a.com", "+919300000004", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Child2", "phone", "+919888800005", "dob",
                java.time.LocalDate.now().minusYears(5).toString(), "gender", "male",
                "guardianId", UUID.randomUUID().toString())), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("guardian-not-found");
    }

    @Test
    void registeringSamePhoneTwiceReturnsDuplicateCandidates() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner5@a.com", "+919300000005", false);
        String token = loginAndGetAccessToken(staff);
        String sharedPhone = "+919888800006";

        exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "First", "phone", sharedPhone, "dob", "1990-01-01", "gender", "male")), Map.class);

        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Second", "phone", sharedPhone, "dob", "1991-02-02", "gender", "female")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).containsKey("candidates");
    }

    @Test
    void listRequiresFullyVerifiedCaller() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "unverified@a.com", "+919300000006", false, true, false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody().get("type")).asString().contains("verification-required");
    }

    @Test
    void listReturnsRegisteredPatients() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner6@a.com", "+919300000007", false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Listed", "phone", "+919888800007", "dob", "1990-01-01", "gender", "male")), Map.class);

        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((java.util.List<?>) resp.getBody().get("data")).isNotEmpty();
    }

    @Test
    void getReturns404ForUnknownPatient() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner7@a.com", "+919300000008", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/patients/" + UUID.randomUUID(), HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** NB-052: a restricted custom field grant (not including "financial") makes outstandingBalance
     * genuinely absent from the JSON — not present-but-zero, not merely hidden client-side. */
    @Test
    void restrictedFieldGrantOmitsOutstandingBalanceFromTheResponse() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff owner = seedStaff(tenant, roleId, "owner7b@a.com", "+919300000011", false);
        SeededStaff restricted = seedStaff(tenant, roleId, "restricted1@a.com", "+919300000012", false);
        String ownerToken = loginAndGetAccessToken(owner);
        exchange("/v1/staff/" + restricted.id(), HttpMethod.PATCH, authedJsonBody(ownerToken, Map.of(
                "fieldGrants", List.of("vitals"))), Map.class);
        String patientId = registerPatient(ownerToken, "FieldGrant1", "+919888800011");

        String restrictedToken = loginAndGetAccessToken(restricted);
        ResponseEntity<Map> restrictedView = exchange("/v1/patients/" + patientId, HttpMethod.GET, authed(restrictedToken), Map.class);
        assertThat(restrictedView.getBody()).doesNotContainKey("outstandingBalance");

        ResponseEntity<Map> ownerView = exchange("/v1/patients/" + patientId, HttpMethod.GET, authed(ownerToken), Map.class);
        assertThat(ownerView.getBody()).containsKey("outstandingBalance");
    }

    /** NB-074: lastVisitAt is real now — derived from a queue entry that actually reached 'completed'. */
    @Test
    void getReflectsLastVisitAtFromACompletedQueueVisit() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc9@a.com", "+919300000010", false);
        String token = loginAndGetAccessToken(doc);
        exchange("/v1/doctors/" + doc.id() + "/working-hours", HttpMethod.POST, authedJsonBody(token, Map.of(
                "dayOfWeek", java.time.LocalDate.now(java.time.ZoneOffset.UTC).getDayOfWeek().getValue() % 7,
                "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        String patientId = registerPatient(token, "LastVisit", "+919888800010");

        ResponseEntity<Map> unvisited = exchange("/v1/patients/" + patientId, HttpMethod.GET, authed(token), Map.class);
        assertThat(unvisited.getBody().get("lastVisitAt")).isNull();

        ResponseEntity<Map> checkIn = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doc.id().toString())), Map.class);
        String queueEntryId = (String) checkIn.getBody().get("id");
        for (String next : new String[]{"waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending", "completed"}) {
            exchange("/v1/queue/" + queueEntryId + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", next)), Map.class);
        }

        ResponseEntity<Map> visited = exchange("/v1/patients/" + patientId, HttpMethod.GET, authed(token), Map.class);
        assertThat(visited.getBody().get("lastVisitAt")).isNotNull();
    }

    @Test
    void patchUpdatesPatient() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner8@a.com", "+919300000009", false);
        String token = loginAndGetAccessToken(staff);
        ResponseEntity<Map> reg = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Before", "phone", "+919888800008", "dob", "1990-01-01", "gender", "male")), Map.class);
        String id = (String) reg.getBody().get("id");

        ResponseEntity<Map> resp = exchange("/v1/patients/" + id, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "name", "After", "phone", "+919888800008", "dob", "1990-01-01", "gender", "male")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("name")).isEqualTo("After");
    }

    @Test
    void mergeWithoutStepUpTokenIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner9@a.com", "+919300000010", false);
        String token = loginAndGetAccessToken(staff);
        String survivorId = registerPatient(token, "Survivor", "+919888800009");
        String dupId = registerPatient(token, "Duplicate", "+919888800010");

        ResponseEntity<Map> resp = exchange("/v1/patients/" + survivorId + "/merge", HttpMethod.POST,
                authedJsonBody(token, Map.of("duplicatePatientId", dupId)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void mergeSelfIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner10@a.com", "+919300000011", true);
        String token = mfaLogin(tenant, staff);
        String stepUp = obtainStepUpToken(token);
        String patientId = registerPatient(token, "Solo", "+919888800011");

        ResponseEntity<Map> resp = exchange("/v1/patients/" + patientId + "/merge", HttpMethod.POST,
                authedJsonBodyWithHeader(token, "X-Step-Up-Token", stepUp,
                        Map.of("duplicatePatientId", patientId)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("merge-self");
    }

    @Test
    void mergeThenUndoRoundTrips() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner11@a.com", "+919300000012", true);
        String token = mfaLogin(tenant, staff);
        String stepUp = obtainStepUpToken(token);
        String survivorId = registerPatient(token, "Survivor2", "+919888800012");
        String dupId = registerPatient(token, "Duplicate2", "+919888800013");

        ResponseEntity<Map> mergeResp = exchange("/v1/patients/" + survivorId + "/merge", HttpMethod.POST,
                authedJsonBodyWithHeader(token, "X-Step-Up-Token", stepUp,
                        Map.of("duplicatePatientId", dupId)), Map.class);
        assertThat(mergeResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID mergeId = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT id FROM patient_merges WHERE duplicate_patient_id = ?::uuid ORDER BY merged_at DESC LIMIT 1",
                UUID.class, dupId));

        String stepUp2 = obtainStepUpToken(token);
        ResponseEntity<Void> undoResp = exchange("/v1/patients/merges/" + mergeId + "/undo", HttpMethod.POST,
                authedEntityWithHeader(token, "X-Step-Up-Token", stepUp2), Void.class);
        assertThat(undoResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // reversing an already-reversed merge must fail
        String stepUp3 = obtainStepUpToken(token);
        ResponseEntity<Map> secondUndo = exchange("/v1/patients/merges/" + mergeId + "/undo", HttpMethod.POST,
                authedEntityWithHeader(token, "X-Step-Up-Token", stepUp3), Map.class);
        assertThat(secondUndo.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void withdrawConsentSucceeds() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "owner12@a.com", "+919300000013", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "ConsentPatient", "+919888800014");

        ResponseEntity<Void> resp = exchange("/v1/patients/" + patientId + "/consent/withdraw", HttpMethod.POST,
                authedJsonBody(token, Map.of("consentType", "messaging")), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ---- E09 Caregiver, Family & Guardianship ----

    @Test
    void registeringMinorWithGuardianGrantsConsentAndDetailShowsIt() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "fam1@a.com", "+919300000020", false);
        String token = loginAndGetAccessToken(staff);
        String guardianId = registerPatient(token, "Guardian One", "+919888800020");

        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Minor One", "phone", "+919888800021", "dob", java.time.LocalDate.now().minusYears(10).toString(),
                "gender", "female", "guardianId", guardianId)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String minorId = (String) resp.getBody().get("id");

        ResponseEntity<Map> detail = exchange("/v1/patients/" + minorId, HttpMethod.GET, authed(token), Map.class);
        assertThat(detail.getBody().get("isMinor")).isEqualTo(true);
        assertThat(detail.getBody().get("guardianId")).isEqualTo(guardianId);
        assertThat(detail.getBody().get("guardianName")).isEqualTo("Guardian One");
        assertThat(detail.getBody().get("guardianConsentGrantedAt")).isNotNull();

        Long grants = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM consents WHERE patient_id = ? AND consent_type = 'guardian_access' AND withdrawn_at IS NULL",
                Long.class, UUID.fromString(minorId)));
        assertThat(grants).isEqualTo(1);
    }

    @Test
    void reassigningGuardianViaPatchWithdrawsOldConsentAndGrantsNew() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "fam2@a.com", "+919300000021", false);
        String token = loginAndGetAccessToken(staff);
        // distinct dobs — same dob + similar names would otherwise trip NB-060's fuzzy duplicate match
        String guardian1 = registerPatientWithDob(token, "Guardian A", "+919888800022", "1975-01-01");
        String guardian2 = registerPatientWithDob(token, "Guardian B", "+919888800023", "1978-06-15");
        String dob = java.time.LocalDate.now().minusYears(8).toString();
        ResponseEntity<Map> created = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Minor Two", "phone", "+919888800024", "dob", dob, "gender", "male", "guardianId", guardian1)), Map.class);
        String minorId = (String) created.getBody().get("id");

        ResponseEntity<Map> patched = exchange("/v1/patients/" + minorId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "name", "Minor Two", "phone", "+919888800024", "dob", dob, "gender", "male", "guardianId", guardian2)), Map.class);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);

        // consents is an append-only event log (NB-053) — reassignment leaves 3 rows: the original
        // grant, a withdrawal for guardian1, and a fresh grant for guardian2. Which guardian is
        // *current* is patients.guardian_id, asserted via the API below, not a row count here.
        Long withdrawalRows = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM consents WHERE patient_id = ? AND consent_type = 'guardian_access' AND withdrawn_at IS NOT NULL",
                Long.class, UUID.fromString(minorId)));
        assertThat(withdrawalRows).isEqualTo(1);

        ResponseEntity<Map> detail = exchange("/v1/patients/" + minorId, HttpMethod.GET, authed(token), Map.class);
        assertThat(detail.getBody().get("guardianId")).isEqualTo(guardian2);

        Long auditRows = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action IN ('patient.guardian_grant','patient.guardian_reassign')",
                Long.class, UUID.fromString(minorId)));
        assertThat(auditRows).isEqualTo(2); // initial grant at registration + reassignment
    }

    @Test
    void adultWithGuardianOnFileAppearsInReviewsDueAndHandoverClearsIt() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "fam3@a.com", "+919300000022", false);
        String token = loginAndGetAccessToken(staff);
        String guardianId = registerPatient(token, "Guardian C", "+919888800025");
        // 19 years old: an adult, but still carrying a guardian on file (nothing stops that at registration)
        String dob = java.time.LocalDate.now().minusYears(19).toString();
        ResponseEntity<Map> created = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Newly Adult", "phone", "+919888800026", "dob", dob, "gender", "female", "guardianId", guardianId)), Map.class);
        String patientId = (String) created.getBody().get("id");

        ResponseEntity<List> due = exchange("/v1/patients/guardian-reviews-due", HttpMethod.GET, authed(token), List.class);
        assertThat(due.getBody()).anyMatch(r -> patientId.equals(((Map<?, ?>) r).get("patientId")));

        ResponseEntity<Map> handover = exchange("/v1/patients/" + patientId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "name", "Newly Adult", "phone", "+919888800026", "dob", dob, "gender", "female")), Map.class);
        assertThat(handover.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> detail = exchange("/v1/patients/" + patientId, HttpMethod.GET, authed(token), Map.class);
        assertThat(detail.getBody().get("guardianId")).isNull();
        assertThat(detail.getBody().get("isMinor")).isEqualTo(false);

        ResponseEntity<List> dueAfter = exchange("/v1/patients/guardian-reviews-due", HttpMethod.GET, authed(token), List.class);
        assertThat(dueAfter.getBody()).noneMatch(r -> patientId.equals(((Map<?, ?>) r).get("patientId")));

        Long revokeAudit = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'patient.guardian_revoke'",
                Long.class, UUID.fromString(patientId)));
        assertThat(revokeAudit).isEqualTo(1);
    }

    @Test
    void guardianReviewsDueRequiresPatientsViewGrant() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "NoPatients", false, fullGrant("queue"));
        SeededStaff staff = seedStaff(tenant, roleId, "fam4@a.com", "+919300000023", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/patients/guardian-reviews-due", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- helpers ----

    private String registerPatient(String token, String name, String phone) {
        return registerPatientWithDob(token, name, phone, "1990-01-01");
    }

    private String registerPatientWithDob(String token, String name, String phone, String dob) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", dob, "gender", "male")), Map.class);
        return (String) resp.getBody().get("id");
    }

    private String mfaLogin(SeededTenant tenant, SeededStaff staff) {
        ResponseEntity<Map> loginResp = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of(
                "tenantSlug", tenant.slug(), "email", staff.email(), "pin", STAFF_PIN)), Map.class);
        String challengeId = (String) loginResp.getBody().get("challengeId");
        ResponseEntity<Map> mfaResp = http.postForEntity(url("/v1/auth/mfa/verify"), jsonBody(Map.of(
                "challengeId", challengeId, "code", currentTotpCode())), Map.class);
        return (String) mfaResp.getBody().get("accessToken");
    }

    private String obtainStepUpToken(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> req = new HttpEntity<>("{\"challengeId\":\"unused\",\"code\":\"" + currentTotpCode() + "\"}", headers);
        ResponseEntity<Map> resp = http.postForEntity(url("/v1/auth/mfa/verify"), req, Map.class);
        return (String) resp.getBody().get("stepUpToken");
    }

    private HttpEntity<Void> authedEntityWithHeader(String accessToken, String headerName, String headerValue) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set(headerName, headerValue);
        return new HttpEntity<>(headers);
    }
}

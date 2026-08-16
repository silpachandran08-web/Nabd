package com.nabd.hms.patient;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

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

    // ---- helpers ----

    private String registerPatient(String token, String name, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "male")), Map.class);
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

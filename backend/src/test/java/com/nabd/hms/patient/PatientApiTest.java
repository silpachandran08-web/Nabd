package com.nabd.hms.patient;

import com.nabd.hms.common.ModuleGrant;
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
                java.time.LocalDate.now().minusYears(5).toString(), "gender", "male", "guardianId", guardianId, "guardianConsent", Map.of("confirmed", true, "channel", "in_person"))), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void guardianRegistrationWithoutValidConsentIsRejected() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "cgv5a@a.com", "+919300000051", false);
        String token = loginAndGetAccessToken(staff);
        String guardianId = registerPatient(token, "Guardian CGV5", "+919888800061");
        String dob = java.time.LocalDate.now().minusYears(5).toString();
        java.util.List<Object> bad = java.util.Arrays.asList(null, Map.of("confirmed", false, "channel", "phone"),
                Map.of("confirmed", true, "channel", "email"), Map.of("confirmed", true));
        int n = 0;
        for (Object consent : bad) {
            Map<String, Object> body = new java.util.HashMap<>(Map.of("name", "Child CGV5", "phone", "+91988880007" + n++,
                    "dob", dob, "gender", "male", "guardianId", guardianId));
            if (consent != null) body.put("guardianConsent", consent);
            ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, body), Map.class);
            assertThat(resp.getStatusCode()).as("consent %s", consent).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(resp.getBody().get("type")).asString().contains("guardian-consent-required");
        }
    }

    @Test
    void guardianConsentEvidenceIsStoredAndAudited() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "cgv5b@a.com", "+919300000052", false);
        String token = loginAndGetAccessToken(staff);
        String guardianId = registerPatient(token, "Guardian CGV5b", "+919888800062");
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Child CGV5b", "phone", "+919888800063", "dob", java.time.LocalDate.now().minusYears(5).toString(),
                "gender", "male", "guardianId", guardianId,
                "guardianConsent", Map.of("confirmed", true, "channel", "written"))), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID childId = UUID.fromString((String) resp.getBody().get("id"));
        Map<String, Object> row = inTenantTx(tenant.id(), () -> jdbc.queryForMap(
                "SELECT captured_by, capture_channel, granted_at FROM consents WHERE patient_id = ? AND consent_type = 'guardian_access'", childId));
        assertThat(row.get("captured_by")).isEqualTo(staff.id());
        assertThat(row.get("capture_channel")).isEqualTo("written");
        assertThat(row.get("granted_at")).isNotNull();
        String after = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT after::text FROM audit_log WHERE entity_id = ? AND action = 'patient.guardian_grant'", String.class, childId));
        assertThat(after).contains("written");
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
                "guardianId", UUID.randomUUID().toString(), "guardianConsent", Map.of("confirmed", true, "channel", "in_person"))), Map.class);

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

    /** REC-038: each candidate says which compared fields matched; the score is 0..1; no national ID is ever echoed. */
    @Test
    @SuppressWarnings("unchecked")
    void duplicateCandidatesReportMatchedFieldsAndNeverTheNationalId() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "dupfields@a.com", "+919300000038", false);
        String token = loginAndGetAccessToken(staff);
        String nationalId = "1098765432";
        ResponseEntity<Map> first = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Mohammed Alharbi", "phone", "+919888803801", "dob", "1985-03-15", "gender", "male",
                "nationalId", nationalId)), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String firstId = (String) first.getBody().get("id");

        // same phone (typed another way), same DOB, near-identical name, same national ID
        ResponseEntity<String> all = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Mohammed Al-Harbi", "phone", "98888 03801", "dob", "1985-03-15", "gender", "male",
                "nationalId", nationalId)), String.class);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(all.getBody()).doesNotContain(nationalId).doesNotContain("nationalId");
        Map<String, Object> allCandidate = onlyCandidate(all.getBody());
        assertThat(allCandidate).containsEntry("patientId", firstId);
        assertThat((List<String>) allCandidate.get("matchedFields")).containsExactly("phone", "dob", "name");
        assertThat(((Number) allCandidate.get("matchScore")).doubleValue()).isEqualTo(1.0);

        // phone only: different person on the same number
        ResponseEntity<String> phoneOnly = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Zainab Qureshi", "phone", "+919888803801", "dob", "2001-11-30", "gender", "female")), String.class);
        assertThat(phoneOnly.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((List<String>) onlyCandidate(phoneOnly.getBody()).get("matchedFields")).containsExactly("phone");

        // DOB + similar name on a different phone: the fuzzy rule, score is the 0..1 name similarity
        ResponseEntity<String> fuzzy = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Mohamed Alharbi", "phone", "+919888803899", "dob", "1985-03-15", "gender", "male")), String.class);
        assertThat(fuzzy.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> fuzzyCandidate = onlyCandidate(fuzzy.getBody());
        assertThat((List<String>) fuzzyCandidate.get("matchedFields")).containsExactly("dob", "name");
        assertThat(((Number) fuzzyCandidate.get("matchScore")).doubleValue()).isGreaterThan(0.3).isLessThan(1.0);

        // tenant isolation: the same phone in another clinic matches nothing there
        SeededTenant other = seedTenant();
        SeededStaff otherStaff = seedStaff(other, seedFullAccessRole(other.id()), "dupfields2@a.com", "+919300000039", false);
        ResponseEntity<Map> elsewhere = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(loginAndGetAccessToken(otherStaff), Map.of(
                "name", "Mohammed Alharbi", "phone", "+919888803801", "dob", "1985-03-15", "gender", "male")), Map.class);
        assertThat(elsewhere.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> onlyCandidate(String body) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) parsed.get("candidates");
            assertThat(candidates).hasSize(1);
            return candidates.get(0);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
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
    /** LIVE-03: the drawer (GET /patients/{id}.lastVisit) and the registry row (lastVisit) use one
     * definition — latest non-no-show queue_date — so a visit still in progress shows in both. */
    @Test
    void detailLastVisitMatchesTheRegistryDefinition() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc-live03@a.com", "+919300000031", false);
        String token = loginAndGetAccessToken(doc);
        exchange("/v1/doctors/" + doc.id() + "/working-hours", HttpMethod.POST, authedJsonBody(token, Map.of(
                "dayOfWeek", java.time.LocalDate.now(java.time.ZoneOffset.UTC).getDayOfWeek().getValue() % 7,
                "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        String patientId = registerPatient(token, "Live Three", "+919888800031");
        assertThat(exchange("/v1/patients/" + patientId, HttpMethod.GET, authed(token), Map.class).getBody())
                .doesNotContainKey("lastVisit");

        exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doc.id().toString())), Map.class);

        Map<String, Object> detail = exchange("/v1/patients/" + patientId, HttpMethod.GET, authed(token), Map.class).getBody();
        Map<String, Object> registryRow = ((List<Map<String, Object>>) exchange("/v1/patients/registry", HttpMethod.GET,
                authed(token), Map.class).getBody().get("rows")).stream()
                .filter(r -> patientId.equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(registryRow.get("lastVisit")).isNotNull();
        assertThat(detail.get("lastVisit")).isEqualTo(registryRow.get("lastVisit"));
    }

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
                "gender", "female", "guardianId", guardianId, "guardianConsent", Map.of("confirmed", true, "channel", "in_person"))), Map.class);
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
                "name", "Minor Two", "phone", "+919888800024", "dob", dob, "gender", "male", "guardianId", guardian1, "guardianConsent", Map.of("confirmed", true, "channel", "in_person"))), Map.class);
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
                "name", "Newly Adult", "phone", "+919888800026", "dob", dob, "gender", "female", "guardianId", guardianId, "guardianConsent", Map.of("confirmed", true, "channel", "in_person"))), Map.class);
        String patientId = (String) created.getBody().get("id");

        ResponseEntity<List> due = exchange("/v1/patients/guardian-reviews-due", HttpMethod.GET, authed(token), List.class);
        assertThat(due.getBody()).anyMatch(r -> patientId.equals(((Map<?, ?>) r).get("patientId")));

        // PATCH is a true partial update (CGV-001): the handover must say guardianId:null explicitly
        java.util.Map<String, Object> clearGuardian = new java.util.HashMap<>();
        clearGuardian.put("guardianId", null);
        ResponseEntity<Map> handover = exchange("/v1/patients/" + patientId, HttpMethod.PATCH, authedJsonBody(token, clearGuardian), Map.class);
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
    void revokingGuardianViaPartialPatchKeepsAddressAndNationalId() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "fam5@a.com", "+919300000024", false);
        String token = loginAndGetAccessToken(staff);
        String guardianId = registerPatientWithDob(token, "Guardian D", "+919888800027", "1975-03-03");
        String dob = java.time.LocalDate.now().minusYears(19).toString();
        ResponseEntity<Map> created = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Has Address", "phone", "+919888800028", "dob", dob, "gender", "female", "guardianId", guardianId, "guardianConsent", Map.of("confirmed", true, "channel", "in_person"),
                "address", "12 Test Street", "nationalId", "123456789012")), Map.class);
        UUID patientId = UUID.fromString((String) created.getBody().get("id"));

        java.util.Map<String, Object> revoke = new java.util.HashMap<>();
        revoke.put("guardianId", null);
        ResponseEntity<Map> patched = exchange("/v1/patients/" + patientId, HttpMethod.PATCH, authedJsonBody(token, revoke), Map.class);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);

        String address = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT address FROM patients WHERE id = ?", String.class, patientId));
        byte[] nationalIdEnc = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT national_id_enc FROM patients WHERE id = ?", byte[].class, patientId));
        assertThat(address).isEqualTo("12 Test Street");
        assertThat(nationalIdEnc).isNotNull();
        assertThat(new String(cipher.decrypt(nationalIdEnc), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("123456789012");
        assertThat(exchange("/v1/patients/" + patientId, HttpMethod.GET, authed(token), Map.class).getBody().get("guardianId")).isNull();
    }

    @Test
    void deleteGuardianRevokesOnlyGuardianAndKeepsAddressAndNationalId() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "fam6@a.com", "+919300000025", false);
        String token = loginAndGetAccessToken(staff);
        String guardianId = registerPatientWithDob(token, "Guardian F", "+919888800031", "1975-05-05");
        String dob = java.time.LocalDate.now().minusYears(19).toString();
        ResponseEntity<Map> created = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Revoke Me", "phone", "+919888800032", "dob", dob, "gender", "female", "guardianId", guardianId, "guardianConsent", Map.of("confirmed", true, "channel", "in_person"),
                "address", "34 Test Lane", "nationalId", "555566667777")), Map.class);
        UUID patientId = UUID.fromString((String) created.getBody().get("id"));

        ResponseEntity<Void> revoked = exchange("/v1/patients/" + patientId + "/guardian", HttpMethod.DELETE, authed(token), Void.class);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, Object> row = inTenantTx(tenant.id(), () -> jdbc.queryForMap(
                "SELECT address, national_id_enc, guardian_id FROM patients WHERE id = ?", patientId));
        assertThat(row.get("guardian_id")).isNull();
        assertThat(row.get("address")).isEqualTo("34 Test Lane");
        assertThat(new String(cipher.decrypt((byte[]) row.get("national_id_enc")), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("555566667777");
        // append-only consents: the grant row stays, a later withdrawn row supersedes it; the API view must show no guardian
        Map<?, ?> detail = exchange("/v1/patients/" + patientId, HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat(detail.get("guardianId")).isNull();
        assertThat(detail.get("guardianConsentGrantedAt")).isNull();
        Long revokeAudit = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'patient.guardian_revoke'", Long.class, patientId));
        assertThat(revokeAudit).isEqualTo(1);

        // idempotent: nothing left to revoke, still 204, no second audit row
        assertThat(exchange("/v1/patients/" + patientId + "/guardian", HttpMethod.DELETE, authed(token), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteGuardianOfMinorIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "fam7@a.com", "+919300000026", false);
        String token = loginAndGetAccessToken(staff);
        String guardianId = registerPatientWithDob(token, "Guardian G", "+919888800033", "1975-06-06");
        ResponseEntity<Map> created = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Little One", "phone", "+919888800034", "dob", java.time.LocalDate.now().minusYears(5).toString(),
                "gender", "male", "guardianId", guardianId, "guardianConsent", Map.of("confirmed", true, "channel", "in_person"))), Map.class);
        String patientId = (String) created.getBody().get("id");

        ResponseEntity<Map> resp = exchange("/v1/patients/" + patientId + "/guardian", HttpMethod.DELETE, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("guardian-required");
        assertThat(exchange("/v1/patients/" + patientId, HttpMethod.GET, authed(token), Map.class).getBody().get("guardianId")).isEqualTo(guardianId);
    }

    @Test
    void partialPatchOfNameLeavesGuardianAddressAndNationalIdUntouched() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "fam6@a.com", "+919300000025", false);
        String token = loginAndGetAccessToken(staff);
        String guardianId = registerPatientWithDob(token, "Guardian E", "+919888800029", "1976-04-04");
        String dob = java.time.LocalDate.now().minusYears(7).toString();
        ResponseEntity<Map> created = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Small Child", "phone", "+919888800030", "dob", dob, "gender", "male", "guardianId", guardianId, "guardianConsent", Map.of("confirmed", true, "channel", "in_person"),
                "address", "Addr 1", "nationalId", "987654321098")), Map.class);
        UUID patientId = UUID.fromString((String) created.getBody().get("id"));

        ResponseEntity<Map> patched = exchange("/v1/patients/" + patientId, HttpMethod.PATCH,
                authedJsonBody(token, Map.of("name", "Renamed Child")), Map.class);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody().get("name")).isEqualTo("Renamed Child");

        Map<String, Object> row = inTenantTx(tenant.id(), () -> jdbc.queryForMap(
                "SELECT address, national_id_enc, guardian_id FROM patients WHERE id = ?", patientId));
        assertThat(row.get("address")).isEqualTo("Addr 1");
        assertThat(row.get("national_id_enc")).isNotNull();
        assertThat(row.get("guardian_id")).isNotNull();
    }

    @Test
    void explicitNullClearsAddressAndNationalIdAndRequiredFieldsCannotBeNulled() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "fam7@a.com", "+919300000026", false);
        String token = loginAndGetAccessToken(staff);
        ResponseEntity<Map> created = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Adult Clear", "phone", "+919888800031", "dob", "1985-05-05", "gender", "male",
                "address", "Old addr", "nationalId", "111122223333")), Map.class);
        UUID patientId = UUID.fromString((String) created.getBody().get("id"));

        java.util.Map<String, Object> clear = new java.util.HashMap<>();
        clear.put("address", null);
        clear.put("nationalId", null);
        assertThat(exchange("/v1/patients/" + patientId, HttpMethod.PATCH, authedJsonBody(token, clear), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = inTenantTx(tenant.id(), () -> jdbc.queryForMap(
                "SELECT address, national_id_enc FROM patients WHERE id = ?", patientId));
        assertThat(row.get("address")).isNull();
        assertThat(row.get("national_id_enc")).isNull();

        // V1-015: the clear is audited by field name; no PHI value is in the payload.
        Map<String, Object> audited = inTenantTx(tenant.id(), () -> jdbc.queryForMap(
                "SELECT after::text AS after FROM audit_log WHERE tenant_id = ? AND action = 'patient.updated' AND entity_id = ? " +
                        "ORDER BY created_at DESC LIMIT 1", tenant.id(), patientId));
        assertThat(audited.get("after").toString()).contains("nationalId").contains("address").contains("cleared");
        assertThat(audited.get("after").toString()).doesNotContain("111122223333").doesNotContain("Old addr");

        // V1-015: a blank-but-present national ID is rejected, not treated as a silent clear.
        java.util.Map<String, Object> blankId = new java.util.HashMap<>();
        blankId.put("nationalId", "   ");
        assertThat(exchange("/v1/patients/" + patientId, HttpMethod.PATCH, authedJsonBody(token, blankId), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // V3-010: same for a blank address, and clearing a national ID that is already empty is a no-op with no audit row.
        java.util.Map<String, Object> blankAddress = new java.util.HashMap<>();
        blankAddress.put("address", "  ");
        assertThat(exchange("/v1/patients/" + patientId, HttpMethod.PATCH, authedJsonBody(token, blankAddress), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Integer auditsBefore = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'patient.updated' AND entity_id = ?",
                Integer.class, tenant.id(), patientId));
        java.util.Map<String, Object> clearAgain = new java.util.HashMap<>();
        clearAgain.put("nationalId", null);
        assertThat(exchange("/v1/patients/" + patientId, HttpMethod.PATCH, authedJsonBody(token, clearAgain), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        Integer auditsAfter = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'patient.updated' AND entity_id = ?",
                Integer.class, tenant.id(), patientId));
        assertThat(auditsAfter).isEqualTo(auditsBefore);

        java.util.Map<String, Object> nullName = new java.util.HashMap<>();
        nullName.put("name", null);
        assertThat(exchange("/v1/patients/" + patientId, HttpMethod.PATCH, authedJsonBody(token, nullName), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
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

    // CGV-002: keep-proxy outcome removes the patient from the worklist, keeps guardian + consent, and is audited.
    @Test
    void keepingTheProxyAtTheAge18ReviewClearsTheWorklistRowAndIsAudited() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "fam-keep@a.com", "+919300000041", false);
        String token = loginAndGetAccessToken(staff);
        String guardianId = registerPatient(token, "Guardian K", "+919888800041");
        String dob = java.time.LocalDate.now().minusYears(19).toString();
        ResponseEntity<Map> created = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Keep Me", "phone", "+919888800042", "dob", dob, "gender", "female", "guardianId", guardianId, "guardianConsent", Map.of("confirmed", true, "channel", "in_person"))), Map.class);
        String patientId = (String) created.getBody().get("id");

        ResponseEntity<Map> bad = exchange("/v1/patients/" + patientId + "/guardian/review", HttpMethod.POST,
                authedJsonBody(token, Map.of("decision", "revoke")), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> kept = exchange("/v1/patients/" + patientId + "/guardian/review", HttpMethod.POST,
                authedJsonBody(token, Map.of("decision", "keep")), Map.class);
        assertThat(kept.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(kept.getBody().get("guardianId")).isEqualTo(guardianId);
        assertThat(kept.getBody().get("guardianReviewKeptAt")).isNotNull();
        assertThat(kept.getBody().get("guardianConsentGrantedAt")).isNotNull();

        ResponseEntity<List> due = exchange("/v1/patients/guardian-reviews-due", HttpMethod.GET, authed(token), List.class);
        assertThat(due.getBody()).noneMatch(r -> patientId.equals(((Map<?, ?>) r).get("patientId")));
        Long auditCount = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_id = ? AND action = 'patient.guardian_review_kept'",
                Long.class, UUID.fromString(patientId)));
        assertThat(auditCount).isEqualTo(1);

        // a minor has no review due
        String minorId = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Little", "phone", "+919888800043", "dob", java.time.LocalDate.now().minusYears(5).toString(),
                "gender", "male", "guardianId", guardianId, "guardianConsent", Map.of("confirmed", true, "channel", "in_person"))), Map.class).getBody().get("id");
        assertThat(exchange("/v1/patients/" + minorId + "/guardian/review", HttpMethod.POST,
                authedJsonBody(token, Map.of("decision", "keep")), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void keepProxyNeedsPatientsEditAndRevokeAfterKeepStillWorks() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "fam-keep2@a.com", "+919300000044", false);
        String token = loginAndGetAccessToken(staff);
        UUID viewRole = seedRole(tenant.id(), "ViewOnlyPat", false, new ModuleGrant("patients", true, false, false, false, false, false, false));
        SeededStaff viewer = seedStaff(tenant, viewRole, "fam-view@a.com", "+919300000045", false);
        String viewToken = loginAndGetAccessToken(viewer);
        String guardianId = registerPatient(token, "Guardian V", "+919888800044");
        String patientId = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Adult V", "phone", "+919888800045", "dob", java.time.LocalDate.now().minusYears(20).toString(),
                "gender", "male", "guardianId", guardianId, "guardianConsent", Map.of("confirmed", true, "channel", "in_person"))), Map.class).getBody().get("id");

        assertThat(exchange("/v1/patients/" + patientId + "/guardian/review", HttpMethod.POST,
                authedJsonBody(viewToken, Map.of("decision", "keep")), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        exchange("/v1/patients/" + patientId + "/guardian/review", HttpMethod.POST, authedJsonBody(token, Map.of("decision", "keep")), Map.class);
        assertThat(exchange("/v1/patients/" + patientId + "/guardian", HttpMethod.DELETE, authed(token), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
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

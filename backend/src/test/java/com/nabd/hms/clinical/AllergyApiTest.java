package com.nabd.hms.clinical;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class AllergyApiTest extends ApiTestBase {

    private String registerPatient(String token, String name, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "male")), Map.class);
        return (String) resp.getBody().get("id");
    }

    @Test
    void addingAnAllergyMakesItVisibleOnTheRegisterAndOnThePatientDrawer() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse1@a.com", "+919800000001", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "A1", "+919999940001");

        ResponseEntity<Map> add = exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "Penicillin", "severity", "severe", "reaction", "anaphylaxis")), Map.class);
        assertThat(add.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(add.getBody().get("active")).isEqualTo(true);

        ResponseEntity<List> list = exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.GET, authed(token), List.class);
        assertThat(list.getBody()).hasSize(1);

        ResponseEntity<Map> drawer = exchange("/v1/patients/" + patientId, HttpMethod.GET, authed(token), Map.class);
        assertThat((List<String>) drawer.getBody().get("allergies")).containsExactly("Penicillin");
    }

    @Test
    void deactivatingAnAllergyRemovesItFromTheActiveList() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse2@a.com", "+919800000002", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "A2", "+919999940002");
        ResponseEntity<Map> add = exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "Sulfa", "severity", "mild", "reaction", "rash")), Map.class);
        String allergyId = (String) add.getBody().get("id");

        ResponseEntity<Map> noReason = exchange("/v1/clinical/allergies/" + allergyId + "/deactivate", HttpMethod.PATCH,
                authed(token), Map.class);
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noReason.getBody().get("type")).asString().contains("reason-required");

        ResponseEntity<Void> deactivate = exchange("/v1/clinical/allergies/" + allergyId + "/deactivate", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("reason", "Entered on the wrong patient")), Void.class);
        assertThat(deactivate.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<List> list = exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.GET, authed(token), List.class);
        assertThat(list.getBody()).isEmpty();
    }

    @Test
    void roleWithoutClinicalGrantIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "FrontDeskOnly", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "reception2@a.com", "+919800000003", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/clinical/patients/" + UUID.randomUUID() + "/allergies", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aCorrectionNeedsAReasonAndTheOriginalStaysInTheHistory() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse4@a.com", "+919800000004", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "A4", "+919999940004");
        ResponseEntity<Map> add = exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "Peanut", "category", "food", "severity", "mild", "reaction", "itching")), Map.class);
        String allergyId = (String) add.getBody().get("id");

        ResponseEntity<Map> noReason = exchange("/v1/clinical/allergies/" + allergyId, HttpMethod.PATCH,
                authedJsonBody(token, Map.of("severity", "severe")), Map.class);
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> corrected = exchange("/v1/clinical/allergies/" + allergyId, HttpMethod.PATCH,
                authedJsonBody(token, Map.of("severity", "severe", "reason", "Patient described a throat swelling episode")), Map.class);
        assertThat(corrected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(corrected.getBody().get("severity")).isEqualTo("severe");

        ResponseEntity<List> history = exchange("/v1/clinical/patients/" + patientId + "/allergies/history", HttpMethod.GET, authed(token), List.class);
        assertThat(history.getBody()).hasSize(2); // recorded + corrected, newest first
        Map<?, ?> latest = (Map<?, ?>) history.getBody().get(0);
        assertThat(latest.get("event")).isEqualTo("corrected");
        assertThat(latest.get("reason")).isEqualTo("Patient described a throat swelling episode");
        assertThat(((Map<?, ?>) latest.get("before")).get("severity")).isEqualTo("mild");
    }

    @Test
    void noKnownAllergiesIsADocumentedStatementDistinctFromNothingRecorded() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "nurse5@a.com", "+919800000005", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "A5", "+919999940005");
        String url = "/v1/clinical/patients/" + patientId + "/allergies/status";

        assertThat(exchange(url, HttpMethod.GET, authed(token), Map.class).getBody().get("state")).isEqualTo("not_assessed");
        assertThat(exchange(url, HttpMethod.PUT, authed(token), Map.class).getBody().get("state")).isEqualTo("none_documented");

        exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "Dust", "severity", "mild")), Map.class);
        assertThat(exchange(url, HttpMethod.GET, authed(token), Map.class).getBody().get("state")).isEqualTo("recorded");
        assertThat(exchange(url, HttpMethod.PUT, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // V1-005: two clinicians correct different fields of the same allergy at once. Neither may undo the other:
    // the severity change must survive the reaction-only correction, and the history must show both changes.
    @Test
    void concurrentCorrectionsDoNotSilentlyDowngradeSeverity() throws Exception {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff a = seedStaff(tenant, roleId, "corr-a@a.com", "+919800040001", false);
        SeededStaff b = seedStaff(tenant, roleId, "corr-b@a.com", "+919800040002", false);
        String tokenA = loginAndGetAccessToken(a);
        String tokenB = loginAndGetAccessToken(b);
        String patientId = registerPatient(tokenA, "Racer One", "+919999940101");
        ResponseEntity<Map> add = exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(tokenA, Map.of("substance", "Penicillin", "severity", "mild", "reaction", "rash")), Map.class);
        String allergyId = (String) add.getBody().get("id");

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<ResponseEntity<Map>> severity = pool.submit(() -> {
            go.await();
            return exchange("/v1/clinical/allergies/" + allergyId, HttpMethod.PATCH, authedJsonBody(tokenA,
                    Map.of("severity", "severe", "reason", "Anaphylaxis confirmed by the patient's record")), Map.class);
        });
        Future<ResponseEntity<Map>> reaction = pool.submit(() -> {
            go.await();
            return exchange("/v1/clinical/allergies/" + allergyId, HttpMethod.PATCH, authedJsonBody(tokenB,
                    Map.of("reaction", "hives", "reason", "Reaction text clarified")), Map.class);
        });
        go.countDown();
        assertThat(severity.get().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reaction.get().getStatusCode()).isEqualTo(HttpStatus.OK);
        pool.shutdown();

        ResponseEntity<List> list = exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.GET, authed(tokenA), List.class);
        Map<?, ?> current = (Map<?, ?>) list.getBody().get(0);
        assertThat(current.get("severity")).isEqualTo("severe");
        assertThat(current.get("reaction")).isEqualTo("hives");

        // Two corrections, each with its own history row, chained: the second one's "before" is the first one's "after".
        ResponseEntity<List> history = exchange("/v1/clinical/patients/" + patientId + "/allergies/history", HttpMethod.GET, authed(tokenA), List.class);
        long corrected = history.getBody().stream().filter(e -> "corrected".equals(((Map<?, ?>) e).get("event"))).count();
        assertThat(corrected).isEqualTo(2);
        for (Object e : history.getBody()) {
            Map<?, ?> ev = (Map<?, ?>) e;
            if ("corrected".equals(ev.get("event"))) {
                assertThat(((Map<?, ?>) ev.get("before")).toString()).isNotEqualTo(((Map<?, ?>) ev.get("after")).toString());
            }
        }
    }

    // V1-014: the removal body is validated (reason is capped at 500 characters).
    @Test
    void removalReasonOverFiveHundredCharactersIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "rem-v14@a.com", "+919800040003", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "Racer Two", "+919999940102");
        ResponseEntity<Map> add = exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "Latex", "severity", "mild")), Map.class);
        String allergyId = (String) add.getBody().get("id");

        ResponseEntity<Map> tooLong = exchange("/v1/clinical/allergies/" + allergyId + "/deactivate", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("reason", "x".repeat(501))), Map.class);

        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<List> stillThere = exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.GET, authed(token), List.class);
        assertThat(stillThere.getBody()).hasSize(1);
    }
}

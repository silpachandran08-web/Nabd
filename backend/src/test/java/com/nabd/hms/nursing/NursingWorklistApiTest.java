package com.nabd.hms.nursing;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** NUR-019: GET /v1/nursing/worklist = GET /queue + GET /patients/{id} + GET /clinical/vitals/{id} per row, in one call. */
class NursingWorklistApiTest extends ApiTestBase {

    private String registerPatient(String token, String name, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "male")), Map.class);
        return (String) resp.getBody().get("id");
    }

    private String checkIn(String token, String patientId, UUID doctorId) {
        ResponseEntity<Map> resp = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class);
        return (String) resp.getBody().get("id");
    }

    private void moveTo(String token, String queueEntryId, String... statuses) {
        for (String s : statuses) {
            exchange("/v1/queue/" + queueEntryId + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", s)), Map.class);
        }
    }

    private void recordVitals(String token, String queueEntryId) {
        ResponseEntity<Map> resp = exchange("/v1/clinical/vitals/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "bpSystolic", 120, "bpDiastolic", 80, "pulseBpm", 72)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private List<Map<String, Object>> items(String token) {
        ResponseEntity<Map> resp = exchange("/v1/nursing/worklist", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) resp.getBody().get("items");
    }

    private static Map<String, Object> itemFor(List<Map<String, Object>> items, String queueEntryId) {
        return items.stream().filter(i -> queueEntryId.equals(((Map<?, ?>) i.get("entry")).get("id"))).findFirst().orElseThrow();
    }

    @Test
    void eachItemCarriesTheSameEntryPatientAndVitalsAsTheSingleEndpoints() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "wl1@a.com", "+919800020001", false);
        String token = loginAndGetAccessToken(staff);
        String p1 = registerPatient(token, "WL One", "+919999960001");
        String p2 = registerPatient(token, "WL Two", "+919999960002");
        String withVitals = checkIn(token, p1, staff.id());
        String withoutVitals = checkIn(token, p2, staff.id());
        moveTo(token, withVitals, "waiting", "vitals_pending");
        recordVitals(token, withVitals);
        moveTo(token, withoutVitals, "waiting", "vitals_pending");

        List<Map<String, Object>> items = items(token);
        List<Map> queue = exchange("/v1/queue", HttpMethod.GET, authed(token), List.class).getBody();
        assertThat(items).hasSize(queue.size()).hasSize(2);
        // waitMinutes is computed per request and can tick over between the two calls, so compare without it.
        java.util.function.Function<Object, Map<?, ?>> noWait = m -> { Map<Object, Object> c = new java.util.HashMap<>((Map<?, ?>) m); c.remove("waitMinutes"); return c; };
        assertThat(items.stream().map(i -> noWait.apply(i.get("entry"))).toList())
                .isEqualTo(queue.stream().map(noWait).toList());

        Map<String, Object> recorded = itemFor(items, withVitals);
        assertThat(recorded.get("patient")).isEqualTo(exchange("/v1/patients/" + p1, HttpMethod.GET, authed(token), Map.class).getBody());
        assertThat(recorded.get("vitals")).isEqualTo(exchange("/v1/clinical/vitals/" + withVitals, HttpMethod.GET, authed(token), Map.class).getBody());

        Map<String, Object> pending = itemFor(items, withoutVitals);
        assertThat(pending.get("patient")).isEqualTo(exchange("/v1/patients/" + p2, HttpMethod.GET, authed(token), Map.class).getBody());
        assertThat(pending).containsEntry("vitals", null); // row kept, key present, value null
    }

    @Test
    void emptyDayGivesEmptyItems() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "wl2@a.com", "+919800020002", false);
        assertThat(items(loginAndGetAccessToken(staff))).isEmpty();
    }

    @Test
    void vitalsAreNullWithoutClinicalViewButTheRowAndPatientStay() {
        SeededTenant tenant = seedTenant();
        SeededStaff full = seedStaff(tenant, seedFullAccessRole(tenant.id()), "wl3@a.com", "+919800020003", false);
        String fullToken = loginAndGetAccessToken(full);
        String p = registerPatient(fullToken, "WL Three", "+919999960003");
        String entry = checkIn(fullToken, p, full.id());
        moveTo(fullToken, entry, "waiting", "vitals_pending");
        recordVitals(fullToken, entry);

        UUID noClinical = seedRole(tenant.id(), "QueueAndPatients", false, fullGrant("queue"), fullGrant("patients"));
        String token = loginAndGetAccessToken(seedStaff(tenant, noClinical, "wl4@a.com", "+919800020004", false));
        Map<String, Object> item = itemFor(items(token), entry);
        assertThat(item).containsEntry("vitals", null);
        assertThat(((Map<?, ?>) item.get("patient")).get("id")).isEqualTo(p);
    }

    @Test
    void callerWithoutQueueViewIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "ClinicalOnly", false, fullGrant("clinical"), fullGrant("patients"), fullGrant("nursing"));
        String token = loginAndGetAccessToken(seedStaff(tenant, roleId, "wl5@a.com", "+919800020005", false));
        assertThat(exchange("/v1/nursing/worklist", HttpMethod.GET, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anotherTenantsEntriesNeverAppear() {
        SeededTenant a = seedTenant();
        SeededStaff staffA = seedStaff(a, seedFullAccessRole(a.id()), "wl6@a.com", "+919800020006", false);
        String tokenA = loginAndGetAccessToken(staffA);
        String entryA = checkIn(tokenA, registerPatient(tokenA, "WL Six", "+919999960006"), staffA.id());

        SeededTenant b = seedTenant();
        SeededStaff staffB = seedStaff(b, seedFullAccessRole(b.id()), "wl7@b.com", "+919800020007", false);
        String tokenB = loginAndGetAccessToken(staffB);
        String entryB = checkIn(tokenB, registerPatient(tokenB, "WL Seven", "+919999960007"), staffB.id());

        List<Map<String, Object>> itemsB = items(tokenB);
        assertThat(itemsB).extracting(i -> (Object) ((Map<?, ?>) i.get("entry")).get("id")).containsExactly(entryB);
        assertThat(items(tokenA)).extracting(i -> (Object) ((Map<?, ?>) i.get("entry")).get("id")).containsExactly(entryA);
    }
}

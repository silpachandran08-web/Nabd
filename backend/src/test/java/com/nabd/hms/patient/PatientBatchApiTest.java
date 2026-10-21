package com.nabd.hms.patient;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FEF-016: GET /v1/patients/batch. */
class PatientBatchApiTest extends ApiTestBase {

    private String register(String token, String name, String phone, String dob) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", dob, "gender", "male")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsTheVisiblePatientsInOneCallAndSkipsUnknownIds() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "batch@a.com", "+919300016001", false);
        String token = loginAndGetAccessToken(staff);
        String a = register(token, "Aarav Menon", "+919888801601", "1971-03-04");
        String b = register(token, "Zoya Hussain", "+919888801602", "1988-09-21");

        ResponseEntity<List> resp = exchange("/v1/patients/batch?ids=" + a + "," + b + "," + UUID.randomUUID(), HttpMethod.GET, authed(token), List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(2);
        assertThat(((List<Map<String, Object>>) resp.getBody()).stream().map(m -> m.get("name"))).containsExactlyInAnyOrder("Aarav Menon", "Zoya Hussain");
    }

    @Test
    void anotherTenantsPatientIsNotReturned() {
        SeededTenant t1 = seedTenant();
        String token1 = loginAndGetAccessToken(seedStaff(t1, seedFullAccessRole(t1.id()), "b1@a.com", "+919300016002", false));
        String mine = register(token1, "Kabir Sandhu", "+919888801603", "1979-12-12");
        SeededTenant t2 = seedTenant();
        String token2 = loginAndGetAccessToken(seedStaff(t2, seedFullAccessRole(t2.id()), "b2@a.com", "+919300016003", false));

        ResponseEntity<List> resp = exchange("/v1/patients/batch?ids=" + mine, HttpMethod.GET, authed(token2), List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEmpty();
    }

    @Test
    void boundaryHundredIdsPassesAndHundredOneIsRefused() {
        SeededTenant tenant = seedTenant();
        String token = loginAndGetAccessToken(seedStaff(tenant, seedFullAccessRole(tenant.id()), "b3@a.com", "+919300016004", false));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) ids.add(UUID.randomUUID().toString());

        assertThat(exchange("/v1/patients/batch?ids=" + String.join(",", ids), HttpMethod.GET, authed(token), List.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ids.add(UUID.randomUUID().toString());
        ResponseEntity<Map> over = exchange("/v1/patients/batch?ids=" + String.join(",", ids), HttpMethod.GET, authed(token), Map.class);
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void withoutPatientsViewIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID role = seedRole(tenant.id(), "NoPatients", false, fullGrant("queue"));
        String token = loginAndGetAccessToken(seedStaff(tenant, role, "b4@a.com", "+919300016005", false));

        assertThat(exchange("/v1/patients/batch?ids=" + UUID.randomUUID(), HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}

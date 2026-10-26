package com.nabd.hms.config;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-035 / S13 contract gate: runtime facts behind the springdoc 3 spec changes (minLength:1, format:email, minItems,
 * minimum:0, LocalTime as string/time-local). Passes unchanged on the pre-PLT-032 build (Spring Boot 3) and on Boot 4,
 * which is what proves the spec got more accurate while the wire did not change.
 */
class WireFormatParityApiTest extends ApiTestBase {

    private int loginStatus(Map<String, Object> body) {
        return http.postForEntity(url("/v1/auth/login"), jsonBody(body), String.class).getStatusCode().value();
    }

    @Test
    void loginRefusesEmptyAndMalformedValuesAsBefore() {
        assertThat(loginStatus(Map.of("tenantSlug", "t", "email", "", "pin", "1234"))).isEqualTo(400);
        assertThat(loginStatus(Map.of("tenantSlug", "", "email", "a@b.com", "pin", "1234"))).isEqualTo(400);
        assertThat(loginStatus(Map.of("tenantSlug", "t", "email", "a@b.com", "pin", ""))).isEqualTo(400);
        assertThat(loginStatus(Map.of("tenantSlug", "t", "email", "not-an-email", "pin", "1234"))).isEqualTo(400);
    }

    @Test
    void emptyCollectionsAndNegativeNumbersAreRefusedAsBefore() {
        SeededTenant tenant = seedTenant();
        String token = loginAndGetAccessToken(seedStaff(tenant, seedFullAccessRole(tenant.id()), "w1@a.com", "+919400000091", false));
        assertThat(exchange("/v1/roles", HttpMethod.POST, authedJsonBody(token, Map.of("name", "r", "grants", List.of())), String.class)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(token, Map.of("name", "x", "price", -1)), String.class)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(exchange("/v1/packages", HttpMethod.POST, authedJsonBody(token, Map.of("name", "p", "price", 1, "validityDays", 1, "items", List.of())), String.class)
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void workingHoursTimesAreStringsOnTheWireAndObjectsAreRefused() {
        SeededTenant tenant = seedTenant();
        SeededStaff doctor = seedStaff(tenant, seedFullAccessRole(tenant.id()), "w2@a.com", "+919400000092", false);
        String token = loginAndGetAccessToken(doctor);
        String path = "/v1/doctors/" + doctor.id() + "/working-hours";
        ResponseEntity<String> created = exchange(path, HttpMethod.POST, authedJsonBody(token,
                Map.of("dayOfWeek", 2, "startTime", "08:00:00", "endTime", "12:30:00", "slotMinutes", 15)), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        System.out.println("WIRE POST response: " + created.getBody());
        assertThat(created.getBody()).contains("\"startTime\":\"08:00:00\"").contains("\"endTime\":\"12:30:00\"");
        ResponseEntity<String> listed = exchange(path, HttpMethod.GET, authed(token), String.class);
        System.out.println("WIRE GET response: " + listed.getBody());
        assertThat(listed.getBody()).contains("\"startTime\":\"08:00:00\"");
        // the old spec described an object {hour,minute,second,nano}; the API never accepted it
        ResponseEntity<String> asObject = exchange(path, HttpMethod.POST, authedJsonBody(token,
                Map.of("dayOfWeek", 3, "startTime", Map.of("hour", 8, "minute", 0, "second", 0, "nano", 0),
                        "endTime", Map.of("hour", 9, "minute", 0, "second", 0, "nano", 0), "slotMinutes", 15)), String.class);
        System.out.println("WIRE object-request status: " + asObject.getStatusCode().value());
        assertThat(asObject.getStatusCode().value()).isBetween(400, 499);
    }
}

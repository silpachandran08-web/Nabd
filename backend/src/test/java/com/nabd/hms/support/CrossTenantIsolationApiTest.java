package com.nabd.hms.support;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-tenancy is enforced two ways at once (app-level tenantId filters AND Postgres RLS) —
 * neither alone caught the NULL-cast bug fixed earlier in PatientRepository's scope gate.
 * These tests hit real endpoints with two genuinely separate, fully-seeded tenants and assert
 * one tenant's staff can never see the other's data, even though both hold validly-signed JWTs.
 */
class CrossTenantIsolationApiTest extends ApiTestBase {

    @Test
    void patientRegisteredInOneTenantIsInvisibleToAnother() {
        SeededTenant tenantA = seedTenant();
        SeededTenant tenantB = seedTenant();
        UUID roleA = seedFullAccessRole(tenantA.id());
        UUID roleB = seedFullAccessRole(tenantB.id());
        SeededStaff staffA = seedStaff(tenantA, roleA, "a@a.com", "+919700000001", false);
        SeededStaff staffB = seedStaff(tenantB, roleB, "b@b.com", "+919700000002", false);
        String tokenA = loginAndGetAccessToken(staffA);
        String tokenB = loginAndGetAccessToken(staffB);

        ResponseEntity<Map> reg = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(tokenA, Map.of(
                "name", "Tenant A Patient", "phone", "+919888900001", "dob", "1990-01-01", "gender", "male")), Map.class);
        String patientId = (String) reg.getBody().get("id");

        ResponseEntity<Map> ownTenant = exchange("/v1/patients/" + patientId, HttpMethod.GET, authed(tokenA), Map.class);
        assertThat(ownTenant.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> otherTenant = exchange("/v1/patients/" + patientId, HttpMethod.GET, authed(tokenB), Map.class);
        assertThat(otherTenant.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void patientListDoesNotLeakAcrossTenants() {
        SeededTenant tenantA = seedTenant();
        SeededTenant tenantB = seedTenant();
        UUID roleA = seedFullAccessRole(tenantA.id());
        UUID roleB = seedFullAccessRole(tenantB.id());
        SeededStaff staffA = seedStaff(tenantA, roleA, "a2@a.com", "+919700000003", false);
        SeededStaff staffB = seedStaff(tenantB, roleB, "b2@b.com", "+919700000004", false);
        String tokenA = loginAndGetAccessToken(staffA);
        String tokenB = loginAndGetAccessToken(staffB);

        exchange("/v1/patients", HttpMethod.POST, authedJsonBody(tokenA, Map.of(
                "name", "Only In A", "phone", "+919888900002", "dob", "1990-01-01", "gender", "male")), Map.class);

        ResponseEntity<Map> listB = exchange("/v1/patients?q=Only In A", HttpMethod.GET, authed(tokenB), Map.class);
        assertThat(listB.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) listB.getBody().get("data")).isEmpty();
    }

    @Test
    void staffListDoesNotLeakAcrossTenants() {
        SeededTenant tenantA = seedTenant();
        SeededTenant tenantB = seedTenant();
        UUID roleA = seedFullAccessRole(tenantA.id());
        UUID roleB = seedFullAccessRole(tenantB.id());
        SeededStaff staffA = seedStaff(tenantA, roleA, "a3@a.com", "+919700000005", false);
        SeededStaff staffB = seedStaff(tenantB, roleB, "b3@b.com", "+919700000006", false);
        String tokenB = loginAndGetAccessToken(staffB);

        ResponseEntity<Map> listB = exchange("/v1/staff", HttpMethod.GET, authed(tokenB), Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) listB.getBody().get("data");
        assertThat(data).noneMatch(s -> staffA.email().equals(s.get("email")));

        // and B's own staff:view cannot resolve A's staff id directly either
        ResponseEntity<Map> getA = exchange("/v1/staff/" + staffA.id(), HttpMethod.GET, authed(tokenB), Map.class);
        assertThat(getA.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void appointmentBookedInOneTenantIsInvisibleToAnother() {
        SeededTenant tenantA = seedTenant();
        SeededTenant tenantB = seedTenant();
        UUID roleA = seedFullAccessRole(tenantA.id());
        UUID roleB = seedFullAccessRole(tenantB.id());
        SeededStaff staffA = seedStaff(tenantA, roleA, "a4@a.com", "+919700000007", false);
        SeededStaff staffB = seedStaff(tenantB, roleB, "b4@b.com", "+919700000008", false);
        String tokenA = loginAndGetAccessToken(staffA);
        String tokenB = loginAndGetAccessToken(staffB);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int pgDay = today.getDayOfWeek().getValue() % 7;
        exchange("/v1/doctors/" + staffA.id() + "/working-hours", HttpMethod.POST, authedJsonBody(tokenA, Map.of(
                "dayOfWeek", pgDay, "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        ResponseEntity<Map> patientResp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(tokenA, Map.of(
                "name", "A Patient", "phone", "+919888900003", "dob", "1990-01-01", "gender", "male")), Map.class);
        String patientId = (String) patientResp.getBody().get("id");
        java.time.Instant slot = java.time.Instant.now().plus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);

        ResponseEntity<Map> booked = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(tokenA, Map.of(
                "doctorId", staffA.id().toString(), "patientId", patientId, "startTime", slot.toString())), Map.class);
        assertThat(booked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String appointmentId = (String) booked.getBody().get("id");

        ResponseEntity<Map> viaB = exchange("/v1/appointments/" + appointmentId, HttpMethod.GET, authed(tokenB), Map.class);
        assertThat(viaB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // M3 (written, NOT executed: no Docker/Postgres in the authoring environment). Wave 2 tables: both the API
    // and the RLS policy (queried as the app role under the other tenant's context) must hide the row.
    @Test
    void noteTemplatesAndVaccineRecordsAreInvisibleToAnotherTenant() {
        SeededTenant tenantA = seedTenant();
        SeededTenant tenantB = seedTenant();
        UUID roleA = seedFullAccessRole(tenantA.id());
        UUID roleB = seedFullAccessRole(tenantB.id());
        SeededStaff staffA = seedStaff(tenantA, roleA, "a5@a.com", "+919700000009", false);
        SeededStaff staffB = seedStaff(tenantB, roleB, "b5@b.com", "+919700000010", false);
        String tokenA = loginAndGetAccessToken(staffA);
        String tokenB = loginAndGetAccessToken(staffB);

        String templateId = (String) exchange("/v1/clinical/note-templates", HttpMethod.POST,
                authedJsonBody(tokenA, Map.of("name", "A only")), Map.class).getBody().get("id");
        String patientId = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(tokenA, Map.of(
                "name", "A Vaccinee", "phone", "+919888900005", "dob", "1990-01-01", "gender", "male")), Map.class).getBody().get("id");
        ResponseEntity<Map> vaccine = exchange("/v1/nursing/vaccine-records", HttpMethod.POST, authedJsonBody(tokenA, Map.of(
                "patientId", patientId, "vaccineName", "A vaccine", "dose", "0.5 mL", "route", "IM")), Map.class);
        assertThat(vaccine.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String vaccineId = (String) vaccine.getBody().get("id");

        // API: B sees neither and cannot touch the template
        assertThat(exchange("/v1/clinical/note-templates", HttpMethod.GET, authed(tokenB), List.class).getBody()).isEmpty();
        assertThat(exchange("/v1/clinical/note-templates/" + templateId, HttpMethod.PATCH,
                authedJsonBody(tokenB, Map.of("name", "Taken")), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/clinical/note-templates/" + templateId, HttpMethod.DELETE, authed(tokenB), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/nursing/vaccine-records/today", HttpMethod.GET, authed(tokenB), List.class).getBody()).isEmpty();
        assertThat(exchange("/v1/nursing/vaccine-records", HttpMethod.POST, authedJsonBody(tokenB, Map.of(
                "patientId", patientId, "vaccineName", "X", "dose", "1", "route", "IM")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND); // A's patient does not exist for B

        // RLS: without any tenant_id predicate, B's context still cannot read A's rows; A's context can
        Integer templatesSeenByB = inTenantTx(tenantB.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM note_templates WHERE id = ?", Integer.class, UUID.fromString(templateId)));
        Integer vaccinesSeenByB = inTenantTx(tenantB.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM vaccine_records WHERE id = ?", Integer.class, UUID.fromString(vaccineId)));
        assertThat(templatesSeenByB).isZero();
        assertThat(vaccinesSeenByB).isZero();
        Integer templatesSeenByA = inTenantTx(tenantA.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM note_templates WHERE id = ?", Integer.class, UUID.fromString(templateId)));
        Integer vaccinesSeenByA = inTenantTx(tenantA.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM vaccine_records WHERE id = ?", Integer.class, UUID.fromString(vaccineId)));
        assertThat(templatesSeenByA).isEqualTo(1);
        assertThat(vaccinesSeenByA).isEqualTo(1);
    }
}

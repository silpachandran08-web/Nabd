package com.nabd.hms.clinical;

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

class NoteApiTest extends ApiTestBase {

    private static int pgDayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue() % 7;
    }

    private void addWorkingHours(String token, UUID doctorId) {
        exchange("/v1/doctors/" + doctorId + "/working-hours", HttpMethod.POST, authedJsonBody(token, Map.of(
                "dayOfWeek", pgDayOfWeek(LocalDate.now(ZoneOffset.UTC)),
                "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
    }

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

    @Test
    void autosaveUpsertCreatesAndUpdatesADraftNote() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc1@a.com", "+919600020001", false);
        String token = loginAndGetAccessToken(doc);
        addWorkingHours(token, doc.id());
        String patientId = registerPatient(token, "N1", "+919999920001");
        String queueEntryId = checkIn(token, patientId, doc.id());

        ResponseEntity<Map> first = exchange("/v1/clinical/notes/" + queueEntryId, HttpMethod.PATCH,
                authedJsonBody(token, Map.of("subjective", "Fever since 2 days", "plan", "")), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("status")).isEqualTo("draft");
        assertThat(first.getBody().get("subjective")).isEqualTo("Fever since 2 days");
        assertThat(first.getBody().get("patientId")).isEqualTo(patientId);

        ResponseEntity<Map> second = exchange("/v1/clinical/notes/" + queueEntryId, HttpMethod.PATCH,
                authedJsonBody(token, Map.of("subjective", "Fever since 2 days, now settling", "plan", "Paracetamol")), Map.class);
        assertThat(second.getBody().get("subjective")).isEqualTo("Fever since 2 days, now settling");
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id")); // same row, upserted not duplicated

        ResponseEntity<Map> fetched = exchange("/v1/clinical/notes/" + queueEntryId, HttpMethod.GET, authed(token), Map.class);
        assertThat(fetched.getBody().get("plan")).isEqualTo("Paracetamol");
    }

    @Test
    void signingLocksTheNoteFromFurtherEdits() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc2@a.com", "+919600020002", false);
        String token = loginAndGetAccessToken(doc);
        addWorkingHours(token, doc.id());
        String patientId = registerPatient(token, "N2", "+919999920002");
        String queueEntryId = checkIn(token, patientId, doc.id());
        exchange("/v1/clinical/notes/" + queueEntryId, HttpMethod.PATCH,
                authedJsonBody(token, Map.of("assessment", "Viral fever")), Map.class);

        ResponseEntity<Map> signed = exchange("/v1/clinical/notes/" + queueEntryId + "/sign", HttpMethod.POST, authed(token), Map.class);
        assertThat(signed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signed.getBody().get("status")).isEqualTo("signed");
        assertThat(signed.getBody().get("signedAt")).isNotNull();

        ResponseEntity<Map> blocked = exchange("/v1/clinical/notes/" + queueEntryId, HttpMethod.PATCH,
                authedJsonBody(token, Map.of("assessment", "trying to edit after signing")), Map.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody().get("type")).asString().contains("note-signed");
    }

    @Test
    void gettingANoteForAQueueEntryWithNoDraftYetIs404() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc3@a.com", "+919600020003", false);
        String token = loginAndGetAccessToken(doc);
        addWorkingHours(token, doc.id());
        String patientId = registerPatient(token, "N3", "+919999920003");
        String queueEntryId = checkIn(token, patientId, doc.id());

        ResponseEntity<Map> resp = exchange("/v1/clinical/notes/" + queueEntryId, HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void roleWithoutClinicalGrantIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "FrontDeskOnly", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "reception1@a.com", "+919600020004", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/clinical/notes/" + UUID.randomUUID(), HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}

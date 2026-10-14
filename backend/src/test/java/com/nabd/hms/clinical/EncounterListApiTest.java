package com.nabd.hms.clinical;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** DOC-005. Written to the TimelineApiTest conventions; NOT executed (no Docker/Postgres in this environment). */
class EncounterListApiTest extends ApiTestBase {

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
            exchange("/v1/queue/" + queueEntryId + "/status", HttpMethod.PATCH,
                    authedJsonBody(token, Map.of("status", s)), Map.class);
        }
    }

    @Test
    void listsOnlyTheCallersOwnConsultedVisitsWithNoteAndRxState() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "enc1@a.com", "+919800040001", false);
        SeededStaff other = seedStaff(tenant, roleId, "enc2@a.com", "+919800040002", false);
        String token = loginAndGetAccessToken(doc);
        String otherToken = loginAndGetAccessToken(other);
        String patientId = registerPatient(token, "E1", "+919999980001");

        String signed = checkIn(token, patientId, doc.id());
        exchange("/v1/clinical/notes/" + signed, HttpMethod.PATCH, authedJsonBody(token, Map.of("diagnosis", "x")), Map.class);
        exchange("/v1/clinical/notes/" + signed + "/sign", HttpMethod.POST, authed(token), Map.class);
        moveTo(token, signed, "waiting", "vitals_pending", "vitals_done", "in_consult");
        String unsigned = checkIn(token, patientId, doc.id());
        moveTo(token, unsigned, "waiting", "vitals_pending", "vitals_done", "in_consult");
        checkIn(token, patientId, doc.id()); // still waiting: never listed

        ResponseEntity<Map> all = exchange("/v1/clinical/encounters", HttpMethod.GET, authed(token), Map.class);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) all.getBody().get("data")).hasSize(2);

        ResponseEntity<Map> uns = exchange("/v1/clinical/encounters?state=unsigned", HttpMethod.GET, authed(token), Map.class);
        List<?> data = (List<?>) uns.getBody().get("data");
        assertThat(data).hasSize(1);
        assertThat(((Map<?, ?>) data.get(0)).get("queueEntryId")).isEqualTo(unsigned);
        assertThat(((Map<?, ?>) data.get(0)).get("noteState")).isEqualTo("none");

        ResponseEntity<Map> theirs = exchange("/v1/clinical/encounters", HttpMethod.GET, authed(otherToken), Map.class);
        assertThat((List<?>) theirs.getBody().get("data")).isEmpty();
    }

    @Test
    void rejectsAnUnknownStateAndAnInvertedRange() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "enc3@a.com", "+919800040003", false);
        String token = loginAndGetAccessToken(doc);

        assertThat(exchange("/v1/clinical/encounters?state=bogus", HttpMethod.GET, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/clinical/encounters?from=2026-02-02&to=2026-01-01", HttpMethod.GET, authed(token), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

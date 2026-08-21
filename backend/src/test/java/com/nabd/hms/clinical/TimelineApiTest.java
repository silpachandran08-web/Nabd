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

class TimelineApiTest extends ApiTestBase {

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
    void completedVisitWithDiagnosisAndSignedPrescriptionAppearsOnTheTimeline() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc7@a.com", "+919800030001", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = registerPatient(token, "T1", "+919999970001");
        String queueEntryId = checkIn(token, patientId, doc.id());

        exchange("/v1/clinical/notes/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "assessment", "Viral fever", "diagnosis", "Viral URI")), Map.class);
        exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Paracetamol")))), Map.class);
        exchange("/v1/clinical/prescriptions/" + queueEntryId + "/sign", HttpMethod.POST, authed(token), Map.class);
        moveTo(token, queueEntryId, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 100, "taxRatePercent", 0)))), Map.class);

        ResponseEntity<List> timeline = exchange("/v1/clinical/patients/" + patientId + "/timeline", HttpMethod.GET, authed(token), List.class);

        assertThat(timeline.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(timeline.getBody()).hasSize(1);
        Map<?, ?> encounter = (Map<?, ?>) timeline.getBody().get(0);
        assertThat(encounter.get("diagnosis")).isEqualTo("Viral URI");
        assertThat(encounter.get("medications")).isEqualTo("Paracetamol");
    }

    @Test
    void aVisitStillInProgressDoesNotAppearOnTheTimeline() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc8@a.com", "+919800030002", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = registerPatient(token, "T2", "+919999970002");
        checkIn(token, patientId, doc.id());

        ResponseEntity<List> timeline = exchange("/v1/clinical/patients/" + patientId + "/timeline", HttpMethod.GET, authed(token), List.class);

        assertThat(timeline.getBody()).isEmpty();
    }
}

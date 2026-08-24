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

class PrescriptionApiTest extends ApiTestBase {

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
    void upsertingItemsCreatesADraftPrescriptionAndSigningLocksIt() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc4@a.com", "+919800020001", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = registerPatient(token, "P1", "+919999960001");
        String queueEntryId = checkIn(token, patientId, doc.id());

        ResponseEntity<Map> upsert = exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Paracetamol", "dosage", "500mg", "frequency", "BD", "duration", "3 days")))), Map.class);
        assertThat(upsert.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(upsert.getBody().get("status")).isEqualTo("draft");
        assertThat((List<?>) upsert.getBody().get("items")).hasSize(1);

        ResponseEntity<Map> signed = exchange("/v1/clinical/prescriptions/" + queueEntryId + "/sign", HttpMethod.POST, authed(token), Map.class);
        assertThat(signed.getBody().get("status")).isEqualTo("signed");

        ResponseEntity<Map> blocked = exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Ibuprofen")))), Map.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody().get("type")).asString().contains("prescription-signed");
    }

    @Test
    void prescribingADrugMatchingARecordedAllergyIsBlockedWithoutAnOverrideReason() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc5@a.com", "+919800020002", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = registerPatient(token, "P2", "+919999960002");
        exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "Penicillin", "severity", "severe", "reaction", "anaphylaxis")), Map.class);
        String queueEntryId = checkIn(token, patientId, doc.id());

        ResponseEntity<Map> blocked = exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Amoxicillin-Penicillin combo", "dosage", "250mg")))), Map.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blocked.getBody().get("type")).asString().contains("allergy-conflict");

        ResponseEntity<Map> overridden = exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Amoxicillin-Penicillin combo", "dosage", "250mg",
                        "allergyOverrideReason", "Prior tolerance confirmed by patient")))), Map.class);
        assertThat(overridden.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void prescribingADrugMatchingAModerateAllergyIsAllowedButCarriesAWarning() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc5b@a.com", "+919800020012", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = registerPatient(token, "P2b", "+919999960012");
        exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "Sulfa", "severity", "moderate", "reaction", "rash")), Map.class);
        String queueEntryId = checkIn(token, patientId, doc.id());

        ResponseEntity<Map> resp = exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Sulfamethoxazole", "dosage", "400mg")))), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK); // moderate never blocks (NB-107)
        Map<?, ?> item = (Map<?, ?>) ((List<?>) resp.getBody().get("items")).get(0);
        assertThat(item.get("allergyWarning")).asString().contains("Sulfa").contains("moderate");
    }

    @Test
    void overridingASevereAllergyConflictIsAudited() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc5c@a.com", "+919800020013", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = registerPatient(token, "P2c", "+919999960013");
        exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "Penicillin", "severity", "severe", "reaction", "anaphylaxis")), Map.class);
        String queueEntryId = checkIn(token, patientId, doc.id());

        exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Penicillin-VK", "dosage", "500mg",
                        "allergyOverrideReason", "Desensitisation protocol under supervision")))), Map.class);

        Map<String, Object> audited = inTenantTx(tenant.id(), () -> jdbc.queryForMap(
                "SELECT action, actor_name, before, after FROM audit_log " +
                        "WHERE tenant_id = ? AND action = 'prescription.allergy_override' ORDER BY created_at DESC LIMIT 1",
                tenant.id()));
        assertThat(audited.get("actor_name")).isEqualTo("Test Staff");
        assertThat(audited.get("after").toString()).contains("Penicillin-VK").contains("Desensitisation protocol under supervision");
    }

    @Test
    void previousMedicinesOnlyReturnsSignedPrescriptions() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc6@a.com", "+919800020003", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = registerPatient(token, "P3", "+919999960003");
        String queueEntryId = checkIn(token, patientId, doc.id());
        exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Cetirizine")))), Map.class);

        ResponseEntity<List> beforeSign = exchange("/v1/clinical/patients/" + patientId + "/prescriptions", HttpMethod.GET, authed(token), List.class);
        assertThat(beforeSign.getBody()).isEmpty();

        exchange("/v1/clinical/prescriptions/" + queueEntryId + "/sign", HttpMethod.POST, authed(token), Map.class);

        ResponseEntity<List> afterSign = exchange("/v1/clinical/patients/" + patientId + "/prescriptions", HttpMethod.GET, authed(token), List.class);
        assertThat(afterSign.getBody()).hasSize(1);
    }

    @Test
    void aFavouriteRxSetCanBeSavedAndAppliedToADraftRerunningAllergyChecks() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc10@a.com", "+919800020005", false);
        String token = loginAndGetAccessToken(doc);

        ResponseEntity<Map> created = exchange("/v1/clinical/rx-sets", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Common cold combo",
                "items", List.of(Map.of("drugName", "Paracetamol", "dosage", "500mg", "frequency", "BD"),
                        Map.of("drugName", "Cetirizine", "dosage", "10mg", "frequency", "OD")))), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        String setId = (String) created.getBody().get("id");
        assertThat((List<?>) created.getBody().get("items")).hasSize(2);

        ResponseEntity<List> listed = exchange("/v1/clinical/rx-sets", HttpMethod.GET, authed(token), List.class);
        assertThat(listed.getBody()).hasSize(1);

        String patientId = registerPatient(token, "P4", "+919999960005");
        exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "Paracetamol", "severity", "severe", "reaction", "hives")), Map.class);
        String queueEntryId = checkIn(token, patientId, doc.id());

        ResponseEntity<Map> blocked = exchange("/v1/clinical/prescriptions/" + queueEntryId + "/apply-set/" + setId,
                HttpMethod.POST, authed(token), Map.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blocked.getBody().get("type")).asString().contains("allergy-conflict");
    }

    @Test
    void roleWithoutClinicalGrantIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "FrontDeskOnly", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "reception4@a.com", "+919800020004", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/clinical/prescriptions/" + UUID.randomUUID(), HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}

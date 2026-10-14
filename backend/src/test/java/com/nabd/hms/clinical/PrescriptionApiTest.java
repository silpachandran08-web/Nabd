package com.nabd.hms.clinical;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    void aSevereAllergyMatchCannotBeSignedUntilADecisionWithANamedReasonIsRecorded() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc5@a.com", "+919800020002", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = registerPatient(token, "P2", "+919999960002");
        exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "Penicillin", "severity", "severe", "reaction", "anaphylaxis")), Map.class);
        String queueEntryId = checkIn(token, patientId, doc.id());

        // Saving the draft is allowed and returns the structured warning, unresolved.
        ResponseEntity<Map> saved = exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Amoxicillin-Penicillin combo", "dosage", "250mg")))), Map.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody().get("unresolvedWarnings")).isEqualTo(1);

        // Signing is refused server-side while the warning is unresolved.
        ResponseEntity<Map> refused = exchange("/v1/clinical/prescriptions/" + queueEntryId + "/sign", HttpMethod.POST, authed(token), Map.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("type")).asString().contains("warning-unresolved");

        // A free-text or unknown reason is not accepted: it must be one of the named reasons.
        ResponseEntity<Map> badReason = exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Amoxicillin-Penicillin combo", "dosage", "250mg",
                        "overrides", List.of(Map.of("kind", "allergy", "reasonCode", "because")))))), Map.class);
        assertThat(badReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badReason.getBody().get("type")).asString().contains("invalid-override-reason");

        ResponseEntity<Map> decided = exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Amoxicillin-Penicillin combo", "dosage", "250mg",
                        "overrides", List.of(Map.of("kind", "allergy", "reasonCode", "direct_supervision")))))), Map.class);
        assertThat(decided.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(decided.getBody().get("unresolvedWarnings")).isEqualTo(0);

        // Re-saving the same line without resending the decision keeps it (never silently cleared).
        ResponseEntity<Map> resaved = exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Amoxicillin-Penicillin combo", "dosage", "500mg")))), Map.class);
        assertThat(resaved.getBody().get("unresolvedWarnings")).isEqualTo(0);

        ResponseEntity<Map> signed = exchange("/v1/clinical/prescriptions/" + queueEntryId + "/sign", HttpMethod.POST, authed(token), Map.class);
        assertThat(signed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signed.getBody().get("status")).isEqualTo("signed");
    }

    @Test
    void anAllergyRecordedAfterTheDraftWasSavedStillBlocksSigning() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc5d@a.com", "+919800020014", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = registerPatient(token, "P2d", "+919999960014");
        String queueEntryId = checkIn(token, patientId, doc.id());
        exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Ibuprofen", "dosage", "400mg")))), Map.class);

        exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "NSAID", "severity", "severe", "reaction", "angioedema")), Map.class);

        ResponseEntity<Map> refused = exchange("/v1/clinical/prescriptions/" + queueEntryId + "/sign", HttpMethod.POST, authed(token), Map.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
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
                        "overrides", List.of(Map.of("kind", "allergy", "reasonCode", "direct_supervision", "note", "Desensitisation protocol")))))), Map.class);

        Map<String, Object> audited = inTenantTx(tenant.id(), () -> jdbc.queryForMap(
                "SELECT action, actor_name, before, after FROM audit_log " +
                        "WHERE tenant_id = ? AND action = 'prescription.allergy_override' ORDER BY created_at DESC LIMIT 1",
                tenant.id()));
        assertThat(audited.get("actor_name")).isEqualTo("Test Staff");
        assertThat(audited.get("after").toString()).contains("Penicillin-VK").contains("direct_supervision").contains("Desensitisation protocol");
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

        ResponseEntity<Map> applied = exchange("/v1/clinical/prescriptions/" + queueEntryId + "/apply-set/" + setId,
                HttpMethod.POST, authed(token), Map.class);
        assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(applied.getBody().get("unresolvedWarnings")).isEqualTo(1); // flagged, not silently accepted

        ResponseEntity<Map> refused = exchange("/v1/clinical/prescriptions/" + queueEntryId + "/sign", HttpMethod.POST, authed(token), Map.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aPregnancyCautionForAWomanOfChildbearingAgeNeedsANamedReasonBeforeSigning() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc5e@a.com", "+919800020015", false);
        String token = loginAndGetAccessToken(doc);
        ResponseEntity<Map> reg = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "P2e", "phone", "+919999960015", "dob", "1995-01-01", "gender", "female")), Map.class);
        String queueEntryId = checkIn(token, (String) reg.getBody().get("id"), doc.id());
        exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Doxycycline", "dosage", "100mg")))), Map.class);

        assertThat(exchange("/v1/clinical/prescriptions/" + queueEntryId + "/sign", HttpMethod.POST, authed(token), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Doxycycline", "dosage", "100mg",
                        "overrides", List.of(Map.of("kind", "pregnancy", "reasonCode", "obstetric_consultation")))))), Map.class);

        assertThat(exchange("/v1/clinical/prescriptions/" + queueEntryId + "/sign", HttpMethod.POST, authed(token), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
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

    /** Clinical grants without {@code approve}: view/edit/create only, like a nurse who can prepare a line. */
    private static ModuleGrant clinicalEditNoApprove() {
        return new ModuleGrant("clinical", true, true, true, false, false, false, false);
    }

    private SeededStaff seedNurse(SeededTenant tenant, String email, String phone) {
        UUID nurseRole = seedRole(tenant.id(), "Nurse", false, clinicalEditNoApprove(), fullGrant("queue"), fullGrant("patients"));
        return seedStaff(tenant, nurseRole, email, phone, false);
    }

    // V1-007: a nurse (clinical:edit, not the visit doctor, no clinical:approve) cannot record a decision or sign.
    @Test
    void aNurseCannotOverrideOrSignAnotherCliniciansPrescription() {
        SeededTenant tenant = seedTenant();
        UUID doctorRole = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, doctorRole, "doc-v7a@a.com", "+919800030001", false);
        SeededStaff nurse = seedNurse(tenant, "nurse-v7a@a.com", "+919800030002");
        String docToken = loginAndGetAccessToken(doc);
        String nurseToken = loginAndGetAccessToken(nurse);
        String patientId = registerPatient(docToken, "V7a", "+919999970001");
        exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(docToken, Map.of("substance", "Penicillin", "severity", "severe", "reaction", "anaphylaxis")), Map.class);
        String queueEntryId = checkIn(docToken, patientId, doc.id());
        String path = "/v1/clinical/prescriptions/" + queueEntryId;

        // The nurse may prepare the draft line ...
        ResponseEntity<Map> draft = exchange(path, HttpMethod.PATCH, authedJsonBody(nurseToken, Map.of(
                "items", List.of(Map.of("drugName", "Amoxicillin", "dosage", "500mg")))), Map.class);
        assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(draft.getBody().get("unresolvedWarnings")).isEqualTo(1);

        // ... but cannot record the override decision,
        ResponseEntity<Map> override = exchange(path, HttpMethod.PATCH, authedJsonBody(nurseToken, Map.of(
                "items", List.of(Map.of("drugName", "Amoxicillin", "dosage", "500mg",
                        "overrides", List.of(Map.of("kind", "allergy", "reasonCode", "no_suitable_alternative")))))), Map.class);
        assertThat(override.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(override.getBody().get("type")).asString().contains("not-prescriber");

        // the visit's doctor records it,
        ResponseEntity<Map> doctorOverride = exchange(path, HttpMethod.PATCH, authedJsonBody(docToken, Map.of(
                "items", List.of(Map.of("drugName", "Amoxicillin", "dosage", "500mg",
                        "overrides", List.of(Map.of("kind", "allergy", "reasonCode", "no_suitable_alternative")))))), Map.class);
        assertThat(doctorOverride.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(doctorOverride.getBody().get("unresolvedWarnings")).isEqualTo(0);

        // and the nurse still cannot sign it.
        ResponseEntity<Map> nurseSign = exchange(path + "/sign", HttpMethod.POST, authed(nurseToken), Map.class);
        assertThat(nurseSign.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(nurseSign.getBody().get("type")).asString().contains("not-prescriber");
        ResponseEntity<Map> stillDraft = exchange(path, HttpMethod.GET, authed(docToken), Map.class);
        assertThat(stillDraft.getBody().get("status")).isEqualTo("draft");

        assertThat(exchange(path + "/sign", HttpMethod.POST, authed(docToken), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aCoveringClinicianWithClinicalApproveMayDecideAndSign() {
        SeededTenant tenant = seedTenant();
        UUID ownerRole = seedFullAccessRole(tenant.id());
        SeededStaff visitDoctor = seedStaff(tenant, ownerRole, "doc-v7b@a.com", "+919800030003", false);
        UUID coverRole = seedRole(tenant.id(), "Covering doctor", false, fullGrant("clinical"), fullGrant("queue"), fullGrant("patients"));
        SeededStaff cover = seedStaff(tenant, coverRole, "cover-v7b@a.com", "+919800030004", false);
        String docToken = loginAndGetAccessToken(visitDoctor);
        String coverToken = loginAndGetAccessToken(cover);
        String patientId = registerPatient(docToken, "V7b", "+919999970003");
        String queueEntryId = checkIn(docToken, patientId, visitDoctor.id());
        String path = "/v1/clinical/prescriptions/" + queueEntryId;
        exchange(path, HttpMethod.PATCH, authedJsonBody(docToken, Map.of("items", List.of(Map.of("drugName", "Cetirizine", "dosage", "10mg")))), Map.class);

        ResponseEntity<Map> signed = exchange(path + "/sign", HttpMethod.POST, authed(coverToken), Map.class);

        assertThat(signed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signed.getBody().get("status")).isEqualTo("signed");
    }

    // V1-004 / V3-006: sign and save take the patient's allergy-register advisory lock first, then the prescription
    // row lock. Deterministic proof: this test holds each lock in its own transaction, starts a sign request, and
    // waits until Postgres itself reports a backend blocked on a lock (pg_stat_activity). Only after the lock is
    // released may the request finish. Removing lockRegister / FOR UPDATE makes the "blocked" poll time out.
    // shortcut: proves each lock blocks sign, not the ordering between the two or the sign-vs-save interleaving.
    @Test
    void signWaitsForTheRegisterLockAndForThePrescriptionRowLock() throws Exception {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc-v4@a.com", "+919800030010", false);
        String token = loginAndGetAccessToken(doc);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // One fresh draft per lock: a signed prescription cannot be signed again.
            for (boolean advisory : new boolean[]{true, false}) {
                String patientId = registerPatient(token, advisory ? "Anwar Pillai" : "Beena Kurup", advisory ? "+919999970120" : "+919999970121");
                String entry = checkIn(token, patientId, doc.id());
                String path = "/v1/clinical/prescriptions/" + entry;
                exchange(path, HttpMethod.PATCH, authedJsonBody(token, Map.of("items", List.of(Map.of("drugName", "Paracetamol", "dosage", "500mg")))), Map.class);
                String lockSql = advisory
                        ? "SELECT pg_advisory_xact_lock(hashtextextended('allergy-register:" + tenant.id() + ":" + patientId + "', 0))"
                        : "SELECT id FROM prescriptions WHERE tenant_id = '" + tenant.id() + "' AND queue_entry_id = '" + entry + "' FOR UPDATE";

                // The sign request is submitted while this transaction holds the lock; the lock is released when the lambda returns.
                Future<ResponseEntity<Map>> signF = inTenantTx(tenant.id(), () -> {
                    jdbc.query(lockSql, rs -> {
                    });
                    Future<ResponseEntity<Map>> f = pool.submit(() -> exchange(path + "/sign", HttpMethod.POST, authed(token), Map.class));
                    try {
                        assertThat(waitUntilABackendIsBlockedOnALock()).as("sign is blocked by: %s", lockSql).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    assertThat(f.isDone()).as("sign must not finish while the lock is held").isFalse();
                    return f;
                });
                ResponseEntity<Map> signed = signF.get(15, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(signed.getStatusCode()).isEqualTo(HttpStatus.OK);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Polls pg_stat_activity until some backend is waiting on a heavyweight lock (row or advisory); false after ~10 s. */
    private boolean waitUntilABackendIsBlockedOnALock() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer blocked = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()", Integer.class);
            if (blocked != null && blocked > 0) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    // V1-004 backstop: the database refuses to change items of a signed prescription, whatever the app does.
    @Test
    void theDatabaseRefusesToChangeItemsOfASignedPrescription() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc-v4b@a.com", "+919800030011", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = registerPatient(token, "V4b", "+919999970011");
        String entry = checkIn(token, patientId, doc.id());
        exchange("/v1/clinical/prescriptions/" + entry, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Paracetamol", "dosage", "500mg")))), Map.class);
        exchange("/v1/clinical/prescriptions/" + entry + "/sign", HttpMethod.POST, authed(token), Map.class);

        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                inTenantTx(tenant.id(), () -> jdbc.update("UPDATE prescription_items SET drug_name = 'Tampered' WHERE tenant_id = ?", tenant.id())));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                inTenantTx(tenant.id(), () -> jdbc.update("DELETE FROM prescription_items WHERE tenant_id = ?", tenant.id())));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                inTenantTx(tenant.id(), () -> jdbc.update("UPDATE prescriptions SET status = 'draft' WHERE tenant_id = ?", tenant.id())));
    }

    // V3-002: the remaining holes in the signed-Rx backstop: re-parenting items out of it, deleting its header (which
    // cascades to the items) and TRUNCATE. Each must fail and leave the signed prescription intact.
    @Test
    void theDatabaseRefusesToMoveDeleteOrTruncateASignedPrescription() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc-v4c@a.com", "+919800030012", false);
        String token = loginAndGetAccessToken(doc);
        String signedEntry = checkIn(token, registerPatient(token, "V4c Signed", "+919999970012"), doc.id());
        String draftEntry = checkIn(token, registerPatient(token, "V4c Draft", "+919999970013"), doc.id());
        for (String e : new String[]{signedEntry, draftEntry}) {
            exchange("/v1/clinical/prescriptions/" + e, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                    "items", List.of(Map.of("drugName", "Paracetamol", "dosage", "500mg")))), Map.class);
        }
        exchange("/v1/clinical/prescriptions/" + signedEntry + "/sign", HttpMethod.POST, authed(token), Map.class);

        // (a) move the signed Rx's items onto the draft
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                inTenantTx(tenant.id(), () -> jdbc.update(
                        "UPDATE prescription_items SET prescription_id = (SELECT p.id FROM prescriptions p WHERE p.tenant_id = ? AND p.status = 'draft') " +
                                "WHERE tenant_id = ? AND prescription_id IN (SELECT p.id FROM prescriptions p WHERE p.tenant_id = ? AND p.status = 'signed')",
                        tenant.id(), tenant.id(), tenant.id())));
        // (b) delete the signed header (would cascade to its items)
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                inTenantTx(tenant.id(), () -> jdbc.update("DELETE FROM prescriptions WHERE tenant_id = ? AND status = 'signed'", tenant.id())));
        // (c) TRUNCATE on either table
        assertTruncateRefusedByTrigger("TRUNCATE prescription_items", "signed prescriptions are final");
        assertTruncateRefusedByTrigger("TRUNCATE prescriptions CASCADE", "signed prescriptions are final");

        Integer signedItems = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM prescription_items i JOIN prescriptions p ON p.id = i.prescription_id " +
                        "WHERE i.tenant_id = ? AND p.status = 'signed'", Integer.class, tenant.id()));
        assertThat(signedItems).isEqualTo(1);
    }

    // DOC-021 x Rx safety (NOT EXECUTED in the authoring environment: no Docker/Testcontainers).
    // Covers PrescriptionRepository.findPatientProfile (LEFT JOIN patient_pregnancy_status), the severe escalation,
    // the fresh decision it demands and the sign gate.
    @Test
    void aRecordedPregnantStatusEscalatesTheRxWarningRequiresAFreshDecisionAndNeverWeakensTheBaseline() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc21a@a.com", "+919800021001", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "P21a", "phone", "+919999921001", "dob", "1995-01-01", "gender", "female")), Map.class).getBody().get("id");
        String entry = checkIn(token, patientId, doc.id());
        String path = "/v1/clinical/prescriptions/" + entry;
        Map<String, Object> line = Map.of("drugName", "Warfarin", "dosage", "5mg");

        // No status row (LEFT JOIN yields NULL): the unchanged baseline caution for a woman of childbearing age.
        ResponseEntity<Map> baseline = exchange(path, HttpMethod.PATCH, authedJsonBody(token, Map.of("items", List.of(line))), Map.class);
        assertThat(baseline.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> baseWarning = pregnancyWarning(baseline);
        assertThat(baseWarning.get("severity")).isEqualTo("caution");
        assertThat(baseWarning.get("matched")).isEqualTo("pregnancy/lactation");

        // Decide the baseline warning, then record the patient as pregnant.
        exchange(path, HttpMethod.PATCH, authedJsonBody(token, Map.of("items", List.of(Map.of("drugName", "Warfarin", "dosage", "5mg",
                "overrides", List.of(Map.of("kind", "pregnancy", "reasonCode", "obstetric_consultation")))))), Map.class);
        exchange("/v1/clinical/patients/" + patientId + "/pregnancy-status", HttpMethod.PUT,
                authedJsonBody(token, Map.of("status", "pregnant")), Map.class);

        // The recorded status escalates to severe, matched differs, so the earlier generic decision does not carry over.
        ResponseEntity<Map> escalated = exchange(path, HttpMethod.GET, authed(token), Map.class);
        Map<?, ?> severe = pregnancyWarning(escalated);
        assertThat(severe.get("severity")).isEqualTo("severe");
        assertThat(severe.get("matched")).isEqualTo("pregnant");
        assertThat(severe.get("requiresDecision")).isEqualTo(true);
        assertThat(severe.get("resolved")).isEqualTo(false);
        assertThat(escalated.getBody().get("unresolvedWarnings")).isEqualTo(1);

        // Sign gate: refused while the escalated warning is undecided, allowed once a named reason is recorded.
        ResponseEntity<Map> refused = exchange(path + "/sign", HttpMethod.POST, authed(token), Map.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("type")).asString().contains("warning-unresolved");
        ResponseEntity<Map> decided = exchange(path, HttpMethod.PATCH, authedJsonBody(token, Map.of("items", List.of(Map.of(
                "drugName", "Warfarin", "dosage", "5mg",
                "overrides", List.of(Map.of("kind", "pregnancy", "reasonCode", "obstetric_consultation")))))), Map.class);
        assertThat(decided.getBody().get("unresolvedWarnings")).isEqualTo(0);

        // Lactating also escalates (same severity, its own matched value).
        exchange("/v1/clinical/patients/" + patientId + "/pregnancy-status", HttpMethod.PUT,
                authedJsonBody(token, Map.of("status", "lactating")), Map.class);
        Map<?, ?> lactating = pregnancyWarning(exchange(path, HttpMethod.GET, authed(token), Map.class));
        assertThat(lactating.get("severity")).isEqualTo("severe");
        assertThat(lactating.get("matched")).isEqualTo("lactating");

        // not_pregnant (and unknown) never remove the baseline: still at least a caution that needs a decision.
        for (String status : new String[]{"not_pregnant", "unknown"}) {
            exchange("/v1/clinical/patients/" + patientId + "/pregnancy-status", HttpMethod.PUT,
                    authedJsonBody(token, Map.of("status", status)), Map.class);
            Map<?, ?> after = pregnancyWarning(exchange(path, HttpMethod.GET, authed(token), Map.class));
            assertThat(after.get("severity")).as(status).isEqualTo("caution");
            assertThat(after.get("matched")).as(status).isEqualTo("pregnancy/lactation");
            assertThat(after.get("requiresDecision")).as(status).isEqualTo(true);
        }
    }

    @Test
    void aRecordedPregnantStatusEscalatesForAPatientWhoseBaselineRuleDoesNotApply() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc21b@a.com", "+919800021002", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = registerPatient(token, "P21b", "+919999921002"); // male: no baseline warning
        String entry = checkIn(token, patientId, doc.id());
        String path = "/v1/clinical/prescriptions/" + entry;
        ResponseEntity<Map> none = exchange(path, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Warfarin", "dosage", "5mg")))), Map.class);
        assertThat(none.getBody().get("unresolvedWarnings")).isEqualTo(0);

        exchange("/v1/clinical/patients/" + patientId + "/pregnancy-status", HttpMethod.PUT,
                authedJsonBody(token, Map.of("status", "pregnant")), Map.class);

        Map<?, ?> severe = pregnancyWarning(exchange(path, HttpMethod.GET, authed(token), Map.class));
        assertThat(severe.get("severity")).isEqualTo("severe");
        assertThat(exchange(path + "/sign", HttpMethod.POST, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    /** The single kind=pregnancy entry of the first item's structured warnings. */
    private static Map<?, ?> pregnancyWarning(ResponseEntity<Map> prescription) {
        List<?> items = (List<?>) prescription.getBody().get("items");
        List<?> warnings = (List<?>) ((Map<?, ?>) items.get(0)).get("warnings");
        List<? extends Map<?, ?>> pregnancy = warnings.stream().map(w -> (Map<?, ?>) w)
                .filter(w -> "pregnancy".equals(w.get("kind"))).toList();
        assertThat(pregnancy).hasSize(1);
        return pregnancy.get(0);
    }

    // DOC-033 (NOT EXECUTED in the authoring environment: no Docker/Testcontainers).
    @Test
    void structuredFieldsRoundTripAndABrandCannotHideAnAllergyConflict() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc33@a.com", "+919800033001", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = registerPatient(token, "P33", "+919999933001");
        exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "Penicillin", "severity", "severe", "reaction", "anaphylaxis")), Map.class);
        String entry = checkIn(token, patientId, doc.id());

        // generic is innocuous, the brand text carries the allergen: the line must still need a decision
        ResponseEntity<Map> saved = exchange("/v1/clinical/prescriptions/" + entry, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Paracetamol", "brandName", "Penicillin-X", "sigPattern", "1-0-1",
                        "route", "oral", "quantity", 10, "quantityUnit", "tablets")))), Map.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody().get("unresolvedWarnings")).isEqualTo(1);
        Map<?, ?> line = (Map<?, ?>) ((List<?>) saved.getBody().get("items")).get(0);
        assertThat(line.get("brandName")).isEqualTo("Penicillin-X");
        assertThat(line.get("sigPattern")).isEqualTo("1-0-1");
        assertThat(line.get("route")).isEqualTo("oral");
        assertThat(line.get("quantityUnit")).isEqualTo("tablets");
        ResponseEntity<Map> refused = exchange("/v1/clinical/prescriptions/" + entry + "/sign", HttpMethod.POST, authed(token), Map.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // an old client that omits the new fields still works
        ResponseEntity<Map> legacy = exchange("/v1/clinical/prescriptions/" + entry, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Paracetamol", "dosage", "500mg")))), Map.class);
        assertThat(legacy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(legacy.getBody().get("unresolvedWarnings")).isEqualTo(0);
    }

    /** TestRestTemplate expands a String URL as a template and re-encodes '%', so "%20" would arrive as a literal "%20".
     * A pre-built URI is sent as is: {@code encodedQuery} must already be percent-encoded. */
    private ResponseEntity<List> searchDrugs(String token, String encodedQuery) {
        return http.exchange(java.net.URI.create(url("/v1/clinical/drugs/search?q=" + encodedQuery)), HttpMethod.GET, authed(token), List.class);
    }

    @Test
    void drugSearchIsTenantScopedTrimmedAndMatchesPharmacyItemsAndHistory() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc33b@a.com", "+919800033002", false);
        String token = loginAndGetAccessToken(doc);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid")), Map.class);
        exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Amoxicillin 500mg capsule", "isRx", true, "price", 12.50, "stockQty", 5)), Map.class);
        String entry = checkIn(token, registerPatient(token, "P33b", "+919999933002"), doc.id());
        exchange("/v1/clinical/prescriptions/" + entry, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Amlodipine")))), Map.class);

        ResponseEntity<List> hits = searchDrugs(token, "am&limit=5");
        assertThat(hits.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(hits.getBody().toString()).contains("Amoxicillin 500mg capsule").contains("Amlodipine");
        assertThat(searchDrugs(token, "a").getBody()).isEmpty();
        assertThat(searchDrugs(token, "%25").getBody()).isEmpty();
        // Surrounding whitespace is trimmed (this reaches the SQL path: "am" after trimming has two characters).
        assertThat(searchDrugs(token, "%20am%20").getBody().toString())
                .contains("Amoxicillin 500mg capsule");
        // 2+ characters containing LIKE wildcards must be matched literally (ESCAPE path), never as "match everything".
        // a%  a_  (and a backslash) all start with a seeded-name prefix letter, so a wildcard bug would return both drugs.
        for (String wildcard : new String[]{"a%25", "a_", "%25%25", "_m", "a%5C"}) {
            assertThat(searchDrugs(token, wildcard).getBody())
                    .as("q=" + wildcard).isEmpty();
        }

        SeededTenant other = seedTenant();
        UUID otherRole = seedFullAccessRole(other.id());
        String otherToken = loginAndGetAccessToken(seedStaff(other, otherRole, "doc33c@b.com", "+919800033003", false));
        assertThat(searchDrugs(otherToken, "am").getBody()).isEmpty();
    }

    // DOC-N1 (written, not executed here: needs Docker/Testcontainers).
    @Test
    void printHeaderIsRefusedForADraftAndCarriesPrescriberAndRegistrationNoOnceSigned() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc-print@a.com", "+919800020091", false);
        String token = loginAndGetAccessToken(doc);
        exchange("/v1/staff/" + doc.id(), HttpMethod.PATCH, authedJsonBody(token, Map.of("professionalRegistrationNo", "KMC 78412")), Map.class);
        String patientId = registerPatient(token, "P-print", "+919999960091");
        String queueEntryId = checkIn(token, patientId, doc.id());
        exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Paracetamol", "dosage", "500mg", "frequency", "BD", "duration", "3 days")))), Map.class);
        String path = "/v1/clinical/prescriptions/" + queueEntryId + "/print-header";

        ResponseEntity<Map> draft = exchange(path, HttpMethod.GET, authed(token), Map.class);
        assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(draft.getBody().get("type")).asString().contains("prescription-not-signed");

        exchange("/v1/clinical/prescriptions/" + queueEntryId + "/sign", HttpMethod.POST, authed(token), Map.class);
        ResponseEntity<Map> signed = exchange(path, HttpMethod.GET, authed(token), Map.class);
        assertThat(signed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signed.getBody().get("prescriberId")).isEqualTo(doc.id().toString());
        assertThat(signed.getBody().get("professionalRegistrationNo")).isEqualTo("KMC 78412");
        assertThat(signed.getBody().get("clinicName")).isNotNull();
    }

    // DOC-035 (written, NOT executed: no Docker/Postgres in the authoring environment).
    private SeededStaff plainDoctor(SeededTenant tenant, String email, String phone) {
        UUID role = seedRole(tenant.id(), "Doctor " + email, false, clinicalEditNoApprove(), fullGrant("queue"), fullGrant("patients"));
        return seedStaff(tenant, role, email, phone, false);
    }

    @Test
    void aSetKeepsBrandSigRouteAndQuantityAndApplyingItStillFlagsABrandAllergy() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc35a@a.com", "+919800035001", false);
        String token = loginAndGetAccessToken(doc);
        ResponseEntity<Map> created = exchange("/v1/clinical/rx-sets", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Brand combo", "items", List.of(Map.of("drugName", "Paracetamol", "brandName", "Penicillin-X",
                        "sigPattern", "1-0-1", "route", "oral", "quantity", 10, "quantityUnit", "tablets")))), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> saved = (Map<?, ?>) ((List<?>) created.getBody().get("items")).get(0);
        assertThat(saved.get("brandName")).isEqualTo("Penicillin-X");
        assertThat(saved.get("sigPattern")).isEqualTo("1-0-1");
        assertThat(saved.get("route")).isEqualTo("oral");
        assertThat(saved.get("quantityUnit")).isEqualTo("tablets");
        assertThat(created.getBody().get("scope")).isEqualTo("mine");
        assertThat(created.getBody().get("lastUsedAt")).isNull();

        String patientId = registerPatient(token, "P35a", "+919999935001");
        exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "Penicillin", "severity", "severe", "reaction", "anaphylaxis")), Map.class);
        String entry = checkIn(token, patientId, doc.id());

        ResponseEntity<Map> applied = exchange("/v1/clinical/prescriptions/" + entry + "/apply-set/" + created.getBody().get("id"),
                HttpMethod.POST, authed(token), Map.class);
        assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> line = (Map<?, ?>) ((List<?>) applied.getBody().get("items")).get(0);
        assertThat(line.get("brandName")).isEqualTo("Penicillin-X");
        assertThat(line.get("sigPattern")).isEqualTo("1-0-1");
        assertThat(line.get("route")).isEqualTo("oral");
        assertThat(applied.getBody().get("unresolvedWarnings")).isEqualTo(1); // brand allergen flagged on apply, never auto-cleared

        ResponseEntity<List> listed = exchange("/v1/clinical/rx-sets", HttpMethod.GET, authed(token), List.class);
        assertThat(((Map<?, ?>) listed.getBody().get(0)).get("lastUsedAt")).isNotNull();
    }

    @Test
    void onlyTheOwnerOrAnApproverMayEditOrDeleteASetAndPrivateSetsAreInvisibleToOthers() {
        SeededTenant tenant = seedTenant();
        SeededStaff owner = plainDoctor(tenant, "own35@a.com", "+919800035002");
        SeededStaff other = plainDoctor(tenant, "oth35@a.com", "+919800035003");
        UUID approverRole = seedRole(tenant.id(), "Clinical lead", false, fullGrant("clinical"), fullGrant("queue"), fullGrant("patients"));
        SeededStaff approver = seedStaff(tenant, approverRole, "app35@a.com", "+919800035004", false);
        String ownerToken = loginAndGetAccessToken(owner);
        String otherToken = loginAndGetAccessToken(other);
        String approverToken = loginAndGetAccessToken(approver);

        String setId = (String) exchange("/v1/clinical/rx-sets", HttpMethod.POST, authedJsonBody(ownerToken, Map.of(
                "name", "Mine", "items", List.of(Map.of("drugName", "Cetirizine")))), Map.class).getBody().get("id");

        // private: invisible to another doctor, and not applicable
        assertThat(exchange("/v1/clinical/rx-sets", HttpMethod.GET, authed(otherToken), List.class).getBody()).isEmpty();
        // shared: visible with scope shared, but still not editable by a non-owner
        assertThat(exchange("/v1/clinical/rx-sets/" + setId, HttpMethod.PATCH,
                authedJsonBody(ownerToken, Map.of("scope", "shared")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> seen = exchange("/v1/clinical/rx-sets", HttpMethod.GET, authed(otherToken), List.class).getBody();
        assertThat(seen).hasSize(1);
        assertThat(((Map<?, ?>) seen.get(0)).get("scope")).isEqualTo("shared");
        assertThat(((Map<?, ?>) seen.get(0)).get("canEdit")).isEqualTo(false);
        assertThat(exchange("/v1/clinical/rx-sets/" + setId, HttpMethod.PATCH,
                authedJsonBody(otherToken, Map.of("name", "Hijack")), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/clinical/rx-sets/" + setId, HttpMethod.DELETE, authed(otherToken), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // a clinical:approve holder may edit; the owner may delete; unknown id is 404
        ResponseEntity<Map> renamed = exchange("/v1/clinical/rx-sets/" + setId, HttpMethod.PATCH,
                authedJsonBody(approverToken, Map.of("name", "Renamed", "items", List.of(Map.of("drugName", "Loratadine")))), Map.class);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().get("name")).isEqualTo("Renamed");
        assertThat(exchange("/v1/clinical/rx-sets/" + setId, HttpMethod.DELETE, authed(ownerToken), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange("/v1/clinical/rx-sets/" + setId, HttpMethod.DELETE, authed(ownerToken), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // M1-fix/m4 (written, NOT executed): another doctor's PRIVATE set does not exist for anyone else, approvers included.
    @Test
    void anotherDoctorsPrivateSetIs404OnPatchDeleteAndApplyEvenForAnApprover() {
        SeededTenant tenant = seedTenant();
        SeededStaff owner = plainDoctor(tenant, "own36@a.com", "+919800036002");
        SeededStaff other = plainDoctor(tenant, "oth36@a.com", "+919800036003");
        UUID leadRole = seedRole(tenant.id(), "Lead36", false, fullGrant("clinical"), fullGrant("queue"), fullGrant("patients"));
        SeededStaff lead = seedStaff(tenant, leadRole, "ld36@a.com", "+919800036004", false);
        String ownerToken = loginAndGetAccessToken(owner);
        String otherToken = loginAndGetAccessToken(other);
        String leadToken = loginAndGetAccessToken(lead);
        String setId = (String) exchange("/v1/clinical/rx-sets", HttpMethod.POST, authedJsonBody(ownerToken, Map.of(
                "name", "Private", "items", List.of(Map.of("drugName", "Cetirizine")))), Map.class).getBody().get("id");
        String patientId = registerPatient(leadToken, "P36", "+919999936001");
        String entry = checkIn(leadToken, patientId, lead.id());

        for (String token : List.of(otherToken, leadToken)) {
            assertThat(exchange("/v1/clinical/rx-sets/" + setId, HttpMethod.PATCH,
                    authedJsonBody(token, Map.of("name", "Probe")), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(exchange("/v1/clinical/rx-sets/" + setId, HttpMethod.DELETE, authed(token), Map.class)
                    .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(exchange("/v1/clinical/prescriptions/" + entry + "/apply-set/" + setId, HttpMethod.POST, authed(token), Map.class)
                    .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(exchange("/v1/clinical/rx-sets", HttpMethod.GET, authed(token), List.class).getBody()).isEmpty();
        }
        // untouched for the owner
        List<?> mine = exchange("/v1/clinical/rx-sets", HttpMethod.GET, authed(ownerToken), List.class).getBody();
        assertThat(mine).hasSize(1);
        assertThat(((Map<?, ?>) mine.get(0)).get("name")).isEqualTo("Private");
    }

    @Test
    void aClinicStandardNeedsClinicalApproveAndIsNotEditableByOrdinaryDoctors() {
        SeededTenant tenant = seedTenant();
        SeededStaff plain = plainDoctor(tenant, "pl35@a.com", "+919800035005");
        String plainToken = loginAndGetAccessToken(plain);
        Map<String, Object> body = Map.of("name", "Std", "scope", "standard", "items", List.of(Map.of("drugName", "Cetirizine")));
        assertThat(exchange("/v1/clinical/rx-sets", HttpMethod.POST, authedJsonBody(plainToken, body), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        UUID leadRole = seedRole(tenant.id(), "Lead", false, fullGrant("clinical"), fullGrant("queue"), fullGrant("patients"));
        String leadToken = loginAndGetAccessToken(seedStaff(tenant, leadRole, "ld35@a.com", "+919800035006", false));
        ResponseEntity<Map> std = exchange("/v1/clinical/rx-sets", HttpMethod.POST, authedJsonBody(leadToken, body), Map.class);
        assertThat(std.getBody().get("scope")).isEqualTo("standard");
        assertThat(std.getBody().get("doctorId")).isNull();
        String id = (String) std.getBody().get("id");

        List<?> seen = exchange("/v1/clinical/rx-sets", HttpMethod.GET, authed(plainToken), List.class).getBody();
        assertThat(((Map<?, ?>) seen.get(0)).get("canEdit")).isEqualTo(false);
        assertThat(exchange("/v1/clinical/rx-sets/" + id, HttpMethod.DELETE, authed(plainToken), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anItemMissingFromTheActivePharmacyMasterIsStaleButOnlyWhenThereIsAMasterToCompareWith() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc35s@a.com", "+919800035007", false);
        String token = loginAndGetAccessToken(doc);
        exchange("/v1/clinical/rx-sets", HttpMethod.POST, authedJsonBody(token, Map.of("name", "S", "items",
                List.of(Map.of("drugName", "Cetirizine"), Map.of("drugName", "Withdrawn drug")))), Map.class);

        Map<?, ?> noMaster = (Map<?, ?>) exchange("/v1/clinical/rx-sets", HttpMethod.GET, authed(token), List.class).getBody().get(0);
        assertThat(noMaster.get("staleCheckAvailable")).isEqualTo(false); // nothing to compare with: unknown, not "fine"
        assertThat(noMaster.get("staleItemCount")).isEqualTo(0);
        assertThat(((Map<?, ?>) ((List<?>) noMaster.get("items")).get(0)).get("stale")).isNull();

        inTenantTx(tenant.id(), () -> jdbc.update(
                "INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, tax_rate_percent, stock_qty) VALUES (?,?,?,?,?,?,?)",
                tenant.id(), "PH-CET", "cetirizine", "Pharmacy", 1, 0, 5));
        Map<?, ?> withMaster = (Map<?, ?>) exchange("/v1/clinical/rx-sets", HttpMethod.GET, authed(token), List.class).getBody().get(0);
        assertThat(withMaster.get("staleCheckAvailable")).isEqualTo(true);
        assertThat(withMaster.get("staleItemCount")).isEqualTo(1);
        List<?> items = (List<?>) withMaster.get("items");
        assertThat(((Map<?, ?>) items.get(0)).get("stale")).isEqualTo(false);
        assertThat(((Map<?, ?>) items.get(1)).get("stale")).isEqualTo(true);
    }

    // DOC-R3 (written, NOT executed in the authoring environment: Maven cannot resolve dependencies there).
    // Signing stores the allergy statement the sign gate checked; later register changes never alter it.
    @Test
    void signingSnapshotsTheAllergyStatementAndLaterRegisterChangesDoNotAlterIt() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc-r3a@a.com", "+919800079001", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = registerPatient(token, "R3a", "+919999979001");
        ResponseEntity<Map> dust = exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "Dust", "severity", "mild", "reaction", "sneezing")), Map.class);
        String dustId = (String) dust.getBody().get("id");
        String queueEntryId = checkIn(token, patientId, doc.id());
        ResponseEntity<Map> draft = exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Paracetamol", "dosage", "500mg")))), Map.class);
        assertThat(draft.getBody().containsKey("allergySnapshot")).isTrue();
        assertThat(draft.getBody().get("allergySnapshot")).isNull(); // a draft never carries one

        ResponseEntity<Map> signed = exchange("/v1/clinical/prescriptions/" + queueEntryId + "/sign", HttpMethod.POST, authed(token), Map.class);
        assertThat(signed.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> atSign = (Map<?, ?>) signed.getBody().get("allergySnapshot");
        assertThat(atSign.get("state")).isEqualTo("recorded");
        List<?> atSignAllergies = (List<?>) atSign.get("allergies");
        assertThat(atSignAllergies).hasSize(1);
        assertThat(((Map<?, ?>) atSignAllergies.get(0)).get("substance")).isEqualTo("Dust");
        assertThat(((Map<?, ?>) atSignAllergies.get(0)).get("severity")).isEqualTo("mild");
        assertThat(((Map<?, ?>) atSignAllergies.get(0)).get("reaction")).isEqualTo("sneezing");

        // The register changes after signing: a new allergy, and the old one removed.
        exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "Latex", "severity", "severe", "reaction", "urticaria")), Map.class);
        assertThat(exchange("/v1/clinical/allergies/" + dustId + "/deactivate", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("reason", "Entered on the wrong patient")), Void.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<?, ?> later = (Map<?, ?>) exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.GET, authed(token), Map.class)
                .getBody().get("allergySnapshot");
        assertThat(later).isEqualTo(atSign);
        Map<?, ?> previous = (Map<?, ?>) exchange("/v1/clinical/patients/" + patientId + "/prescriptions", HttpMethod.GET, authed(token), List.class)
                .getBody().get(0);
        assertThat(previous.get("allergySnapshot")).isEqualTo(atSign);

        // Database backstop: the snapshot of a signed prescription cannot be rewritten (V51 trigger).
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                inTenantTx(tenant.id(), () -> jdbc.update(
                        "UPDATE prescriptions SET allergy_snapshot = '{\"state\":\"not_assessed\",\"allergies\":[]}'::jsonb WHERE tenant_id = ?",
                        tenant.id())));
        String audited = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT after::text FROM audit_log WHERE tenant_id = ? AND action = 'prescription.signed' ORDER BY created_at DESC LIMIT 1",
                String.class, tenant.id()));
        assertThat(audited).contains("allergySnapshotState").contains("recorded");
    }

    // DOC-R3: "no known allergies" and "not assessed" are snapshotted as the distinct statements they are.
    @Test
    void noKnownAllergiesAndNotAssessedAreSnapshottedAsDistinctStatements() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc-r3b@a.com", "+919800079002", false);
        String token = loginAndGetAccessToken(doc);

        String notAsked = registerPatient(token, "R3b NA", "+919999979002");
        String naEntry = checkIn(token, notAsked, doc.id());
        exchange("/v1/clinical/prescriptions/" + naEntry, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Paracetamol")))), Map.class);
        Map<?, ?> na = (Map<?, ?>) exchange("/v1/clinical/prescriptions/" + naEntry + "/sign", HttpMethod.POST, authed(token), Map.class)
                .getBody().get("allergySnapshot");
        assertThat(na.get("state")).isEqualTo("not_assessed");
        assertThat((List<?>) na.get("allergies")).isEmpty();
        assertThat(na.get("documentedAt")).isNull();

        String nka = registerPatient(token, "R3b NKA", "+919999979003");
        assertThat(exchange("/v1/clinical/patients/" + nka + "/allergies/status", HttpMethod.PUT, authed(token), Map.class)
                .getBody().get("state")).isEqualTo("none_documented");
        String nkaEntry = checkIn(token, nka, doc.id());
        exchange("/v1/clinical/prescriptions/" + nkaEntry, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Paracetamol")))), Map.class);
        Map<?, ?> none = (Map<?, ?>) exchange("/v1/clinical/prescriptions/" + nkaEntry + "/sign", HttpMethod.POST, authed(token), Map.class)
                .getBody().get("allergySnapshot");
        assertThat(none.get("state")).isEqualTo("none_documented");
        assertThat((List<?>) none.get("allergies")).isEmpty();
        assertThat(none.get("documentedBy")).isEqualTo(doc.id().toString());
        assertThat(none.get("documentedByName")).isEqualTo("Test Staff");
        assertThat(none.get("documentedAt")).isNotNull();

        // An allergy recorded after signing does not turn the signed "no known allergies" into "recorded".
        exchange("/v1/clinical/patients/" + nka + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "Latex", "severity", "mild")), Map.class);
        Map<?, ?> after = (Map<?, ?>) exchange("/v1/clinical/prescriptions/" + nkaEntry, HttpMethod.GET, authed(token), Map.class)
                .getBody().get("allergySnapshot");
        assertThat(after).isEqualTo(none);
    }

    // DOC-R3: a prescription signed before V79 has no snapshot. It stays null (never filled from the current register),
    // and a draft can never carry a snapshot (CHECK constraint).
    @Test
    void aLegacySignedPrescriptionHasANullSnapshotAndADraftCannotCarryOne() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "doc-r3c@a.com", "+919800079003", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = registerPatient(token, "R3c", "+919999979004");
        exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(token, Map.of("substance", "Dust", "severity", "mild")), Map.class);
        String legacyEntry = checkIn(token, patientId, doc.id());
        UUID legacyId = UUID.randomUUID();
        // What a pre-V79 sign left behind: status signed, signed_at set, no allergy_snapshot.
        inTenantTx(tenant.id(), () -> jdbc.update(
                "INSERT INTO prescriptions (id, tenant_id, queue_entry_id, patient_id, doctor_id, status, signed_at) " +
                        "VALUES (?,?,?::uuid,?::uuid,?,'signed', now() - interval '30 days')",
                legacyId, tenant.id(), legacyEntry, patientId, doc.id()));

        ResponseEntity<Map> legacy = exchange("/v1/clinical/prescriptions/" + legacyEntry, HttpMethod.GET, authed(token), Map.class);
        assertThat(legacy.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(legacy.getBody().get("status")).isEqualTo("signed");
        assertThat(legacy.getBody().containsKey("allergySnapshot")).isTrue();
        assertThat(legacy.getBody().get("allergySnapshot")).isNull();

        String draftEntry = checkIn(token, registerPatient(token, "R3c Draft", "+919999979005"), doc.id());
        exchange("/v1/clinical/prescriptions/" + draftEntry, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Paracetamol")))), Map.class);
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                inTenantTx(tenant.id(), () -> jdbc.update(
                        "UPDATE prescriptions SET allergy_snapshot = '{\"state\":\"not_assessed\",\"allergies\":[]}'::jsonb " +
                                "WHERE tenant_id = ? AND status = 'draft'", tenant.id())));
    }
}

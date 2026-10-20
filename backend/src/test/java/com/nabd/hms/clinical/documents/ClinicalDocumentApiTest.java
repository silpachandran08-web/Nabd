package com.nabd.hms.clinical.documents;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** DOC-030. Written to the NoteApiTest conventions; NOT executed in the authoring environment (no Docker/Testcontainers). */
class ClinicalDocumentApiTest extends ApiTestBase {

    // CLN-005: signing needs a registered clinician. Every staff member these tests seed is one, so they keep testing what
    // they were written for; the licence refusals themselves are covered in ClinicalLicenceApiTest.
    @Override
    protected SeededStaff seedStaff(SeededTenant tenant, UUID roleId, String email, String mobilePhone, boolean mfaEnabled,
                                    boolean emailVerified, boolean mobileVerified) {
        return com.nabd.hms.support.ClinicianFixture.licensed(
                super.seedStaff(tenant, roleId, email, mobilePhone, mfaEnabled, emailVerified, mobileVerified));
    }

    private record Visit(String token, String queueEntryId, String patientId, SeededStaff doctor) {
    }

    private Visit seedVisit(SeededTenant tenant) {
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, "docs1@a.com", "+919600030001", false);
        String token = loginAndGetAccessToken(doc);
        exchange("/v1/doctors/" + doc.id() + "/working-hours", HttpMethod.POST, authedJsonBody(token, Map.of(
                "dayOfWeek", LocalDate.now(ZoneOffset.UTC).getDayOfWeek().getValue() % 7,
                "startTime", "00:00:00", "endTime", "23:45:00", "slotMinutes", 15)), Map.class);
        String patientId = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Doc Patient", "phone", "+919999930001", "dob", "1990-01-01", "gender", "male")), Map.class).getBody().get("id");
        String queueEntryId = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doc.id().toString())), Map.class).getBody().get("id");
        return new Visit(token, queueEntryId, patientId, doc);
    }

    private ResponseEntity<Map> createReferral(Visit v, Map<String, String> body) {
        return exchange("/v1/clinical/visits/" + v.queueEntryId() + "/documents", HttpMethod.POST,
                authedJsonBody(v.token(), Map.of("type", "referral", "body", body)), Map.class);
    }

    @Test
    void draftIsEditableThenIssueSnapshotsTheHeaderAndFreezesIt() {
        Visit v = seedVisit(seedTenant());
        ResponseEntity<Map> created = createReferral(v, Map.of("referredTo", "Cardiology"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("status")).isEqualTo("draft");
        assertThat(created.getBody().get("header")).isNull();
        String id = (String) created.getBody().get("id");

        // issuing with a required field missing is refused
        ResponseEntity<Map> incomplete = exchange("/v1/clinical/documents/" + id + "/issue", HttpMethod.POST, authed(v.token()), Map.class);
        assertThat(incomplete.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> patched = exchange("/v1/clinical/documents/" + id, HttpMethod.PATCH,
                authedJsonBody(v.token(), Map.of("body", Map.of("referredTo", "Cardiology", "reason", "Chest pain on exertion"))), Map.class);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> issued = exchange("/v1/clinical/documents/" + id + "/issue", HttpMethod.POST, authed(v.token()), Map.class);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(issued.getBody().get("status")).isEqualTo("issued");
        assertThat(issued.getBody().get("issuedAt")).isNotNull();
        Map<?, ?> header = (Map<?, ?>) issued.getBody().get("header");
        assertThat(((Map<?, ?>) header.get("patient")).get("name")).isEqualTo("Doc Patient");
        assertThat(((Map<?, ?>) header.get("clinic")).get("name")).isNotNull();

        ResponseEntity<Map> edit = exchange("/v1/clinical/documents/" + id, HttpMethod.PATCH,
                authedJsonBody(v.token(), Map.of("body", Map.of("referredTo", "Other"))), Map.class);
        assertThat(edit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(edit.getBody().get("type")).asString().contains("document-issued");
        ResponseEntity<Map> again = exchange("/v1/clinical/documents/" + id + "/issue", HttpMethod.POST, authed(v.token()), Map.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // database backstop (V54): each of these must fail with the trigger's own message, not just "any exception"
        UUID tenantId = v.doctor().tenantId();
        assertIssuedIsFinal(tenantId, () -> jdbc.update("UPDATE clinical_documents SET body = '{}'::jsonb WHERE id = ?::uuid", id), "update");
        assertIssuedIsFinal(tenantId, () -> jdbc.update("DELETE FROM clinical_documents WHERE id = ?::uuid", id), "delete");
        Integer stillThere = inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM clinical_documents WHERE id = ?::uuid AND status = 'issued'", Integer.class, id));
        assertThat(stillThere).isEqualTo(1);
    }

    /** The V54 trigger raises integrity_constraint_violation with "clinical document is issued and cannot be <op>". */
    private void assertIssuedIsFinal(UUID tenantId, java.util.function.Supplier<Integer> statement, String op) {
        org.springframework.dao.DataIntegrityViolationException e = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> inTenantTx(tenantId, () -> statement.get()));
        assertThat(e.getMostSpecificCause().getMessage()).contains("clinical document is issued and cannot be " + op);
    }

    @Test
    void theDatabaseStillAllowsEditingAndDeletingADraftAndIssuingIt() {
        Visit v = seedVisit(seedTenant());
        UUID tenantId = v.doctor().tenantId();
        String draft = (String) createReferral(v, Map.of("referredTo", "ENT")).getBody().get("id");
        String toDelete = (String) createReferral(v, Map.of("referredTo", "Delete me")).getBody().get("id");

        assertThat(inTenantTx(tenantId, () -> jdbc.update(
                "UPDATE clinical_documents SET body = '{\"referredTo\":\"Edited\"}'::jsonb WHERE id = ?::uuid", draft))).isEqualTo(1);
        assertThat(inTenantTx(tenantId, () -> jdbc.update(
                "DELETE FROM clinical_documents WHERE id = ?::uuid AND status = 'draft'", toDelete))).isEqualTo(1);
        // draft -> issued is the one permitted transition (the CHECK needs issued_at, issued_by and header together)
        assertThat(inTenantTx(tenantId, () -> jdbc.update(
                "UPDATE clinical_documents SET status = 'issued', issued_at = now(), issued_by = ?, header = '{}'::jsonb WHERE id = ?::uuid",
                v.doctor().id(), draft))).isEqualTo(1);
        assertThat(inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT status FROM clinical_documents WHERE id = ?::uuid", String.class, draft))).isEqualTo("issued");
    }

    @Test
    void onlyTheVisitDoctorOrClinicalApproveMayIssueButAnyClinicalEditorMayDraft() {
        SeededTenant tenant = seedTenant();
        Visit v = seedVisit(tenant);
        UUID nurseRole = seedRole(tenant.id(), "Nurse", false, new com.nabd.hms.common.ModuleGrant("clinical", true, true, true, false, false, false, false));
        String nurse = loginAndGetAccessToken(seedStaff(tenant, nurseRole, "nurse-doc@a.com", "+919600030005", false));
        UUID coverRole = seedRole(tenant.id(), "Covering doctor", false, fullGrant("clinical"));
        String cover = loginAndGetAccessToken(seedStaff(tenant, coverRole, "cover-doc@a.com", "+919600030006", false));

        // a nurse can draft and edit ...
        ResponseEntity<Map> created = exchange("/v1/clinical/visits/" + v.queueEntryId() + "/documents", HttpMethod.POST,
                authedJsonBody(nurse, Map.of("type", "referral", "body", Map.of("referredTo", "ENT", "reason", "Hearing loss"))), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) created.getBody().get("id");
        assertThat(exchange("/v1/clinical/documents/" + id, HttpMethod.PATCH,
                authedJsonBody(nurse, Map.of("body", Map.of("referredTo", "ENT", "reason", "Hearing loss, left"))), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // ... but cannot issue it in the doctor's name; the draft stays a draft
        ResponseEntity<Map> refused = exchange("/v1/clinical/documents/" + id + "/issue", HttpMethod.POST, authed(nurse), Map.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("type")).asString().contains("not-prescriber");
        assertThat(exchange("/v1/clinical/documents/" + id, HttpMethod.GET, authed(v.token()), Map.class).getBody().get("status"))
                .isEqualTo("draft");

        // a clinician holding clinical:approve may issue; the header still names the visit doctor
        ResponseEntity<Map> issued = exchange("/v1/clinical/documents/" + id + "/issue", HttpMethod.POST, authed(cover), Map.class);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> header = (Map<?, ?>) issued.getBody().get("header");
        assertThat(((Map<?, ?>) header.get("doctor")).get("id")).isEqualTo(v.doctor().id().toString());
    }

    @Test
    void theIssuedHeaderSnapshotsTheDoctorRegistrationNumberOrNullWhenUnset() {
        SeededTenant tenant = seedTenant();
        Visit v = seedVisit(tenant);
        // CLN-005: a doctor with no registration number can no longer issue, so a registered cover with clinical:approve
        // issues on their visit; the header still snapshots the VISIT doctor's number, null when they have none.
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE staff SET professional_registration_no = NULL WHERE id = ?", v.doctor().id()));
        String coverToken = loginAndGetAccessToken(seedStaff(tenant, v.doctor().roleId(), "docs-cover@a.com", "+919600030099", false));
        String unset = (String) createReferral(v, Map.of("referredTo", "ENT", "reason", "Hearing loss")).getBody().get("id");
        Map<?, ?> noReg = (Map<?, ?>) ((Map<?, ?>) exchange("/v1/clinical/documents/" + unset + "/issue", HttpMethod.POST,
                authed(coverToken), Map.class).getBody().get("header")).get("doctor");
        assertThat(noReg.containsKey("registrationNumber")).isTrue();
        assertThat(noReg.get("registrationNumber")).isNull();

        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE staff SET professional_registration_no = 'KMC 12345' WHERE id = ?", v.doctor().id()));
        String set = (String) createReferral(v, Map.of("referredTo", "Cardiology", "reason", "Murmur")).getBody().get("id");
        Map<?, ?> withReg = (Map<?, ?>) ((Map<?, ?>) exchange("/v1/clinical/documents/" + set + "/issue", HttpMethod.POST,
                authed(v.token()), Map.class).getBody().get("header")).get("doctor");
        assertThat(withReg.get("registrationNumber")).isEqualTo("KMC 12345");

        // later changes never rewrite an already issued header
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE staff SET professional_registration_no = 'CHANGED 1' WHERE id = ?", v.doctor().id()));
        Map<?, ?> reread = (Map<?, ?>) ((Map<?, ?>) exchange("/v1/clinical/documents/" + set, HttpMethod.GET, authed(v.token()), Map.class)
                .getBody().get("header")).get("doctor");
        assertThat(reread.get("registrationNumber")).isEqualTo("KMC 12345");
    }

    @Test
    void patchingADraftWithNoBodyLeavesTheBodyUnchanged() {
        Visit v = seedVisit(seedTenant());
        String id = (String) createReferral(v, Map.of("referredTo", "ENT", "reason", "Keep me")).getBody().get("id");

        for (Map<String, Object> empty : List.<Map<String, Object>>of(Map.of(), java.util.Collections.singletonMap("body", null))) {
            ResponseEntity<Map> resp = exchange("/v1/clinical/documents/" + id, HttpMethod.PATCH, authedJsonBody(v.token(), empty), Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat((Map<String, Object>) resp.getBody().get("body")).containsEntry("referredTo", "ENT").containsEntry("reason", "Keep me");
        }
        // an explicit empty object is still a deliberate replace
        ResponseEntity<Map> cleared = exchange("/v1/clinical/documents/" + id, HttpMethod.PATCH,
                authedJsonBody(v.token(), Map.of("body", Map.of())), Map.class);
        assertThat((Map<?, ?>) cleared.getBody().get("body")).isEmpty();
    }

    @Test
    void issuingWritesAnAuditRowWithoutClinicalText() {
        Visit v = seedVisit(seedTenant());
        String id = (String) createReferral(v, Map.of("referredTo", "ENT", "reason", "secret reason text")).getBody().get("id");
        exchange("/v1/clinical/documents/" + id + "/issue", HttpMethod.POST, authed(v.token()), Map.class);
        List<String> after = inTenantTx(v.doctor().tenantId(), () -> jdbc.queryForList(
                "SELECT after::text FROM audit_log WHERE action = 'clinical_document.issued' AND entity_id = ?::uuid", String.class, id));
        assertThat(after).hasSize(1);
        assertThat(after.get(0)).doesNotContain("secret reason text");
    }

    @Test
    void listForVisitAndGetReturnTheDocuments() {
        Visit v = seedVisit(seedTenant());
        createReferral(v, Map.of("referredTo", "ENT"));
        ResponseEntity<List> list = exchange("/v1/clinical/visits/" + v.queueEntryId() + "/documents", HttpMethod.GET, authed(v.token()), List.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody()).hasSize(1);
    }

    @Test
    void visitSummaryPrefillUsesOnlySignedSources() {
        Visit v = seedVisit(seedTenant());
        exchange("/v1/clinical/notes/" + v.queueEntryId(), HttpMethod.PATCH,
                authedJsonBody(v.token(), Map.of("diagnosis", "Viral fever", "plan", "Rest")), Map.class);
        ResponseEntity<Map> draftOnly = exchange("/v1/clinical/visits/" + v.queueEntryId() + "/documents/summary-prefill",
                HttpMethod.GET, authed(v.token()), Map.class);
        assertThat(draftOnly.getBody().get("noteSigned")).isEqualTo(false);
        assertThat((Map<?, ?>) draftOnly.getBody().get("body")).isEmpty();

        exchange("/v1/clinical/notes/" + v.queueEntryId() + "/sign", HttpMethod.POST, authed(v.token()), Map.class);
        ResponseEntity<Map> signed = exchange("/v1/clinical/visits/" + v.queueEntryId() + "/documents/summary-prefill",
                HttpMethod.GET, authed(v.token()), Map.class);
        assertThat(signed.getBody().get("noteSigned")).isEqualTo(true);
        assertThat(((Map<?, ?>) signed.getBody().get("body")).get("diagnosis")).isEqualTo("Viral fever");
    }

    @Test
    void unknownFieldAndUnknownVisitAreRejected() {
        Visit v = seedVisit(seedTenant());
        assertThat(createReferral(v, Map.of("restFrom", "2026-01-01")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> missing = exchange("/v1/clinical/visits/" + UUID.randomUUID() + "/documents", HttpMethod.POST,
                authedJsonBody(v.token(), Map.of("type", "referral", "body", Map.of())), Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void documentsAreTenantScoped() {
        Visit a = seedVisit(seedTenant());
        String id = (String) createReferral(a, Map.of("referredTo", "ENT")).getBody().get("id");
        SeededTenant other = seedTenant();
        UUID role = seedFullAccessRole(other.id());
        String otherToken = loginAndGetAccessToken(seedStaff(other, role, "docs2@b.com", "+919600030002", false));
        assertThat(exchange("/v1/clinical/documents/" + id, HttpMethod.GET, authed(otherToken), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void readerCanListButNotWriteAndNoGrantIsForbidden() {
        SeededTenant tenant = seedTenant();
        Visit v = seedVisit(tenant);
        UUID viewRole = seedRole(tenant.id(), "ViewOnly", false, new com.nabd.hms.common.ModuleGrant("clinical", true, false, false, false, false, false, false));
        String viewer = loginAndGetAccessToken(seedStaff(tenant, viewRole, "viewer@a.com", "+919600030003", false));
        assertThat(exchange("/v1/clinical/visits/" + v.queueEntryId() + "/documents", HttpMethod.GET, authed(viewer), List.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/clinical/visits/" + v.queueEntryId() + "/documents", HttpMethod.POST,
                authedJsonBody(viewer, Map.of("type", "referral", "body", Map.of())), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        UUID none = seedRole(tenant.id(), "FrontDeskOnly", false, fullGrant("queue"));
        String reception = loginAndGetAccessToken(seedStaff(tenant, none, "fd@a.com", "+919600030004", false));
        assertThat(exchange("/v1/clinical/visits/" + v.queueEntryId() + "/documents", HttpMethod.GET, authed(reception), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}

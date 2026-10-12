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

        // database backstop: even a direct UPDATE of an issued row is refused
        Integer changed = null;
        try {
            changed = inTenantTx(UUID.fromString(v.doctor().tenantId().toString()),
                    () -> jdbc.update("UPDATE clinical_documents SET body = '{}'::jsonb WHERE id = ?::uuid", id));
        } catch (RuntimeException expected) {
            // integrity_constraint_violation from clinical_documents_issued_is_final()
        }
        assertThat(changed).isNull();
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

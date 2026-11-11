package com.nabd.hms.billing.document;

import com.nabd.hms.common.ModuleGrant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FIN-035: claim pack from a paid visit: timing, licence gate, minimum-necessary content, audit, OTP secure-link delivery. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Fin035ClaimPackApiTest extends S15BillingTestBase {

    private record Visit(Env env, String patientId, String invoiceId, String visitId) {
    }

    /** A paid visit with a signed note (diagnosis plus history/assessment/plan that must stay out of the pack). */
    private Visit paidVisit(boolean licences) {
        Env e = env();
        String patientId = patient(e);
        String inv = invoice(e, patientId, "XRAY");
        pay(e, inv, 550);
        String visitId = inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT queue_entry_id::text FROM invoices WHERE id = ?::uuid", String.class, inv));
        inTenantTx(e.tenant().id(), () -> jdbc.update("INSERT INTO clinical_notes (tenant_id, queue_entry_id, patient_id, doctor_id, subjective, objective, assessment, plan, "
                        + "diagnosis, status, signed_at, signed_by) VALUES (?, ?::uuid, ?::uuid, ?, 'HISTORY-SECRET', 'EXAM-SECRET', 'ASSESS-SECRET', 'PLAN-SECRET', "
                        + "'Acute otitis media', 'signed', now(), ?)",
                e.tenant().id(), visitId, patientId, e.owner().id(), e.owner().id()));
        if (licences) {
            inTenantTx(e.tenant().id(), () -> {
                jdbc.update("UPDATE staff SET professional_registration_no = 'KMC-12345' WHERE id = ?", e.owner().id());
                jdbc.update("INSERT INTO licence_registry (tenant_id, licence_type, holder_name, number, region) VALUES (?, 'facility', 'Test Clinic', 'CEA-KA-9876', 'IN')",
                        e.tenant().id());
            });
        }
        return new Visit(e, patientId, inv, visitId);
    }

    private ResponseEntity<Map> generate(String token, String visitId, String format) {
        return exchange("/v1/billing/claim-packs", HttpMethod.POST, authedJsonBody(token, Map.of("visitId", visitId, "formatCode", format)), Map.class);
    }

    private ResponseEntity<byte[]> download(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setAccept(List.of(MediaType.APPLICATION_PDF, MediaType.parseMediaType("text/csv"), MediaType.APPLICATION_PROBLEM_JSON));
        return exchange(path, HttpMethod.GET, new HttpEntity<>(h), byte[].class);
    }

    @Test
    void aPaidVisitGivesAPdfAndCsvPackInUnder30SecondsWithOnlyTheFormatsFields() {
        Visit v = paidVisit(true);
        ResponseEntity<Map> formats = exchange("/v1/billing/claim-pack-formats?jurisdiction=IN", HttpMethod.GET, authed(v.env().ownerToken()), Map.class);
        assertThat(((List<Map>) formats.getBody().get("items"))).extracting(f -> f.get("code")).contains("GENERIC", "GENERIC_CSV");

        long t0 = System.nanoTime();
        ResponseEntity<Map> r = generate(v.env().ownerToken(), v.visitId(), "GENERIC_CSV");
        ResponseEntity<byte[]> pdf = download(v.env().ownerToken(), (String) r.getBody().get("documentUrl"));
        ResponseEntity<byte[]> csv = download(v.env().ownerToken(), (String) r.getBody().get("csvUrl"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).as("generate + download, ms").isLessThan(30_000);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);

        String text = String.join("\n", pdfLines(pdf.getBody()));
        assertThat(text).contains("CLAIM PACK - Generic with line data (CSV)", "Facility licence no: CEA-KA-9876", "Registration no: KMC-12345",
                "Invoice No: ", "PAYMENT RECEIPT", "CONSULTATION SUMMARY", "Acute otitis media", "Cat XRAY");
        assertThat(text).doesNotContain("HISTORY-SECRET", "EXAM-SECRET", "ASSESS-SECRET", "PLAN-SECRET"); // no full clinical notes
        String rows = new String(csv.getBody(), StandardCharsets.UTF_8);
        assertThat(rows).startsWith("invoiceNumber,invoiceDate,lineNo,description,sacHsn,quantity,unitPrice,taxRatePercent,lineTotal\r\n").contains("Cat XRAY");

        // GENERIC has no CSV
        ResponseEntity<Map> plain = generate(v.env().ownerToken(), v.visitId(), "GENERIC");
        assertThat(plain.getBody().get("csvUrl")).isNull();

        // the generation is audited with actor, visit, format and recipient
        Map<String, Object> audit = inTenantTx(v.env().tenant().id(), () -> jdbc.queryForMap(
                "SELECT actor_id::text AS actor, after::text AS after FROM audit_log WHERE action = 'billing.claim_pack_generated' AND entity_id = ?::uuid",
                r.getBody().get("packId")));
        assertThat(audit.get("actor")).isEqualTo(v.env().owner().id().toString());
        assertThat((String) audit.get("after")).contains(v.visitId()).contains("GENERIC_CSV").contains("\"recipient\": \"desk\"");
    }

    @Test
    void missingLicenceDataBlocksGenerationWithAFixItMessageAndAnUnpaidVisitIs409() {
        Visit v = paidVisit(false);
        ResponseEntity<Map> r = generate(v.env().ownerToken(), v.visitId(), "GENERIC");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("type")).asString().contains("LICENCE_DATA_MISSING");
        assertThat((List<String>) r.getBody().get("missing")).containsExactlyInAnyOrder("doctorRegistrationNo", "facilityLicence");
        assertThat((String) r.getBody().get("detail")).contains("registration number").contains("facility licence");

        Env e = v.env();
        String unpaid = invoice(e, patient(e), "CONS");
        String unpaidVisit = inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT queue_entry_id::text FROM invoices WHERE id = ?::uuid", String.class, unpaid));
        ResponseEntity<Map> notBilled = generate(e.ownerToken(), unpaidVisit, "GENERIC");
        assertThat(notBilled.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notBilled.getBody().get("type")).asString().contains("VISIT_NOT_BILLED");
    }

    @Test
    void billingViewAloneCannotPullTheClinicalSummaryAndAnotherClinicCannotReadThePack() {
        Visit v = paidVisit(true);
        String billingOnly = billingToken(v.env(), "BillingDesk", true, false, false);
        ResponseEntity<Map> r = generate(billingOnly, v.visitId(), "GENERIC");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().get("type")).asString().contains("clinical-summary-grant-required");
        UUID noBillingRole = seedRole(v.env().tenant().id(), "ClinOnly", false, new ModuleGrant("clinical", true, false, false, false, false, false, false));
        String clinicalOnly = loginAndGetAccessToken(seedStaff(v.env().tenant(), noBillingRole, "clin" + v.env().tenant().id() + "@a.com", phone(), false));
        assertThat(generate(clinicalOnly, v.visitId(), "GENERIC").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> ok = generate(v.env().ownerToken(), v.visitId(), "GENERIC");
        Env other = env();
        assertThat(download(other.ownerToken(), (String) ok.getBody().get("documentUrl")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(download(billingOnly, (String) ok.getBody().get("documentUrl")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void sendToPatientGoesOnlyThroughAnOtpSecureLinkAndIsAudited() {
        Visit v = paidVisit(true);
        inTenantTx(v.env().tenant().id(), () -> jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, 'secure_link', ?, 'en', 'UTILITY', 'Clinic', 'Hello {{1}}, a secure message is ready: {{2}}', 2, 'approved')""",
                v.env().tenant().id(), "m_" + UUID.randomUUID().toString().substring(0, 8)));
        ResponseEntity<Map> pack = generate(v.env().ownerToken(), v.visitId(), "GENERIC");
        String packId = (String) pack.getBody().get("packId");
        ResponseEntity<Map> sent = exchange("/v1/billing/claim-packs/" + packId + "/send", HttpMethod.POST,
                authedJsonBody(v.env().ownerToken(), Map.of("language", "en")), Map.class);
        assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String linkId = (String) sent.getBody().get("linkId");
        assertThat(sent.getBody().get("expiresAt")).isNotNull();

        Map<String, Object> link = inTenantTx(v.env().tenant().id(), () -> jdbc.queryForMap(
                "SELECT s.document_type, s.recipient_patient_id::text AS recipient, f.purpose, f.status FROM secure_links s "
                        + "JOIN file_objects f ON f.tenant_id = s.tenant_id AND f.id = s.document_id WHERE s.id = ?::uuid", linkId));
        assertThat(link).containsEntry("document_type", "document").containsEntry("recipient", v.patientId())
                .containsEntry("purpose", "claim_pack").containsEntry("status", "available");
        String audit = inTenantTx(v.env().tenant().id(), () -> jdbc.queryForObject(
                "SELECT after::text FROM audit_log WHERE action = 'billing.claim_pack_sent' AND entity_id = ?::uuid", String.class, packId));
        assertThat(audit).contains(v.visitId()).contains("GENERIC").contains(v.patientId()).contains(linkId);

        // a stranger cannot be the recipient
        String stranger = patient(v.env());
        ResponseEntity<Map> refused = exchange("/v1/billing/claim-packs/" + packId + "/send", HttpMethod.POST,
                authedJsonBody(v.env().ownerToken(), Map.of("recipientPatientId", stranger)), Map.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void csvCellsAreQuotedAndFormulaSafe() {
        assertThat(ClaimPackService.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(ClaimPackService.cell("=HYPERLINK(1)")).isEqualTo("'=HYPERLINK(1)");
        assertThat(ClaimPackService.cell("-12.50")).isEqualTo("-12.50");
        assertThat(ClaimPackService.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(ClaimPackService.cell(null)).isEmpty();
    }
}

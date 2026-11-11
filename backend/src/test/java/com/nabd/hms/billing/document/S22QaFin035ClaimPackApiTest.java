package com.nabd.hms.billing.document;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S22 QA (t1), FIN-035: Idempotency-Key replay returns the same pack (one row, one audit), the pack holds only that visit's
 * patient, invoice and diagnosis, and foreign / garbage / unknown inputs are refused without a 500.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S22QaFin035ClaimPackApiTest extends S15BillingTestBase {

    private record Visit(String patientId, String invoiceId, String visitId) {
    }

    private Visit paidVisit(Env e, String diagnosis) {
        String patientId = patient(e);
        String inv = invoice(e, patientId, "XRAY");
        pay(e, inv, 550);
        String visitId = inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT queue_entry_id::text FROM invoices WHERE id = ?::uuid", String.class, inv));
        inTenantTx(e.tenant().id(), () -> jdbc.update("INSERT INTO clinical_notes (tenant_id, queue_entry_id, patient_id, doctor_id, subjective, objective, assessment, plan, "
                        + "diagnosis, status, signed_at, signed_by) VALUES (?, ?::uuid, ?::uuid, ?, 's', 'o', 'a', 'p', ?, 'signed', now(), ?)",
                e.tenant().id(), visitId, patientId, e.owner().id(), diagnosis, e.owner().id()));
        return new Visit(patientId, inv, visitId);
    }

    private void licences(Env e) {
        inTenantTx(e.tenant().id(), () -> {
            jdbc.update("UPDATE staff SET professional_registration_no = 'KMC-22222' WHERE id = ?", e.owner().id());
            jdbc.update("INSERT INTO licence_registry (tenant_id, licence_type, holder_name, number, region) VALUES (?, 'facility', 'Test Clinic', 'CEA-KA-2222', 'IN')",
                    e.tenant().id());
        });
    }

    private ResponseEntity<Map> generate(String token, Object visitId, String format, String key) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", key);
        String body = "{\"visitId\":" + (visitId == null ? "null" : "\"" + visitId + "\"") + ",\"formatCode\":\"" + format + "\"}";
        return exchange("/v1/billing/claim-packs", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    private ResponseEntity<byte[]> download(String token, String path) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setAccept(List.of(MediaType.APPLICATION_PDF, MediaType.APPLICATION_PROBLEM_JSON));
        return exchange(path, HttpMethod.GET, new HttpEntity<>(h), byte[].class);
    }

    private String name(Env e, String patientId) {
        return inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT name FROM patients WHERE id = ?::uuid", String.class, patientId));
    }

    @Test
    void f01_aReplayWithTheSameIdempotencyKeyReturnsTheSamePack_oneRowOneAudit() {
        Env e = env();
        licences(e);
        Visit v = paidVisit(e, "Acute sinusitis");
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> first = generate(e.ownerToken(), v.visitId(), "GENERIC", key);
        ResponseEntity<Map> again = generate(e.ownerToken(), v.visitId(), "GENERIC", key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getBody().get("packId")).isEqualTo(first.getBody().get("packId"));
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM claim_packs WHERE visit_id = ?::uuid", Long.class, v.visitId()))).isEqualTo(1);
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'billing.claim_pack_generated' AND entity_id = ?::uuid",
                Long.class, first.getBody().get("packId")))).isEqualTo(1);
        // the same key with another body is not a silent replay of the first pack
        Visit other = paidVisit(e, "Otitis externa");
        ResponseEntity<Map> reused = generate(e.ownerToken(), other.visitId(), "GENERIC", key);
        System.out.println("QA FIN-035 f01: same key, other visit -> " + reused.getStatusCode() + " " + reused.getBody());
        assertThat(reused.getStatusCode().value()).as("key reuse with a different body").isIn(409, 422, 400);
    }

    @Test
    void f02_thePackHoldsOnlyThatVisitsPatientInvoiceAndDiagnosis() {
        Env e = env();
        licences(e);
        Visit x = paidVisit(e, "DIAG-X-Migraine");
        Visit y = paidVisit(e, "DIAG-Y-Gastritis");
        String yInvoiceNo = inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT invoice_number FROM invoices WHERE id = ?::uuid", String.class, y.invoiceId()));
        String xInvoiceNo = inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT invoice_number FROM invoices WHERE id = ?::uuid", String.class, x.invoiceId()));
        ResponseEntity<Map> r = generate(e.ownerToken(), x.visitId(), "GENERIC", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String text = String.join("\n", pdfLines(download(e.ownerToken(), (String) r.getBody().get("documentUrl")).getBody()));
        assertThat(text).contains(name(e, x.patientId())).contains("DIAG-X-Migraine").contains(xInvoiceNo);
        assertThat(text).doesNotContain(name(e, y.patientId())).doesNotContain("DIAG-Y-Gastritis").doesNotContain(yInvoiceNo);
    }

    @Test
    void f03_foreignGarbageAndUnknownInputsAreRefusedWithoutA500() {
        Env a = env(), b = env();
        licences(a);
        licences(b);
        Visit bv = paidVisit(b, "Other clinic");
        ResponseEntity<Map> foreign = generate(a.ownerToken(), bv.visitId(), "GENERIC", UUID.randomUUID().toString());
        assertThat(foreign.getStatusCode().value()).as("another clinic's visit -> " + foreign.getBody()).isIn(404, 409);
        assertThat(inTenantTx(a.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM claim_packs", Long.class))).isZero();

        Visit av = paidVisit(a, "Mine");
        assertThat(generate(a.ownerToken(), av.visitId(), "NO_SUCH_FORMAT", UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(generate(a.ownerToken(), "not-a-uuid", "GENERIC", UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(generate(a.ownerToken(), null, "GENERIC", UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(generate(a.ownerToken(), UUID.randomUUID(), "GENERIC", UUID.randomUUID().toString()).getStatusCode().value()).isIn(404, 409);
        assertThat(download(a.ownerToken(), "/v1/billing/claim-packs/not-a-uuid/document").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/billing/claim-pack-formats?jurisdiction=XX", HttpMethod.GET, authed(a.ownerToken()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

package com.nabd.hms.exchange;

import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.exchange.api.ConsultFhir;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IDC-055 against the real schema: a signed consult with Rx is read through the owning modules' APIs and mapped, with no web
 * request (TenantScope, as a worker would). TEST DATA: every name, medicine and finding is synthetic.
 */
class Idc055ConsultFhirApiTest extends ApiTestBase {

    @Autowired ConsultFhir fhir;
    @Autowired TenantScope tenantScope;

    private record Seeded(SeededTenant tenant, UUID doctor, UUID signer, UUID patient, UUID visit, UUID note) {
    }

    private static String phone() {
        return "+9198" + (10000000 + (int) (Math.random() * 89999999));
    }

    private void identifier(UUID tenant, UUID patient, String type, String value) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO patient_identifiers (id, tenant_id, patient_id, id_type, blind_index, value_enc, last4, verified_via) VALUES (?,?,?,?,?,?,?,'abdm_mobile_otp')",
                id, tenant, patient, type, (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""),
                cipher.encrypt(value.getBytes(StandardCharsets.UTF_8), FieldCipher.Aad.of(tenant, "patient_identifiers", "value_enc", id)), value.substring(value.length() - 4));
    }

    /** One visit with a note and a two-line prescription, signed (by a second clinician) or left as drafts. */
    private Seeded consult(boolean signed) {
        SeededTenant t = seedTenant();
        UUID role = seedFullAccessRole(t.id());
        UUID doctor = seedStaff(t, role, "idc055-d-" + UUID.randomUUID() + "@a.com", phone(), false).id();
        UUID signer = seedStaff(t, role, "idc055-s-" + UUID.randomUUID() + "@a.com", phone(), false).id();
        return inTenantTx(t.id(), () -> {
            jdbc.update("UPDATE staff SET name = 'Dr Test Visit', professional_registration_no = 'TEST/REG/55' WHERE id = ?", doctor);
            jdbc.update("UPDATE staff SET name = 'Dr Test Signer', hpr_id = '71-0000-0000-0055', hpr_verified_at = now() WHERE id = ?", signer);
            jdbc.update("INSERT INTO tenant_integrations_abdm (tenant_id, enabled, hfr_id, verified_at) VALUES (?, true, 'IN-TEST-HFR-55', now())", t.id());
            UUID patient = jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender, address, national_id_enc) "
                            + "VALUES (?, 'Test Patient Fiftyfive', ?, '1988-03-09', 'female', 'Test Street 55', ?) RETURNING id", UUID.class,
                    t.id(), "+919800055055", FhirFixtures.AADHAAR_SHAPED.getBytes(StandardCharsets.UTF_8));
            identifier(t.id(), patient, "ABHA_NUMBER", "91555555555555");
            identifier(t.id(), patient, "ABHA_ADDRESS", "test.fiftyfive@abdm");
            UUID visit = jdbc.queryForObject("INSERT INTO queue_entries (tenant_id, patient_id, doctor_id, department_id, queue_date, token_number, status) "
                    + "VALUES (?,?,?,(SELECT id FROM departments WHERE is_default),current_date,1,'completed') RETURNING id", UUID.class, t.id(), patient, doctor);
            UUID note = jdbc.queryForObject("INSERT INTO clinical_notes (tenant_id, queue_entry_id, patient_id, doctor_id, subjective, objective, assessment, plan, diagnosis, "
                            + "status, signed_at, signed_by) VALUES (?,?,?,?, 'Test subjective 55', NULL, 'Test assessment 55', 'Test plan 55', 'Test diagnosis 55', ?, ?, ?) RETURNING id",
                    UUID.class, t.id(), visit, patient, doctor, signed ? "signed" : "draft", signed ? java.sql.Timestamp.from(java.time.Instant.now()) : null, signed ? signer : null);
            UUID rx = jdbc.queryForObject("INSERT INTO prescriptions (tenant_id, queue_entry_id, patient_id, doctor_id) VALUES (?,?,?,?) RETURNING id", UUID.class,
                    t.id(), visit, patient, doctor);
            jdbc.update("INSERT INTO prescription_items (tenant_id, prescription_id, drug_name, brand_name, dosage, sig_pattern, frequency, duration, route, instructions, "
                    + "quantity, quantity_unit, display_order) VALUES (?,?, 'Test drug A', 'Test brand A', '1 unit', '1-0-1', NULL, '5 days', 'oral', 'Test instruction A', 10, 'tablet', 0)", t.id(), rx);
            jdbc.update("INSERT INTO prescription_items (tenant_id, prescription_id, drug_name, frequency, display_order) VALUES (?,?, 'Test drug B', 'twice daily', 1)", t.id(), rx);
            if (signed) {
                jdbc.update("UPDATE prescriptions SET status = 'signed', signed_at = now(), signed_by = ? WHERE id = ?", signer, rx);
                jdbc.update("INSERT INTO note_amendments (tenant_id, note_id, amended_by, reason, diagnosis) VALUES (?,?,?, 'Test amendment reason 55', 'Test amended diagnosis 55')",
                        t.id(), note, doctor);
            }
            return new Seeded(t, doctor, signer, patient, visit, note);
        });
    }

    private Optional<List<ObjectNode>> map(UUID tenant, UUID visit) {
        return tenantScope.run(tenant, () -> fhir.forVisit(visit));
    }

    @Test
    void aSignedConsultWithRxMapsToTheSixResourceTypesFromTheOwnersRecords_withoutAWebRequest() {
        Seeded s = consult(true);
        List<ObjectNode> out = map(s.tenant().id(), s.visit()).orElseThrow();
        assertThat(out.stream().map(r -> r.get("resourceType").asString()).toList())
                .containsExactly("Composition", "Patient", "Encounter", "Organization", "Practitioner", "Practitioner", "MedicationRequest", "MedicationRequest");

        JsonNode patient = FhirFixtures.one(out, "Patient");
        assertThat(patient.get("id").asString()).isEqualTo(s.patient().toString());
        assertThat(patient.get("name").get(0).get("text").asString()).isEqualTo("Test Patient Fiftyfive");
        assertThat(patient.get("gender").asString()).isEqualTo("female");
        assertThat(patient.get("birthDate").asString()).isEqualTo("1988-03-09");
        List<String> ids = new ArrayList<>();
        patient.get("identifier").forEach(i -> ids.add((i.has("system") ? i.get("system").asString() : "-") + "|" + i.get("value").asString()));
        String mrn = inTenantTx(s.tenant().id(), () -> jdbc.queryForObject("SELECT mrn FROM patients WHERE id = ?", String.class, s.patient()));
        assertThat(ids).containsExactly("urn:nabd:tenant:" + s.tenant().id() + ":mrn|" + mrn, "https://healthid.ndhm.gov.in|91-5555-5555-5555", "-|test.fiftyfive@abdm");

        JsonNode org = FhirFixtures.one(out, "Organization");
        assertThat(org.get("id").asString()).isEqualTo(s.tenant().id().toString());
        assertThat(org.get("name").asString()).isEqualTo("Test Clinic " + s.tenant().slug());
        assertThat(org.get("identifier").get(0).get("value").asString()).isEqualTo("IN-TEST-HFR-55");

        JsonNode enc = FhirFixtures.one(out, "Encounter");
        assertThat(enc.get("id").asString()).as("the first leg is its own encounter").isEqualTo(s.visit().toString());
        assertThat(enc.get("status").asString()).isEqualTo("finished");
        assertThat(enc.get("participant").get(0).get("individual").get("reference").asString()).isEqualTo("Practitioner/" + s.doctor());
        assertThat(enc.get("period").get("start").asString()).isNotBlank();

        // the signer (verified HPR id) authored the note and the Rx; the visit's doctor (council number only) amended it
        JsonNode comp = FhirFixtures.one(out, "Composition");
        assertThat(comp.get("id").asString()).isEqualTo(s.note().toString());
        assertThat(comp.get("status").asString()).isEqualTo("amended");
        assertThat(comp.get("author").get(0).get("reference").asString()).isEqualTo("Practitioner/" + s.signer());
        List<String> titles = new ArrayList<>();
        comp.get("section").forEach(x -> titles.add(x.get("title").asString()));
        assertThat(titles).containsExactly("Medications", "Subjective", "Assessment", "Plan", "Diagnosis", "Amendment");
        assertThat(comp.get("section").get(5).get("text").get("div").asString()).contains("Reason: Test amendment reason 55<br/>Diagnosis: Test amended diagnosis 55");
        assertThat(comp.get("section").get(5).get("author").get(0).get("reference").asString()).isEqualTo("Practitioner/" + s.doctor());
        for (JsonNode p : FhirFixtures.ofType(out, "Practitioner")) {
            JsonNode id = p.get("identifier").get(0);
            if (p.get("id").asString().equals(s.signer().toString())) {
                assertThat(id.get("system").asString() + "|" + id.get("value").asString()).isEqualTo("https://doctor.ndhm.gov.in|71-0000-0000-0055");
                assertThat(p.get("name").get(0).get("text").asString()).isEqualTo("Dr Test Signer");
            } else {
                assertThat(p.get("id").asString()).isEqualTo(s.doctor().toString());
                assertThat(id.has("system")).isFalse();
                assertThat(id.get("value").asString()).isEqualTo("TEST/REG/55");
            }
        }

        List<JsonNode> meds = FhirFixtures.ofType(out, "MedicationRequest");
        assertThat(meds.get(0).get("medicationCodeableConcept").toString()).isEqualTo("{\"text\":\"Test drug A (Test brand A)\"}");
        assertThat(meds.get(0).get("dosageInstruction").get(0).get("text").asString()).isEqualTo("1 unit, 1-0-1, 5 days");
        assertThat(meds.get(0).get("dispenseRequest").get("quantity").get("unit").asString()).isEqualTo("tablet");
        assertThat(meds.get(0).get("dispenseRequest").get("quantity").get("value").decimalValue()).isEqualByComparingTo("10");
        assertThat(meds.get(1).get("medicationCodeableConcept").toString()).isEqualTo("{\"text\":\"Test drug B\"}");
        assertThat(meds.get(1).get("dosageInstruction").get(0).get("text").asString()).isEqualTo("twice daily");
        for (JsonNode m : meds) {
            assertThat(m.get("requester").get("reference").asString()).isEqualTo("Practitioner/" + s.signer());
            assertThat(m.get("encounter").get("reference").asString()).isEqualTo("Encounter/" + s.visit());
            assertThat(m.get("authoredOn").asString()).isNotBlank();
        }

        // the sealed national id, the phone and the address never leave the patient module
        assertThat(out.toString()).doesNotContain(FhirFixtures.AADHAAR_SHAPED).doesNotContain("9800055055").doesNotContain("Test Street 55");
    }

    @Test
    void draftsAreNeverMapped_anotherClinicSeesNothing_andTheCallMustBeInsideATenantTransaction() {
        Seeded draft = consult(false);
        assertThat(map(draft.tenant().id(), draft.visit())).as("an unsigned note").isEmpty();

        Seeded signed = consult(true);
        assertThat(map(signed.tenant().id(), signed.visit())).isPresent();
        assertThat(map(draft.tenant().id(), signed.visit())).as("another clinic's visit").isEmpty();
        assertThat(map(signed.tenant().id(), UUID.randomUUID())).as("an unknown visit").isEmpty();
        assertThatThrownBy(() -> fhir.forVisit(signed.visit())).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aSignedNoteWithOnlyADraftPrescriptionMapsWithoutAnyMedicine() {
        Seeded s = consult(false);
        inTenantTx(s.tenant().id(), () -> jdbc.update("UPDATE clinical_notes SET status = 'signed', signed_at = now(), signed_by = ? WHERE id = ?", s.signer(), s.note()));
        List<ObjectNode> out = map(s.tenant().id(), s.visit()).orElseThrow();
        assertThat(FhirFixtures.ofType(out, "MedicationRequest")).isEmpty();
        assertThat(out.toString()).doesNotContain("Test drug").doesNotContain("Medications");
        assertThat(FhirFixtures.one(out, "Composition").get("status").asString()).isEqualTo("final");
    }
}

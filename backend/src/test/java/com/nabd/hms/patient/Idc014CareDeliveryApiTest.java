package com.nabd.hms.patient;

import com.nabd.hms.messaging.NotificationService;
import com.nabd.hms.shared.events.consumer.EventConsumer;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IDC-014: dual delivery within the scope the patient allowed. The scope matrix (logistics / clinical / marketing x FULL /
 * BOOK_ONLY / pending / withdrawn consent), the visit-instructions fan-out, the elderly missed-visit alert and the send-time
 * re-check that stops a message to a caregiver who lost the scope after it was requested.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Idc014CareDeliveryApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(7000);

    @Autowired List<EventConsumer> consumers;
    @Autowired NotificationService notifications;
    @Autowired com.nabd.hms.patient.api.PatientRefs refs;
    @Autowired ObjectMapper json;

    private record Family(SeededTenant t, UUID doctor, UUID father, UUID full, UUID bookOnly, UUID pending) {
    }

    private static String phone() {
        return "+9197" + (20000000 + SEQ.incrementAndGet() * 13);
    }

    private UUID patient(SeededTenant t, String name, String dob, boolean messaging) {
        UUID id = inTenantTx(t.id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,?,?::date,'male') RETURNING id",
                UUID.class, t.id(), name, phone(), dob));
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'data_processing')", t.id(), id));
        if (messaging) {
            inTenantTx(t.id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'messaging')", t.id(), id));
        }
        return id;
    }

    private void proxy(SeededTenant t, UUID caregiver, UUID subject, String relation, String scope, String status) {
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO patient_proxies (tenant_id, proxy_patient_id, subject_patient_id, relation, scope, status, accepted_at) VALUES (?,?,?,?,?,?,?)",
                t.id(), caregiver, subject, relation, scope, status, "active".equals(status) ? java.sql.Timestamp.from(java.time.Instant.now()) : null));
    }

    /** A father (aged 70 unless given) with a son on FULL, a daughter on BOOK_ONLY and a nephew still pending; templates approved. */
    private Family family(String fatherDob) {
        SeededTenant t = seedTenant();
        for (String name : List.of("visit_instructions", "missed_visit_alert")) {
            inTenantTx(t.id(), () -> jdbc.update("INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status) "
                    + "VALUES (?, ?, ?, 'en', 'UTILITY', 'Clinic', 'A message about {{1}}', 1, 'approved')", t.id(), name, "m_" + UUID.randomUUID().toString().replace("-", "")));
        }
        SeededStaff doc = seedStaff(t, seedFullAccessRole(t.id()), "idc014-" + SEQ.incrementAndGet() + "@a.com", phone(), false);
        UUID father = patient(t, "Raghavan Nair", fatherDob, true);
        UUID son = patient(t, "Arun Nair", "1985-01-01", true);
        UUID daughter = patient(t, "Meera Nair", "1988-01-01", true);
        UUID nephew = patient(t, "Vivek Menon", "1990-01-01", true);
        proxy(t, son, father, "son", "FULL", "active");
        proxy(t, daughter, father, "daughter", "BOOK_ONLY", "active");
        proxy(t, nephew, father, "nephew", null, "pending");
        return new Family(t, doc.id(), father, son, daughter, nephew);
    }

    private ResponseEntity<String> recipients(Family f, String cls, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.set("X-Internal-Token", token);
        String ref = inTenantTx(f.t().id(), () -> refs.refFor(f.father()));
        return http.exchange(url("/internal/v1/patients/" + ref + "/recipients?tenantId=" + f.t().id() + "&class=" + cls), HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    private List<String> relations(Family f, String cls) {
        ResponseEntity<String> r = recipients(f, cls, INTERNAL_COMMS_TOKEN);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((List<Map<String, Object>>) json.readValue(r.getBody(), List.class)).stream().map(m -> {
            assertThat(m).containsEntry("channel", "whatsapp").containsOnlyKeys("recipientRef", "channel", "relation");
            return (String) m.get("relation");
        }).toList();
    }

    private void consume(Family f, String group, String type, Map<String, Object> data) {
        EventConsumer c = consumers.stream().filter(x -> x.group().equals(group)).findFirst().orElseThrow();
        inTenantTx(f.t().id(), () -> c.handle(new EventConsumer.Event("ev:in:x", "1-0", UUID.randomUUID().toString(), type, f.t().id(), null, 1, json.valueToTree(data))));
    }

    /** A closed visit whose doctor issued a visit summary. */
    private UUID closedVisit(Family f) {
        UUID entry = inTenantTx(f.t().id(), () -> jdbc.queryForObject("INSERT INTO queue_entries (tenant_id, patient_id, doctor_id, department_id, queue_date, token_number) VALUES (?,?,?,(SELECT id FROM departments WHERE is_default),current_date,?) RETURNING id",
                UUID.class, f.t().id(), f.father(), f.doctor(), SEQ.incrementAndGet()));
        inTenantTx(f.t().id(), () -> jdbc.update("INSERT INTO clinical_documents (tenant_id, patient_id, queue_entry_id, doctor_id, doc_type, status, body, header, created_by, issued_by, issued_at) "
                + "VALUES (?,?,?,?,'visit_summary','issued','{}'::jsonb,'{}'::jsonb,?,?,now())", f.t().id(), f.father(), entry, f.doctor(), f.doctor(), f.doctor()));
        return entry;
    }

    private List<UUID> sentTo(Family f, String purpose) {
        return inTenantTx(f.t().id(), () -> jdbc.queryForList("SELECT recipient_patient_id FROM notification_requests WHERE purpose = ? AND status = 'requested' ORDER BY created_at", UUID.class, purpose));
    }

    @Test
    void scopeMatrix_logisticsReachesEveryLiveProxy_clinicalOnlyRecordScope_marketingThePatientAlone() {
        Family f = family("1955-06-01");
        assertThat(relations(f, "logistics")).containsExactly("self", "son", "daughter", "nephew");
        assertThat(relations(f, "clinical")).containsExactly("self", "son");
        assertThat(relations(f, "marketing")).isEmpty(); // no marketing consent on file
        assertThat(recipients(f, "logistics", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(recipients(f, "logistics", "wrong").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(recipients(f, "gossip", INTERNAL_COMMS_TOKEN).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ac1_ac2_aClosedVisitWithInstructionsGoesToThePatientAndTheFullProxy_notToBookOnly() {
        Family f = family("1955-06-01");
        UUID entry = closedVisit(f);
        consume(f, "care-visit-instructions", "visit.completed.v1", Map.of("entryId", entry.toString(), "encounterId", UUID.randomUUID().toString()));
        consume(f, "care-visit-instructions", "visit.completed.v1", Map.of("entryId", entry.toString(), "encounterId", UUID.randomUUID().toString())); // redelivery
        assertThat(sentTo(f, "visit_instructions")).containsExactlyInAnyOrder(f.father(), f.full());
        UUID req = inTenantTx(f.t().id(), () -> jdbc.queryForObject("SELECT id FROM notification_requests WHERE purpose = 'visit_instructions' AND recipient_patient_id = ?", UUID.class, f.full()));
        assertThat(notifications.claim(req).params()).containsExactly("Raghavan"); // first name only: no clinical text in the body

        UUID noSummary = inTenantTx(f.t().id(), () -> jdbc.queryForObject("INSERT INTO queue_entries (tenant_id, patient_id, doctor_id, department_id, queue_date, token_number) VALUES (?,?,?,(SELECT id FROM departments WHERE is_default),current_date,?) RETURNING id",
                UUID.class, f.t().id(), f.father(), f.doctor(), SEQ.incrementAndGet()));
        consume(f, "care-visit-instructions", "visit.completed.v1", Map.of("entryId", noSummary.toString()));
        assertThat(sentTo(f, "visit_instructions")).hasSize(2); // no instructions, no message
    }

    @Test
    void sendTime_aCaregiverDowngradedAfterTheRequestGetsNothing() {
        Family f = family("1955-06-01");
        consume(f, "care-visit-instructions", "visit.completed.v1", Map.of("entryId", closedVisit(f).toString()));
        UUID req = inTenantTx(f.t().id(), () -> jdbc.queryForObject("SELECT id FROM notification_requests WHERE purpose = 'visit_instructions' AND recipient_patient_id = ?", UUID.class, f.full()));
        inTenantTx(f.t().id(), () -> jdbc.update("UPDATE patient_proxies SET scope = 'BOOK_ONLY' WHERE proxy_patient_id = ?", f.full()));
        assertThatThrownBy(() -> notifications.claim(req)).isInstanceOf(com.nabd.hms.common.ApiException.class);
        assertThat(inTenantTx(f.t().id(), () -> jdbc.queryForObject("SELECT status FROM notification_requests WHERE id = ?", String.class, req))).isEqualTo("cancelled");
        UUID own = inTenantTx(f.t().id(), () -> jdbc.queryForObject("SELECT id FROM notification_requests WHERE purpose = 'visit_instructions' AND recipient_patient_id = ?", UUID.class, f.father()));
        assertThat(notifications.claim(own).params()).containsExactly("Raghavan"); // the patient's own copy still goes
    }

    @Test
    void ac3_anElderlyMembersNoShowAlertsEveryCaregiver_notThePatient_andAYoungerMemberTriggersNothing() {
        Family f = family("1950-03-03");
        UUID appt = inTenantTx(f.t().id(), () -> jdbc.queryForObject("INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time) VALUES (?,?,?,now() - interval '1 hour', now() - interval '45 minutes') RETURNING id",
                UUID.class, f.t().id(), f.father(), f.doctor()));
        consume(f, "care-missed-visit", "scheduling.appointment.no_show.v1", Map.of("appointmentId", appt.toString(), "markedBy", "staff"));
        assertThat(sentTo(f, "missed_visit_alert")).containsExactlyInAnyOrder(f.full(), f.bookOnly(), f.pending());

        Family young = family("1985-03-03");
        UUID appt2 = inTenantTx(young.t().id(), () -> jdbc.queryForObject("INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time) VALUES (?,?,?,now() - interval '1 hour', now() - interval '45 minutes') RETURNING id",
                UUID.class, young.t().id(), young.father(), young.doctor()));
        consume(young, "care-missed-visit", "scheduling.appointment.no_show.v1", Map.of("appointmentId", appt2.toString()));
        assertThat(sentTo(young, "missed_visit_alert")).isEmpty();
    }

    @Test
    void ac4_withdrawnMessagingConsentStopsBothRecipients_beforeAndAfterTheRequest() {
        Family f = family("1955-06-01");
        consume(f, "care-visit-instructions", "visit.completed.v1", Map.of("entryId", closedVisit(f).toString()));
        List<UUID> pending = inTenantTx(f.t().id(), () -> jdbc.queryForList("SELECT id FROM notification_requests WHERE purpose = 'visit_instructions'", UUID.class));
        assertThat(pending).hasSize(2);
        inTenantTx(f.t().id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, withdrawn_at) VALUES (?,?,'messaging',now())", f.t().id(), f.father()));
        for (UUID id : pending) {
            assertThatThrownBy(() -> notifications.claim(id)).isInstanceOf(com.nabd.hms.common.ApiException.class);
        }
        assertThat(relations(f, "clinical")).isEmpty();
        assertThat(relations(f, "logistics")).isEmpty();
        consume(f, "care-visit-instructions", "visit.completed.v1", Map.of("entryId", closedVisit(f).toString()));
        assertThat(sentTo(f, "visit_instructions")).isEmpty();
    }
}

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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S21 QA (t1), IDC-014: a caregiver REVOKED (not downgraded) after the request gets nothing at send time; a caregiver's own
 * withdrawn messaging consent; the missed-visit alert when the patient withdrew messaging consent; the 60th-birthday boundary;
 * the internal recipients endpoint does not answer for another tenant's ref.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S21QaIdc014CareApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(21700);

    @Autowired List<EventConsumer> consumers;
    @Autowired NotificationService notifications;
    @Autowired com.nabd.hms.patient.api.PatientRefs refs;
    @Autowired ObjectMapper json;

    private record Family(SeededTenant t, UUID doctor, UUID father, UUID son, UUID daughter) {
    }

    private static String phone() {
        return "+9189" + (20000000 + SEQ.incrementAndGet() * 13);
    }

    private UUID patient(SeededTenant t, String name, String dob) {
        UUID id = inTenantTx(t.id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,?,?,?::date,'male') RETURNING id",
                UUID.class, t.id(), name, phone(), dob));
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'data_processing')", t.id(), id));
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'messaging')", t.id(), id));
        return id;
    }

    private void proxy(SeededTenant t, UUID caregiver, UUID subject, String relation, String scope) {
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO patient_proxies (tenant_id, proxy_patient_id, subject_patient_id, relation, scope, status, accepted_at) VALUES (?,?,?,?,?,'active',now())",
                t.id(), caregiver, subject, relation, scope));
    }

    /** Father with a son on FULL and a daughter on BOOK_AND_RECORDS. */
    private Family family(String fatherDob) {
        SeededTenant t = seedTenant();
        for (String name : List.of("visit_instructions", "missed_visit_alert")) {
            inTenantTx(t.id(), () -> jdbc.update("INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status) "
                    + "VALUES (?, ?, ?, 'en', 'UTILITY', 'Clinic', 'A message about {{1}}', 1, 'approved')", t.id(), name, "m_" + UUID.randomUUID().toString().replace("-", "")));
        }
        SeededStaff doc = seedStaff(t, seedFullAccessRole(t.id()), "qa21-idc014-" + SEQ.incrementAndGet() + "@a.com", phone(), false);
        UUID father = patient(t, "Gopal Iyer", fatherDob);
        UUID son = patient(t, "Hari Iyer", "1984-01-01");
        UUID daughter = patient(t, "Lata Iyer", "1987-01-01");
        proxy(t, son, father, "son", "FULL");
        proxy(t, daughter, father, "daughter", "BOOK_AND_RECORDS");
        return new Family(t, doc.id(), father, son, daughter);
    }

    private void consume(Family f, String group, String type, Map<String, Object> data) {
        EventConsumer c = consumers.stream().filter(x -> x.group().equals(group)).findFirst().orElseThrow();
        inTenantTx(f.t().id(), () -> c.handle(new EventConsumer.Event("ev:in:x", "1-0", UUID.randomUUID().toString(), type, f.t().id(), null, 1, json.valueToTree(data))));
    }

    private UUID closedVisit(Family f) {
        UUID entry = inTenantTx(f.t().id(), () -> jdbc.queryForObject("INSERT INTO queue_entries (tenant_id, patient_id, doctor_id, department_id, queue_date, token_number) VALUES (?,?,?,(SELECT id FROM departments WHERE is_default),current_date,?) RETURNING id",
                UUID.class, f.t().id(), f.father(), f.doctor(), SEQ.incrementAndGet()));
        inTenantTx(f.t().id(), () -> jdbc.update("INSERT INTO clinical_documents (tenant_id, patient_id, queue_entry_id, doctor_id, doc_type, status, body, header, created_by, issued_by, issued_at) "
                + "VALUES (?,?,?,?,'visit_summary','issued','{}'::jsonb,'{}'::jsonb,?,?,now())", f.t().id(), f.father(), entry, f.doctor(), f.doctor(), f.doctor()));
        return entry;
    }

    private UUID noShow(Family f) {
        UUID appt = inTenantTx(f.t().id(), () -> jdbc.queryForObject("INSERT INTO appointments (tenant_id, patient_id, doctor_id, start_time, end_time) VALUES (?,?,?,now() - interval '1 hour', now() - interval '45 minutes') RETURNING id",
                UUID.class, f.t().id(), f.father(), f.doctor()));
        consume(f, "care-missed-visit", "scheduling.appointment.no_show.v1", Map.of("appointmentId", appt.toString(), "markedBy", "staff"));
        return appt;
    }

    private UUID requestTo(Family f, String purpose, UUID recipient) {
        return inTenantTx(f.t().id(), () -> jdbc.queryForObject("SELECT id FROM notification_requests WHERE purpose = ? AND recipient_patient_id = ?", UUID.class, purpose, recipient));
    }

    private List<UUID> sentTo(Family f, String purpose) {
        return inTenantTx(f.t().id(), () -> jdbc.queryForList("SELECT recipient_patient_id FROM notification_requests WHERE purpose = ? AND status = 'requested'", UUID.class, purpose));
    }

    @Test
    void d01_aCaregiverRevokedAfterTheRequestGetsNothingAtSendTime_theOtherStillGets() {
        Family f = family("1950-06-01");
        consume(f, "care-visit-instructions", "visit.completed.v1", Map.of("entryId", closedVisit(f).toString()));
        assertThat(sentTo(f, "visit_instructions")).containsExactlyInAnyOrder(f.father(), f.son(), f.daughter());
        UUID toSon = requestTo(f, "visit_instructions", f.son());
        inTenantTx(f.t().id(), () -> jdbc.update("UPDATE patient_proxies SET status = 'revoked', revoked_at = now() WHERE proxy_patient_id = ?", f.son()));
        assertThatThrownBy(() -> notifications.claim(toSon)).isInstanceOf(com.nabd.hms.common.ApiException.class);
        assertThat(inTenantTx(f.t().id(), () -> jdbc.queryForObject("SELECT status FROM notification_requests WHERE id = ?", String.class, toSon))).isEqualTo("cancelled");
        assertThat(notifications.claim(requestTo(f, "visit_instructions", f.daughter())).params()).containsExactly("Gopal");
    }

    @Test
    void d02_aCaregiverWhoWithdrewTheirOwnMessagingConsentGetsNoAlert_beforeOrAfterTheRequest() {
        Family f = family("1950-06-01");
        inTenantTx(f.t().id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, withdrawn_at) VALUES (?,?,'messaging',now())", f.t().id(), f.daughter()));
        noShow(f);
        assertThat(sentTo(f, "missed_visit_alert")).containsExactly(f.son());
        UUID toSon = requestTo(f, "missed_visit_alert", f.son());
        inTenantTx(f.t().id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, withdrawn_at) VALUES (?,?,'messaging',now())", f.t().id(), f.son()));
        assertThatThrownBy(() -> notifications.claim(toSon)).isInstanceOf(com.nabd.hms.common.ApiException.class);
    }

    @Test
    void d03_patientWithdrewMessagingConsent_noShowAlertsNobody() {
        Family f = family("1950-06-01");
        inTenantTx(f.t().id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, withdrawn_at) VALUES (?,?,'messaging',now())", f.t().id(), f.father()));
        noShow(f);
        assertThat(sentTo(f, "missed_visit_alert")).isEmpty();
    }

    @Test
    void d04_sixtiethBirthdayBoundary_60TodayAlerts_60TomorrowDoesNot() {
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        Family turns60 = family("1950-01-01");
        Family almost = family("1950-01-01");
        LocalDate today = LocalDate.now(zone);
        for (Family f : List.of(turns60, almost)) {
            jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", zone.getId(), f.t().id());
        }
        inTenantTx(turns60.t().id(), () -> jdbc.update("UPDATE patients SET dob = ? WHERE id = ?", java.sql.Date.valueOf(today.minusYears(60)), turns60.father()));
        inTenantTx(almost.t().id(), () -> jdbc.update("UPDATE patients SET dob = ? WHERE id = ?", java.sql.Date.valueOf(today.minusYears(60).plusDays(1)), almost.father()));
        noShow(turns60);
        noShow(almost);
        assertThat(sentTo(turns60, "missed_visit_alert")).as("60 today").containsExactlyInAnyOrder(turns60.son(), turns60.daughter());
        assertThat(sentTo(almost, "missed_visit_alert")).as("60 tomorrow").isEmpty();
    }

    @Test
    void d05_recipientsEndpointRefusesARefFromAnotherTenant() {
        Family a = family("1950-06-01"), b = family("1950-06-01");
        String refA = inTenantTx(a.t().id(), () -> refs.refFor(a.father()));
        HttpHeaders h = new HttpHeaders();
        h.set("X-Internal-Token", INTERNAL_COMMS_TOKEN);
        ResponseEntity<String> cross = http.exchange(url("/internal/v1/patients/" + refA + "/recipients?tenantId=" + b.t().id() + "&class=clinical"), HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(cross.getStatusCode()).as(cross.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> noTenant = http.exchange(url("/internal/v1/patients/" + refA + "/recipients?class=clinical"), HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(noTenant.getStatusCode().value()).isEqualTo(400);
        ResponseEntity<String> badTenant = http.exchange(url("/internal/v1/patients/" + refA + "/recipients?tenantId=nope&class=clinical"), HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(badTenant.getStatusCode().value()).isEqualTo(400);
    }
}

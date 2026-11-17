package com.nabd.hms.patient;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.messaging.NotificationService;
import com.nabd.hms.shared.notify.NotificationOutcome;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * COM-025: the preferences API (desk) and the Settings relay (Edge), and what the send path does with them: the chosen language
 * with the English fallback, a switched-off category, caregiver dual delivery with the send-time re-check, and the NB-133 one
 * message per phone rule.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(OutputCaptureExtension.class)
class Com025NotificationPreferencesApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(25000);

    @Autowired NotificationService notifications;
    @Autowired JwtEncoder encoder;

    private record Clinic(SeededTenant t, String owner, String viewer) {
    }

    private static String phone() {
        return "+9196" + (30000000 + SEQ.incrementAndGet() * 17);
    }

    private static String sha256(String phone) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(phone.replaceAll("\\D", "").getBytes(StandardCharsets.UTF_8)));
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        String owner = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "com025-o" + SEQ.incrementAndGet() + "@a.com", phone(), false));
        UUID viewRole = seedRole(t.id(), "Viewer", false, new ModuleGrant("patients", true, false, false, false, false, false, false));
        String viewer = loginAndGetAccessToken(seedStaff(t, viewRole, "com025-v" + SEQ.incrementAndGet() + "@a.com", phone(), false));
        return new Clinic(t, owner, viewer);
    }

    private UUID patient(SeededTenant t, String name, String dob, String phone, UUID guardian) {
        UUID id = inTenantTx(t.id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender, guardian_id) VALUES (?,?,?,?::date,'female',?) RETURNING id",
                UUID.class, t.id(), name, phone, dob, guardian));
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'data_processing'),(?,?,'messaging')", t.id(), id, t.id(), id));
        return id;
    }

    private void proxy(SeededTenant t, UUID caregiver, UUID subject, String relation) {
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO patient_proxies (tenant_id, proxy_patient_id, subject_patient_id, relation, scope, status, accepted_at) VALUES (?,?,?,?,'BOOK_ONLY','active',now())",
                t.id(), caregiver, subject, relation));
    }

    private void template(SeededTenant t, String name, String language, int params) {
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status) "
                + "VALUES (?, ?, ?, ?, 'UTILITY', 'Clinic', 'Message', ?, 'approved')", t.id(), name, "m_" + UUID.randomUUID().toString().replace("-", ""), language, params));
    }

    private ResponseEntity<Map> put(Clinic c, String token, UUID patient, Map<String, Object> body) {
        return exchange("/v1/patients/" + patient + "/notification-preferences", HttpMethod.PUT, authedJsonBody(token, body), Map.class);
    }

    private ResponseEntity<Map> get(String token, UUID patient) {
        return exchange("/v1/patients/" + patient + "/notification-preferences", HttpMethod.GET, authed(token), Map.class);
    }

    private static Map<String, Object> prefs(String language, Map<String, Object> categories, List<Map<String, Object>> caregivers) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("language", language);
        m.put("channel", "whatsapp");
        m.put("categories", categories);
        m.put("caregivers", caregivers);
        return m;
    }

    private NotificationOutcome remind(SeededTenant t, UUID patient, String key) {
        return inTenantTx(t.id(), () -> notifications.request(new NotificationRequest(NotificationPurpose.APPT_REMINDER_24H, "appointment", UUID.randomUUID(), patient,
                null, null, List.of("Asha", "12 March", "10:30"), null, null, Instant.now().plusSeconds(3600), key)));
    }

    private List<Map<String, Object>> rows(SeededTenant t, String purpose) {
        return inTenantTx(t.id(), () -> jdbc.queryForList("SELECT id, recipient_patient_id, about_patient_id, status, language FROM notification_requests WHERE purpose = ? ORDER BY created_at, about_patient_id NULLS FIRST", purpose));
    }

    private int events(SeededTenant t, String type, UUID aggregate) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = ? AND aggregate_id = ?", Integer.class, t.id(), type, aggregate));
    }

    // ---- the API ------------------------------------------------------------------------------------------------------

    @Test
    void defaults_save_audit_event_idempotentPut_viewerCannotEdit_otherClinicSeesNothing() {
        Clinic c = clinic();
        UUID asha = patient(c.t(), "Asha Rao", "1980-01-01", phone(), null);
        Map<String, Object> d = get(c.viewer(), asha).getBody();
        assertThat(d).containsEntry("channel", "whatsapp").containsEntry("version", 0).containsEntry("caregivers", List.of());
        assertThat(d.get("language")).isNull();
        assertThat((Map<String, Object>) d.get("categories")).containsEntry("careReminders", true).containsEntry("reviews", true);

        Map<String, Object> body = prefs("hi", Map.of("reviews", false), List.of());
        body.put("quietHours", Map.of("start", "21:00", "end", "08:00"));
        assertThat(put(c, c.viewer(), asha, body).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> r = put(c, c.owner(), asha, body);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("language", "hi").containsEntry("version", 1).containsEntry("quietHours", Map.of("start", "21:00", "end", "08:00"));
        assertThat((Map<String, Object>) r.getBody().get("categories")).containsEntry("reviews", false).containsEntry("recalls", true);
        assertThat(put(c, c.owner(), asha, body).getBody()).containsEntry("version", 1); // same body: nothing written
        assertThat(events(c.t(), "patient.notification_preferences.changed.v1", asha)).isEqualTo(1);
        String after = inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT after::text FROM audit_log WHERE action = 'patient.notification_preferences_changed' AND entity_id = ?", String.class, asha));
        assertThat(after).contains("\"language\": \"hi\"").doesNotContain("Asha");

        Map<String, Object> bad = prefs("fr", Map.of(), List.of());
        assertThat(put(c, c.owner(), asha, bad).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Clinic other = clinic();
        assertThat(get(other.owner(), asha).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put(other, other.owner(), asha, body).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void onlyAnAuthorisedCaregiverCanBeAdded_422CaregiverNotAuthorised() {
        Clinic c = clinic();
        UUID father = patient(c.t(), "Raghavan Nair", "1950-01-01", phone(), null);
        UUID son = patient(c.t(), "Arun Nair", "1980-01-01", phone(), null);
        UUID stranger = patient(c.t(), "Vivek Menon", "1985-01-01", phone(), null);
        proxy(c.t(), son, father, "son");
        Map<String, Object> view = get(c.owner(), father).getBody();
        assertThat((List<Map<String, Object>>) view.get("eligibleCaregivers")).singleElement()
                .satisfies(e -> assertThat(e).containsEntry("personRef", son.toString()).containsEntry("firstName", "Arun").containsEntry("relation", "son"));

        ResponseEntity<Map> no = put(c, c.owner(), father, prefs(null, Map.of(), List.of(Map.of("personRef", stranger.toString(), "categories", List.of("careReminders")))));
        assertThat(no.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(no.getBody()).containsEntry("code", "CAREGIVER_NOT_AUTHORISED");
        ResponseEntity<Map> reviews = put(c, c.owner(), father, prefs(null, Map.of(), List.of(Map.of("personRef", son.toString(), "categories", List.of("reviews")))));
        assertThat(reviews.getStatusCode()).as("a caregiver gets reminders and recalls only").isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> ok = put(c, c.owner(), father, prefs(null, Map.of(), List.of(Map.of("personRef", son.toString(), "categories", List.of("careReminders")))));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map<String, Object>>) ok.getBody().get("caregivers")).singleElement()
                .satisfies(g -> assertThat(g).containsEntry("personRef", son.toString()).containsEntry("categories", List.of("careReminders")));
    }

    // ---- the send path ------------------------------------------------------------------------------------------------

    @Test
    void ac1_theChosenLanguageIsUsed_andAnUnapprovedOneFallsBackToEnglishWithALogLine(CapturedOutput output) {
        Clinic c = clinic();
        template(c.t(), "appt_reminder_24h", "en", 3);
        template(c.t(), "appt_reminder_24h", "hi", 3);
        UUID hindi = patient(c.t(), "Asha Rao", "1980-01-01", phone(), null);
        UUID kannada = patient(c.t(), "Kavya Rao", "1981-01-01", phone(), null);
        assertThat(put(c, c.owner(), hindi, prefs("hi", Map.of(), List.of())).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put(c, c.owner(), kannada, prefs("kn", Map.of(), List.of())).getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID hi = remind(c.t(), hindi, "r-hi").requestId();
        UUID kn = remind(c.t(), kannada, "r-kn").requestId();
        assertThat(notifications.claim(hi).language()).isEqualTo("hi");
        assertThat(notifications.claim(kn).language()).as("no approved Kannada version yet").isEqualTo("en");
        assertThat(output.getAll()).contains("template language fallback: appt_reminder_24h has no approved 'kn' version");
    }

    @Test
    void ac2_reviewsSwitchedOff_noReviewRequestGoesOut_otherKindsStillDo() {
        Clinic c = clinic();
        template(c.t(), "review_request", "en", 3);
        template(c.t(), "appt_reminder_24h", "en", 3);
        UUID asha = patient(c.t(), "Asha Rao", "1980-01-01", phone(), null);
        inTenantTx(c.t().id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'marketing')", c.t().id(), asha));
        assertThat(put(c, c.owner(), asha, prefs(null, Map.of("reviews", false), List.of())).getStatusCode()).isEqualTo(HttpStatus.OK);

        NotificationOutcome review = inTenantTx(c.t().id(), () -> notifications.request(new NotificationRequest(NotificationPurpose.REVIEW_REQUEST, "visit", UUID.randomUUID(), asha,
                null, null, List.of("Asha", "12 March", "https://x.test/s/1"), null, null, null, "review:1")));
        assertThat(review.status()).isEqualTo("blocked");
        assertThat(events(c.t(), "comms.notification.requested.v1", review.requestId())).isZero();
        assertThatThrownBy(() -> notifications.claim(review.requestId())).isInstanceOf(ApiException.class); // nothing for Comms to send

        assertThat(remind(c.t(), asha, "r-1").status()).isEqualTo("requested"); // care reminders stay on
    }

    @Test
    void ac3_bothGetTheReminderAsSeparateRequests_aRevokedCaregiverIsStoppedAtSendTime() {
        Clinic c = clinic();
        template(c.t(), "appt_reminder_24h", "en", 3);
        UUID father = patient(c.t(), "Raghavan Nair", "1950-01-01", phone(), null);
        UUID son = patient(c.t(), "Arun Nair", "1980-01-01", phone(), null);
        proxy(c.t(), son, father, "son");
        assertThat(put(c, c.owner(), father, prefs(null, Map.of(), List.of(Map.of("personRef", son.toString(), "categories", List.of("careReminders"))))).getStatusCode()).isEqualTo(HttpStatus.OK);

        remind(c.t(), father, "appt-t24h:1");
        remind(c.t(), father, "appt-t24h:1"); // the sweep asking again changes nothing
        List<Map<String, Object>> rows = rows(c.t(), "appt_reminder_24h");
        assertThat(rows).extracting(r -> r.get("recipient_patient_id")).containsExactly(father, son);
        assertThat(rows).extracting(r -> r.get("status")).containsOnly("requested");
        assertThat(rows.get(1)).containsEntry("about_patient_id", father);
        UUID own = (UUID) rows.get(0).get("id"), copy = (UUID) rows.get(1).get("id");
        assertThat(own).isNotEqualTo(copy);
        assertThat(events(c.t(), "comms.notification.requested.v1", own)).isEqualTo(1);
        assertThat(events(c.t(), "comms.notification.requested.v1", copy)).as("own delivery status").isEqualTo(1);
        assertThat(notifications.claim(copy).recipient().patientId()).isEqualTo(son);

        inTenantTx(c.t().id(), () -> jdbc.update("UPDATE patient_proxies SET status = 'revoked', revoked_at = now() WHERE proxy_patient_id = ?", son));
        assertThatThrownBy(() -> notifications.claim(copy)).isInstanceOf(ApiException.class); // read live: stopped at once, well inside 1 min
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT status FROM notification_requests WHERE id = ?", String.class, copy))).isEqualTo("cancelled");
        assertThat(notifications.claim(own).recipient().patientId()).isEqualTo(father); // the patient's own copy still goes
        assertThat((List<?>) get(c.owner(), father).getBody().get("caregivers")).as("a revoked caregiver drops off the list").isEmpty();

        remind(c.t(), father, "appt-t24h:2");
        assertThat(rows(c.t(), "appt_reminder_24h")).extracting(r -> r.get("recipient_patient_id")).containsExactly(father, son, father);
    }

    @Test
    void ac4_nb133_aChildWithTwoGuardiansOnDifferentPhones_oneMessagePerGuardianNeverTwo() {
        Clinic c = clinic();
        template(c.t(), "appt_reminder_24h", "en", 3);
        String motherPhone = phone(), fatherPhone = phone();
        UUID mother = patient(c.t(), "Priya Sharma", "1988-01-01", motherPhone, null);
        UUID father = patient(c.t(), "Rohit Sharma", "1986-01-01", fatherPhone, null);
        UUID grandma = patient(c.t(), "Kamla Sharma", "1950-01-01", fatherPhone, null); // shares the father's phone
        UUID child = patient(c.t(), "Aarav Sharma", "2022-01-01", motherPhone, mother); // a child's record carries the guardian's number
        proxy(c.t(), father, child, "father");
        proxy(c.t(), grandma, child, "grandmother");
        assertThat((List<Map<String, Object>>) get(c.owner(), child).getBody().get("eligibleCaregivers"))
                .as("the mother already gets it on the child's number").extracting(e -> e.get("personRef")).containsExactly(father.toString(), grandma.toString());
        assertThat(put(c, c.owner(), child, prefs(null, Map.of(), List.of(
                Map.of("personRef", father.toString(), "categories", List.of("careReminders")),
                Map.of("personRef", grandma.toString(), "categories", List.of("careReminders"))))).getStatusCode()).isEqualTo(HttpStatus.OK);

        remind(c.t(), child, "vaccine-visit:1");
        remind(c.t(), child, "vaccine-visit:1");
        List<UUID> to = rows(c.t(), "appt_reminder_24h").stream().map(r -> (UUID) r.get("recipient_patient_id")).toList();
        assertThat(to).containsExactly(child, father); // the mother's phone once, the father's phone once (grandma shares it)
        List<String> phones = inTenantTx(c.t().id(), () -> jdbc.queryForList("SELECT p.phone_digits FROM notification_requests n JOIN patients p ON p.id = n.recipient_patient_id WHERE n.purpose = 'appt_reminder_24h'", String.class));
        assertThat(phones).doesNotHaveDuplicates().hasSize(2);
    }

    // ---- the WhatsApp Settings relay (Patient Access Edge) ------------------------------------------------------------

    @Test
    void settingsRelay_setsTheLanguageOfTheHousehold_onceAnEventPerChange_tenantBound() throws Exception {
        Clinic c = clinic();
        String ph = phone();
        UUID mother = patient(c.t(), "Priya Sharma", "1988-01-01", ph, null);
        UUID child = patient(c.t(), "Aarav Sharma", "2020-01-01", ph, mother);
        String edge = InternalTestTokens.mint(encoder, "patient-access-edge", c.t().id());
        String path = "/internal/v1/tenants/" + c.t().id() + "/households/" + sha256(ph) + "/notification-preferences";

        ResponseEntity<Map> r = exchange(path, HttpMethod.PATCH, authedJsonBody(edge, Map.of("language", "hi")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("language", "hi").containsEntry("changed", 2);
        assertThat(get(c.owner(), mother).getBody()).containsEntry("language", "hi").containsEntry("version", 1);
        assertThat(get(c.owner(), child).getBody()).containsEntry("language", "hi");
        assertThat(exchange(path, HttpMethod.PATCH, authedJsonBody(edge, Map.of("language", "hi")), Map.class).getBody()).containsEntry("changed", 0);
        assertThat(events(c.t(), "patient.notification_preferences.changed.v1", mother)).isEqualTo(1);

        assertThat(exchange(path, HttpMethod.PATCH, authedJsonBody(edge, Map.of("language", "xx")), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/internal/v1/tenants/" + c.t().id() + "/households/" + sha256(phone()) + "/notification-preferences", HttpMethod.PATCH,
                authedJsonBody(edge, Map.of("language", "hi")), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Clinic other = clinic();
        String otherEdge = InternalTestTokens.mint(encoder, "patient-access-edge", other.t().id());
        assertThat(exchange(path, HttpMethod.PATCH, authedJsonBody(otherEdge, Map.of("language", "en")), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(path, HttpMethod.PATCH, authedJsonBody(c.owner(), Map.of("language", "en")), Map.class).getStatusCode().is4xxClientError()).isTrue();
        assertThat(get(c.owner(), mother).getBody()).containsEntry("language", "hi");
    }
}

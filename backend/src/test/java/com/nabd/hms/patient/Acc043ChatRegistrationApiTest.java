package com.nabd.hms.patient;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ACC-043: registration from the first WhatsApp message (consent first, then the patient), and the desk's "Complete in chat". */
class Acc043ChatRegistrationApiTest extends ApiTestBase {

    @Autowired JwtEncoder encoder;
    @Autowired ClinicClock clock;

    private record Clinic(SeededTenant t, String edge, String staff) {
        String base() {
            return "/internal/v1/tenants/" + t.id();
        }
    }

    private static String hash(String phone) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(phone.replaceAll("\\D", "").getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO privacy_notices (tenant_id, version, language, template_key, body_md, mandatory_hash, effective_from) "
                + "VALUES (?, 'v1', 'en', 'IN-DPDP-1', 'body', 'h1', now() - interval '1 day')", t.id()));
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "acc043-" + UUID.randomUUID() + "@a.com", "+9195" + (10000000 + (int) (Math.random() * 89999999)), false);
        return new Clinic(t, InternalTestTokens.mint(encoder, "patient-access-edge", t.id()), loginAndGetAccessToken(owner));
    }

    private static String newPhone() {
        return "+9198" + (10000000 + (int) (Math.random() * 89999999));
    }

    /** The Edge relays the consent taps; returns the first consent record id. */
    @SuppressWarnings("unchecked")
    private String consent(Clinic c, String phone, boolean care, boolean messaging) {
        ResponseEntity<Map> r = exchange(c.base() + "/patients/consents", HttpMethod.POST, authedJsonBody(c.edge(), Map.of("phoneHash", hash(phone), "noticeVersion", "v1",
                "language", "en", "evidence", Map.of("wamid", "wamid." + UUID.randomUUID()),
                "decisions", List.of(Map.of("purpose", "data_processing", "granted", care), Map.of("purpose", "messaging", "granted", messaging)))), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((List<String>) r.getBody().get("consentIds")).get(0);
    }

    private Map<String, Object> body(String phone, String name, String dob, String consentId) {
        Map<String, Object> m = new HashMap<>();
        m.put("phoneVerified", true);
        m.put("phone", phone);
        m.put("phoneHash", hash(phone));
        m.put("firstName", name.split(" ")[0]);
        m.put("lastName", name.contains(" ") ? name.substring(name.indexOf(' ') + 1) : null);
        m.put("dob", dob);
        m.put("sex", "female");
        m.put("consentRecordId", consentId);
        return m;
    }

    private ResponseEntity<Map> create(Clinic c, Map<String, Object> b) {
        return exchange(c.base() + "/patients", HttpMethod.POST, authedJsonBody(c.edge(), b), Map.class);
    }

    private int count(Clinic c, String sql, Object... args) {
        return inTenantTx(c.t().id(), () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    @Test
    void ac1_ac2_consentWithNoticeVersionComesFirst_thenThePatientExistsWithSourceWhatsapp() {
        Clinic c = clinic();
        String phone = newPhone();
        String consentId = consent(c, phone, true, true);
        assertThat(count(c, "SELECT count(*) FROM patients WHERE phone = ?", phone)).as("consent only: no patient yet").isZero();

        ResponseEntity<Map> r = create(c, body(phone, "Meera Nair", "1992-04-05", consentId));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((String) r.getBody().get("patientRef")).isNotBlank();
        Map<String, Object> p = inTenantTx(c.t().id(), () -> jdbc.queryForMap("SELECT id, name, registration_source, created_at, profile_completed_at FROM patients WHERE phone = ?", phone));
        assertThat(p).containsEntry("name", "Meera Nair").containsEntry("registration_source", "whatsapp");
        assertThat(p.get("profile_completed_at")).isNull();
        Map<String, Object> consent = inTenantTx(c.t().id(), () -> jdbc.queryForMap("SELECT notice_version, channel, granted_at FROM consents WHERE patient_id = ? AND consent_type = 'data_processing'", p.get("id")));
        assertThat(consent).containsEntry("notice_version", "v1").containsEntry("channel", "WHATSAPP");
        assertThat(count(c, "SELECT count(*) FROM chat_consents cc JOIN patients p ON p.id = cc.attached_patient_id WHERE cc.purpose = 'data_processing' AND cc.decided_at <= p.created_at"))
                .as("the decision was stored before the patient row").isEqualTo(1);
        assertThat(count(c, "SELECT count(*) FROM audit_log WHERE action = 'patient.registered_via_chat'")).isEqualTo(1);
    }

    @Test
    void consentIsRequired_aDeclinedOrForeignOrMissingRecordCreatesNothing() {
        Clinic c = clinic();
        String phone = newPhone();
        String declined = consent(c, phone, false, false);
        for (Map<String, Object> b : List.of(body(phone, "No Consent", "1990-01-01", declined),
                body(phone, "No Consent", "1990-01-01", UUID.randomUUID().toString()),
                body(phone, "No Consent", "1990-01-01", null),
                body(newPhone(), "Other Phone", "1990-01-01", consent(c, phone, true, true)))) { // a record that belongs to another phone
            ResponseEntity<Map> r = create(c, b);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(r.getBody()).containsEntry("code", "CONSENT_REQUIRED");
        }
        assertThat(count(c, "SELECT count(*) FROM patients WHERE name IN ('No Consent', 'Other Phone')")).isZero();
        assertThat(count(c, "SELECT count(*) FROM chat_consents WHERE phone_hash = ? AND purpose = 'data_processing' AND NOT granted", hash(phone)))
                .as("the declined-consent record is stored").isEqualTo(1);
    }

    @Test
    void validation_phoneVerifiedHashAndDate() {
        Clinic c = clinic();
        String phone = newPhone();
        String consentId = consent(c, phone, true, true);
        Map<String, Object> notVerified = body(phone, "A B", "1990-01-01", consentId);
        notVerified.put("phoneVerified", false);
        assertThat(create(c, notVerified).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> wrongHash = body(phone, "A B", "1990-01-01", consentId);
        wrongHash.put("phoneHash", hash(newPhone()));
        assertThat(create(c, wrongHash).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(c, body(phone, "A B", LocalDate.now().plusDays(3).toString(), consentId)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> neither = body(phone, "A B", null, consentId);
        assertThat(create(c, neither).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count(c, "SELECT count(*) FROM patients WHERE phone = ?", phone)).isZero();
    }

    @Test
    void ageInsteadOfDobStoresADateOfBirthOfThatAge() {
        Clinic c = clinic();
        String phone = newPhone();
        Map<String, Object> b = body(phone, "Ravi Kumar", null, consent(c, phone, true, false));
        b.put("age", 34);
        assertThat(create(c, b).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        LocalDate today = clock.today(c.t().id());
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT dob FROM patients WHERE phone = ?", java.sql.Date.class, phone)).toLocalDate())
                .isEqualTo(today.minusYears(34));
    }

    @Test
    void ac3_aMinorNeedsAGuardian_noRecordWithoutOne_withAConsentedAdultOnTheSamePhoneTheChildIsLinked() {
        Clinic c = clinic();
        String phone = newPhone();
        String child = LocalDate.now().minusYears(7).toString();
        ResponseEntity<Map> refused = create(c, body(phone, "Tiny Tot", child, consent(c, phone, true, true)));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody()).containsEntry("code", "GUARDIAN_REQUIRED");
        assertThat(count(c, "SELECT count(*) FROM patients WHERE phone = ?", phone)).isZero();

        String guardianRef = (String) create(c, body(phone, "Grown Up", "1988-02-02", consent(c, phone, true, true))).getBody().get("patientRef");
        Map<String, Object> withGuardian = body(phone, "Tiny Tot", child, null);
        withGuardian.put("guardianRef", guardianRef);
        ResponseEntity<Map> ok = create(c, withGuardian);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count(c, "SELECT count(*) FROM patients c JOIN patients g ON g.id = c.guardian_id WHERE c.name = 'Tiny Tot' AND g.name = 'Grown Up'")).isEqualTo(1);
        assertThat(count(c, "SELECT count(*) FROM consents x JOIN patients p ON p.id = x.patient_id WHERE p.name = 'Tiny Tot' AND x.consent_type IN ('data_processing','guardian_access') AND x.guardian_patient_id IS NOT NULL"))
                .isEqualTo(2);
        // a guardian that has not consented, or sits on another phone, does not count
        String otherPhone = newPhone();
        Map<String, Object> wrongPhone = body(otherPhone, "Wrong Phone", child, null);
        wrongPhone.put("guardianRef", guardianRef);
        assertThat(create(c, wrongPhone).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void ac6_aLikelyDuplicateCreatesNoSecondPatient_butAnotherPersonOnTheSameFamilyPhoneIsFine() {
        Clinic c = clinic();
        String phone = newPhone();
        assertThat(create(c, body(phone, "Latha Menon", "1980-06-07", consent(c, phone, true, true))).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> dup = create(c, body(phone, "Latha Menon", "1980-06-07", consent(c, phone, true, true)));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dup.getBody()).containsEntry("code", "POSSIBLE_DUPLICATE");
        assertThat((List<?>) dup.getBody().get("candidates")).hasSize(1);
        assertThat(dup.getBody().toString()).doesNotContain("Latha");
        assertThat(count(c, "SELECT count(*) FROM patients WHERE phone = ?", phone)).isEqualTo(1);
        assertThat(count(c, "SELECT count(*) FROM audit_log WHERE action = 'patient.chat_registration_duplicate'")).isEqualTo(1);

        ResponseEntity<Map> family = create(c, body(phone, "Gopal Menon", "1978-01-09", consent(c, phone, true, true)));
        assertThat(family.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count(c, "SELECT count(*) FROM patients WHERE phone = ?", phone)).isEqualTo(2);
    }

    @Test
    void ac4_completeInChat_theDeskAsksThePatientFinishesAndOnlySentFieldsChange() {
        Clinic c = clinic();
        String phone = newPhone();
        ResponseEntity<Map> reg = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.staff(), Map.of("name", "Desk Walkin", "phone", phone, "dob", "1995-03-03", "gender", "male")), Map.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) reg.getBody().get("id");
        assertThat(count(c, "SELECT count(*) FROM patients WHERE id = ?::uuid AND registration_source = 'desk'", id)).isEqualTo(1);
        inTenantTx(c.t().id(), () -> jdbc.update("INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status) "
                + "VALUES (?, 'profile_completion', ?, 'en', 'UTILITY', 'Clinic', 'Please finish your registration.', 0, 'approved')", c.t().id(), "m_" + UUID.randomUUID().toString().replace("-", "")));

        // no WhatsApp consent yet: refused, nothing stored
        ResponseEntity<Map> noConsent = exchange("/v1/patients/" + id + "/complete-in-chat", HttpMethod.POST, authed(c.staff()), Map.class);
        assertThat(noConsent.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(count(c, "SELECT count(*) FROM patients WHERE id = ?::uuid AND chat_completion_requested_at IS NOT NULL", id)).isZero();
        assertThat(exchange(c.base() + "/patients/pending-completion/" + hash(phone), HttpMethod.GET, authed(c.edge()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        inTenantTx(c.t().id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?, ?::uuid, 'messaging')", c.t().id(), id));
        ResponseEntity<Map> asked = exchange("/v1/patients/" + id + "/complete-in-chat", HttpMethod.POST, authed(c.staff()), Map.class);
        assertThat(asked.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(count(c, "SELECT count(*) FROM notification_requests WHERE purpose = 'profile_completion' AND status = 'requested' AND recipient_patient_id = ?::uuid", id)).isEqualTo(1);
        assertThat(exchange("/v1/patients/" + id + "/complete-in-chat", HttpMethod.POST, authed(c.staff()), Map.class).getStatusCode()).as("same day, same request").isEqualTo(HttpStatus.ACCEPTED);
        assertThat(count(c, "SELECT count(*) FROM notification_requests WHERE purpose = 'profile_completion'")).isEqualTo(1);

        ResponseEntity<Map> pending = exchange(c.base() + "/patients/pending-completion/" + hash(phone), HttpMethod.GET, authed(c.edge()), Map.class);
        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pending.getBody()).containsEntry("firstName", "Desk");
        String ref = (String) pending.getBody().get("patientRef");
        assertThat(ref).isNotEqualTo(id);

        // the patient finishes in chat
        assertThat(exchange(c.base() + "/patients/" + ref, HttpMethod.PATCH, authedJsonBody(c.edge(), Map.of("address", "12 Lake Road", "preferredLanguage", "hi")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Map<String, Object> row = inTenantTx(c.t().id(), () -> jdbc.queryForMap("SELECT name, address, email, preferred_language, profile_completed_at, chat_completion_requested_at FROM patients WHERE id = ?::uuid", id));
        assertThat(row).containsEntry("name", "Desk Walkin").containsEntry("address", "12 Lake Road").containsEntry("preferred_language", "hi");
        assertThat(row.get("email")).as("not sent, not touched").isNull();
        assertThat(row.get("profile_completed_at")).isNotNull();
        assertThat(row.get("chat_completion_requested_at")).isNull();
        assertThat(exchange(c.base() + "/patients/pending-completion/" + hash(phone), HttpMethod.GET, authed(c.edge()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/patients/" + id + "/complete-in-chat", HttpMethod.POST, authed(c.staff()), Map.class).getStatusCode()).as("already complete").isEqualTo(HttpStatus.CONFLICT);

        // an empty or malformed completion is a 400
        assertThat(exchange(c.base() + "/patients/" + ref, HttpMethod.PATCH, authedJsonBody(c.edge(), Map.of()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(c.base() + "/patients/" + ref, HttpMethod.PATCH, authedJsonBody(c.edge(), Map.of("email", "not-an-email")), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void permissionsAndTenantIsolation() {
        Clinic a = clinic(), b = clinic();
        String phone = newPhone();
        String consentId = consent(a, phone, true, true);
        // the other clinic's Edge identity may not act in clinic A
        assertThat(exchange(a.base() + "/patients", HttpMethod.POST, authedJsonBody(b.edge(), body(phone, "Cross Tenant", "1990-01-01", consentId)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(a.base() + "/patients/pending-completion/" + hash(phone), HttpMethod.GET, authed(b.edge()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // a staff token is not a service token
        assertThat(exchange(a.base() + "/patients", HttpMethod.POST, authedJsonBody(a.staff(), body(phone, "Staff Token", "1990-01-01", consentId)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // a consent record of clinic A is useless in clinic B
        assertThat(create(b, body(phone, "Cross Tenant", "1990-01-01", consentId)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(create(a, body(phone, "Real Patient", "1990-01-01", consentId)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count(b, "SELECT count(*) FROM patients WHERE phone = ?", phone)).as("RLS: clinic B sees nothing").isZero();
        // the desk button needs patients:edit
        UUID readOnly = seedRole(a.t().id(), "Reader", false, fullGrant("queue"));
        SeededStaff nurse = seedStaff(a.t(), readOnly, "acc043-ro-" + UUID.randomUUID() + "@a.com", "+9194" + (10000000 + (int) (Math.random() * 89999999)), false);
        assertThat(exchange("/v1/patients/" + UUID.randomUUID() + "/complete-in-chat", HttpMethod.POST, authed(loginAndGetAccessToken(nurse)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}

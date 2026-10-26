package com.nabd.hms.patient.consent;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** IDC-004 (desk capture), IDC-006 (default-deny, withdrawal), IDC-007 (assisted / witness rules), IDC-008 (child profiles, audience). */
class Idc004ConsentCaptureApiTest extends ApiTestBase {

    @Autowired ClinicClock clinicClock;
    @Autowired com.nabd.hms.patient.api.MarketingAudience audience;

    private record Clinic(SeededTenant tenant, SeededStaff owner, String token) {
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    /** A default-deny clinic with its v1 notices seeded (saving the grievance contact seeds them, IDC-003). */
    private Clinic clinic() {
        SeededTenant t = seedTenant();
        su().update("UPDATE tenants SET consent_default_deny = true WHERE id = ?", t.id());
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "own" + t.slug() + "@a.com", "+9195" + (10000000 + (int) (Math.random() * 89999999)), false);
        String token = loginAndGetAccessToken(owner);
        assertThat(exchange("/v1/setup/consent-contact", HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "name", "Dr Grievance", "email", "grievance@clinic.example", "phone", "+911234567890")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Clinic(t, owner, token);
    }

    private String phone() {
        return "+9198" + (10000000 + (int) (Math.random() * 89999999));
    }

    private UUID patient(Clinic c, String dob, String guardianId) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Consent " + UUID.randomUUID().toString().substring(0, 8), "phone", phone(), "dob", dob, "gender", "female", "confirmedNotDuplicate", true));
        if (guardianId != null) {
            body.put("guardianId", guardianId);
            body.put("guardianConsent", Map.of("confirmed", true, "channel", "in_person"));
        }
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), body), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private UUID adult(Clinic c) {
        return patient(c, "1985-05-05", null);
    }

    private ResponseEntity<Map> capture(Clinic c, UUID patient, Map<String, Object> body) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(c.token());
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return exchange("/v1/patients/" + patient + "/consents", HttpMethod.POST, new HttpEntity<>(json, h), Map.class);
    }

    private Map<String, Object> req(String channel, Object... decisions) {
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (int i = 0; i < decisions.length; i += 2) {
            list.add(Map.of("purpose", decisions[i], "granted", decisions[i + 1]));
        }
        Map<String, Object> m = new HashMap<>();
        m.put("decisions", list);
        m.put("noticeVersion", "1");
        m.put("language", "en");
        m.put("channel", channel);
        return m;
    }

    private HttpStatus read(Clinic c, UUID patient) {
        return HttpStatus.valueOf(exchange("/v1/patients/" + patient, HttpMethod.GET, authed(c.token()), Map.class).getStatusCode().value());
    }

    private String type(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("type"));
    }

    private int rows(Clinic c, UUID patient, String where) {
        return su().queryForObject("SELECT count(*) FROM consents WHERE tenant_id = ? AND patient_id = ? AND " + where, Integer.class, c.tenant().id(), patient);
    }

    @SuppressWarnings("unchecked")
    @Test
    void ac1_noConsentMeansNotProcessableThenTwoRowsAtTheCurrentNoticeVersionOpenTheRecord() {
        Clinic c = clinic();
        UUID p = adult(c);
        assertThat(read(c, p)).as("IDC-006: no processing consent row = not processable").isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> state = exchange("/v1/patients/" + p + "/consents", HttpMethod.GET, authed(c.token()), Map.class);
        assertThat(state.getBody()).containsEntry("needsConsent", true).containsEntry("processable", false);
        assertThat((Map<String, Object>) state.getBody().get("notices")).as("the capture screen reads the current notice versions here").containsEntry("en", "1").containsEntry("hi", "1");

        ResponseEntity<Map> r = capture(c, p, req("DESK", "processing", true, "messaging", true, "marketing", false));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((List<?>) r.getBody().get("consents")).hasSize(2);
        assertThat(rows(c, p, "channel = 'DESK' AND notice_version = '1' AND language = 'en' AND withdrawn_at IS NULL")).isEqualTo(2);
        assertThat(rows(c, p, "consent_type = 'marketing'")).as("an unticked optional purpose stores nothing").isZero();
        assertThat(read(c, p)).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/patients/" + p + "/consents", HttpMethod.GET, authed(c.token()), Map.class).getBody())
                .containsEntry("processable", true).containsEntry("needsReack", false);
        assertThat(su().queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'consent.changed.v1' AND payload->>'noticeVersion' = '1'", Integer.class, c.tenant().id()))
                .as("one existing consent event per recorded decision").isEqualTo(2);
        Map<String, Object> audit = su().queryForMap("SELECT after::text AS a FROM audit_log WHERE tenant_id = ? AND action = 'patient.consent_captured' AND entity_id = ?", c.tenant().id(), p);
        assertThat((String) audit.get("a")).contains("noticeVersion").contains("processing").doesNotContain("Consent ");
    }

    @Test
    void ac1b_staleNoticeUnknownLanguageDuplicateAndOptionalWithoutProcessingAreRefused() {
        Clinic c = clinic();
        UUID p = adult(c);
        Map<String, Object> stale = req("DESK", "processing", true);
        stale.put("noticeVersion", "9");
        assertThat(type(capture(c, p, stale))).endsWith("notice-version-stale");
        Map<String, Object> noLang = req("DESK", "processing", true);
        noLang.put("language", "fr");
        assertThat(capture(c, p, noLang).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> dup = req("DESK", "processing", true, "processing", false);
        assertThat(capture(c, p, dup).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> optionalOnly = req("DESK", "messaging", true);
        assertThat(type(capture(c, p, optionalOnly))).as("optional purposes need an active processing consent").endsWith("processing-required");
        assertThat(rows(c, p, "true")).isZero();
    }

    @Test
    void ac2_declinedProcessingAllowsOnlyTheFlaggedEmergencyPathAndNoMessageGateOpens() {
        Clinic c = clinic();
        UUID p = adult(c);
        assertThat(type(capture(c, p, req("DESK", "processing", false, "messaging", true)))).as("no messaging consent beside a refusal").endsWith("processing-required");
        assertThat(read(c, p)).as("a refusal is not a licence").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(capture(c, p, req("DESK", "processing", false)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(rows(c, p, "consent_type = 'treatment'")).isZero();

        Map<String, Object> emergency = req("DESK", "processing", false);
        emergency.put("emergencyTreatmentBasis", true);
        assertThat(capture(c, p, emergency).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(read(c, p)).as("treatment-only path").isEqualTo(HttpStatus.OK);
        Map<String, Object> state = exchange("/v1/patients/" + p + "/consents", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat(state).containsEntry("emergencyTreatmentBasis", true).containsEntry("needsConsent", false);
        assertThat(rows(c, p, "consent_type = 'treatment' AND evidence->>'basis' = 'emergency' AND evidence->>'flag' = 'no-processing-consent'")).isEqualTo(1);
        assertThat(inTenantTx(c.tenant().id(), () -> registryGranted(p, "messaging"))).as("the emergency path grants no messaging").isFalse();
        assertThat(inTenantTx(c.tenant().id(), () -> registryGranted(p, "marketing"))).isFalse();

        assertThat(exchange("/v1/patients/" + p + "/consent/withdraw", HttpMethod.POST, authedJsonBody(c.token(), Map.of("consentType", "data_processing")), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(read(c, p)).as("withdrawing processing also ends the emergency basis").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Autowired com.nabd.hms.patient.api.ConsentRegistry registry;

    private boolean registryGranted(UUID p, String purpose) {
        return registry.isGranted(p, com.nabd.hms.patient.api.ConsentRegistry.Purpose.valueOf(purpose.toUpperCase()));
    }

    @Test
    void ac3_aMinorNeedsTheLinkedGuardianAndIsNeverGivenMarketing() {
        Clinic c = clinic();
        UUID guardian = adult(c);
        UUID child = patient(c, LocalDate.now().minusYears(8).toString(), guardian.toString());
        ResponseEntity<Map> noGuardian = capture(c, child, req("DESK", "processing", true));
        assertThat(noGuardian.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(type(noGuardian)).endsWith("guardian-required");
        Map<String, Object> wrong = req("DESK", "processing", true);
        wrong.put("guardianPatientId", UUID.randomUUID());
        assertThat(type(capture(c, child, wrong))).as("only the linked guardian").endsWith("guardian-required");

        Map<String, Object> mk = req("DESK", "processing", true, "marketing", true);
        mk.put("guardianPatientId", guardian);
        ResponseEntity<Map> marketing = capture(c, child, mk);
        assertThat(marketing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(type(marketing)).endsWith("minor-marketing-blocked");

        Map<String, Object> ok = req("DESK", "processing", true, "messaging", true);
        ok.put("guardianPatientId", guardian);
        assertThat(capture(c, child, ok).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(rows(c, child, "guardian_patient_id = '" + guardian + "' AND notice_version = '1' AND evidence->>'consentBy' = 'guardian'")).isEqualTo(2);
        assertThat(su().queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'consent.changed.v1' AND payload->>'guardianRef' = ?", Integer.class,
                c.tenant().id(), guardian.toString())).isEqualTo(2);
    }

    @Test
    void ac4_aGrantOnAnOlderNoticeShowsTheReAckPromptAndTheOldVersionIsRefused() {
        Clinic c = clinic();
        UUID p = adult(c);
        assertThat(capture(c, p, req("DESK", "processing", true)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        su().update("INSERT INTO privacy_notices (tenant_id, version, language, template_key, body_md, mandatory_hash, effective_from) "
                + "SELECT tenant_id, '2', language, template_key, body_md, mandatory_hash, now() FROM privacy_notices WHERE tenant_id = ? AND language = 'en' AND version = '1'", c.tenant().id());
        assertThat(exchange("/v1/patients/" + p + "/consents", HttpMethod.GET, authed(c.token()), Map.class).getBody()).containsEntry("needsReack", true);
        assertThat(type(capture(c, p, req("DESK", "processing", true)))).endsWith("notice-version-stale");
        Map<String, Object> v2 = req("DESK", "processing", true);
        v2.put("noticeVersion", "2");
        assertThat(capture(c, p, v2).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(exchange("/v1/patients/" + p + "/consents", HttpMethod.GET, authed(c.token()), Map.class).getBody()).containsEntry("needsReack", false);
    }

    @Test
    void idc007_assistedCaptureRecordsChannelWitnessAndLanguageAndEnforcesTheSecondWitness() {
        Clinic c = clinic();
        UUID p = adult(c);
        Map<String, Object> hi = req("ASSISTED", "processing", true, "messaging", true);
        hi.put("language", "hi");
        assertThat(capture(c, p, hi).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(rows(c, p, "channel = 'ASSISTED' AND language = 'hi' AND witness_staff_id = '" + c.owner().id() + "'")).as("the receptionist is the witness").isEqualTo(2);

        su().update("UPDATE tenants SET consent_two_witnesses = true WHERE id = ?", c.tenant().id());
        UUID p2 = adult(c);
        Map<String, Object> self = req("ASSISTED", "processing", true);
        self.put("witnessStaffIds", List.of(c.owner().id()));
        ResponseEntity<Map> refused = capture(c, p2, self);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(type(refused)).endsWith("second-witness-required");
        assertThat(type(capture(c, p2, req("ASSISTED", "processing", true)))).as("no witness at all").endsWith("second-witness-required");
        assertThat(rows(c, p2, "true")).isZero();

        SeededStaff second = seedStaff(c.tenant(), c.owner().roleId(), "w" + c.tenant().slug() + "@a.com", phone(), false);
        Map<String, Object> two = req("ASSISTED", "processing", true);
        two.put("witnessStaffIds", List.of(second.id()));
        assertThat(capture(c, p2, two).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(rows(c, p2, "witness_staff_id = '" + second.id() + "' AND evidence->'witnessStaffIds' IS NOT NULL")).isEqualTo(1);
        Map<String, Object> ghost = req("ASSISTED", "processing", true);
        ghost.put("witnessStaffIds", List.of(UUID.randomUUID()));
        assertThat(type(capture(c, adult(c), ghost))).endsWith("witness-unknown");
    }

    @Test
    void idc006_defaultDenyMatrixWithdrawalAndLegacyClinics() {
        Clinic c = clinic();
        UUID p = adult(c);
        assertThat(read(c, p)).isEqualTo(HttpStatus.NOT_FOUND);
        capture(c, p, req("DESK", "processing", true, "marketing", true));
        assertThat(read(c, p)).isEqualTo(HttpStatus.OK);
        for (String type : List.of("marketing", "data_processing")) {
            assertThat(exchange("/v1/patients/" + p + "/consent/withdraw", HttpMethod.POST, authedJsonBody(c.token(), Map.of("consentType", type, "channel", "DESK")), Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
        }
        assertThat(read(c, p)).as("a withdrawal is effective on the next read").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'patient.consent_withdrawn' AND entity_id = ?", Integer.class, c.tenant().id(), p)).isEqualTo(2);
        assertThat(su().queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'consent.changed.v1' AND payload->>'status' = 'withdrawn' AND payload->>'consentType' = 'marketing'",
                Integer.class, c.tenant().id())).isEqualTo(1);
        Map<String, Object> again = req("DESK", "processing", true);
        assertThat(capture(c, p, again).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(read(c, p)).as("the newest row decides: a fresh grant restores access").isEqualTo(HttpStatus.OK);

        // legacy clinic (pre-IDC-006 data): no row stays readable, a withdrawal still blocks
        su().update("UPDATE tenants SET consent_default_deny = false WHERE id = ?", c.tenant().id());
        UUID legacy = adult(c);
        assertThat(read(c, legacy)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void tenantIsolation_anotherClinicCannotCaptureOrReadThisPatientsConsent() {
        Clinic a = clinic();
        Clinic b = clinic();
        UUID p = adult(a);
        assertThat(capture(b, p, req("DESK", "processing", true)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/patients/" + p + "/consents", HttpMethod.GET, authed(b.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rows(a, p, "true")).isZero();
    }

    @Test
    void idc008_marketingAudienceExcludesEveryoneUnder18AtTheClinicDayBoundary() {
        Clinic c = clinic();
        LocalDate today = clinicClock.today(c.tenant().id());
        UUID adult = seeded(c, today.minusYears(40), true);
        UUID turns18Today = seeded(c, today.minusYears(18), true);
        UUID turns18Tomorrow = seeded(c, today.minusYears(18).plusDays(1), true);
        UUID seventeen = seeded(c, today.minusYears(17).minusDays(364), true);
        UUID noMarketing = seeded(c, today.minusYears(30), false);
        List<UUID> ids = inTenantTx(c.tenant().id(), () -> audience.adults(null, 100));
        assertThat(ids).contains(adult, turns18Today).doesNotContain(turns18Tomorrow, seventeen, noMarketing);
        assertThat(inTenantTx(c.tenant().id(), () -> audience.adults(adult, 100))).as("keyset paging").doesNotContain(adult);
    }

    private UUID seeded(Clinic c, LocalDate dob, boolean marketing) {
        UUID id = UUID.randomUUID();
        inTenantTx(c.tenant().id(), () -> {
            jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,'Aud','" + phone() + "',?,'female')", id, c.tenant().id(), dob);
            jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'data_processing')", c.tenant().id(), id);
            if (marketing) {
                jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'marketing')", c.tenant().id(), id);
            }
        });
        return id;
    }
}

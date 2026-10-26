package com.nabd.hms.patient;

import com.nabd.hms.patient.api.ChatConsentAttacher;
import com.nabd.hms.patient.api.ConsentRegistry;
import com.nabd.hms.patient.api.ConsentRegistry.Purpose;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** IDC-005: the Edge relays WhatsApp consent taps; the decision is stored before a patient exists and attached when the patient is created. */
class Idc005ChatConsentApiTest extends ApiTestBase {

    @Autowired JwtEncoder encoder;
    @Autowired ChatConsentAttacher attacher;
    @Autowired ConsentRegistry registry;

    private static String hash(String phone) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(phone.replaceAll("\\D", "").getBytes(StandardCharsets.UTF_8)));
    }

    private void notice(UUID tenantId, String version, String lang) {
        inTenantTx(tenantId, () -> jdbc.update("INSERT INTO privacy_notices (tenant_id, version, language, template_key, body_md, mandatory_hash, effective_from) "
                + "VALUES (?,?,?,'IN-DPDP-1','body','h1', now() - interval '1 day')", tenantId, version, lang));
    }

    private Map<String, Object> body(String phone, String version, String wamid, Map<String, Boolean> decisions) throws Exception {
        return Map.of("phoneHash", hash(phone), "noticeVersion", version, "language", "en", "evidence", Map.of("wamid", wamid),
                "decisions", decisions.entrySet().stream().map(e -> Map.of("purpose", e.getKey(), "granted", e.getValue())).toList());
    }

    private ResponseEntity<Map> post(UUID tenant, String token, Object b) {
        return exchange("/internal/v1/tenants/" + tenant + "/patients/consents", HttpMethod.POST, authedJsonBody(token, b), Map.class);
    }

    private int count(UUID tenant, String sql, Object... args) {
        return inTenantTx(tenant, () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    @Test
    void agreeStoresTheDecisionBeforeAnyPatientExistsThenAttachesWithWhatsappChannelAndWamidEvidence() throws Exception {
        SeededTenant t = seedTenant();
        notice(t.id(), "v1", "en");
        String token = InternalTestTokens.mint(encoder, "patient-access-edge", t.id());
        String phone = "+919800000101";
        ResponseEntity<Map> r = post(t.id(), token, body(phone, "v1", "wamid.A1", Map.of("data_processing", true, "messaging", true, "marketing", false)));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((List<?>) r.getBody().get("consentIds")).hasSize(3);
        assertThat(count(t.id(), "SELECT count(*) FROM patients")).as("no patient row yet: the decision came first").isZero();
        assertThat(count(t.id(), "SELECT count(*) FROM chat_consents WHERE wamid = 'wamid.A1'")).isEqualTo(3);

        UUID patient = UUID.randomUUID();
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,'Chat Patient',?,'1990-01-01','female')", patient, t.id(), phone));
        assertThat(inTenantTx(t.id(), () -> attacher.attach(patient, hashQuiet(phone)))).isEqualTo(3);
        assertThat(inTenantTx(t.id(), () -> attacher.attach(patient, hashQuiet(phone)))).as("second attach finds nothing waiting").isZero();

        assertThat(inTenantTx(t.id(), () -> registry.isGranted(patient, Purpose.DATA_PROCESSING))).isTrue();
        assertThat(inTenantTx(t.id(), () -> registry.isGranted(patient, Purpose.MESSAGING))).isTrue();
        assertThat(inTenantTx(t.id(), () -> registry.isGranted(patient, Purpose.MARKETING))).as("marketing stays default-deny").isFalse();
        Map<String, Object> row = inTenantTx(t.id(), () -> jdbc.queryForMap("SELECT channel, notice_version, language, evidence->>'wamid' AS wamid FROM consents "
                + "WHERE patient_id = ? AND consent_type = 'data_processing'", patient));
        assertThat(row).containsEntry("channel", "WHATSAPP").containsEntry("notice_version", "v1").containsEntry("language", "en").containsEntry("wamid", "wamid.A1");
        assertThat(count(t.id(), "SELECT count(*) FROM consents WHERE patient_id = ? AND consent_type = 'marketing' AND withdrawn_at IS NOT NULL", patient))
                .as("the stored marketing value is granted=false").isEqualTo(1);
    }

    private static String hashQuiet(String phone) {
        try {
            return hash(phone);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void marketingNeverPreSelected_omittedMeansStoredNo() throws Exception {
        SeededTenant t = seedTenant();
        notice(t.id(), "v1", "en");
        String token = InternalTestTokens.mint(encoder, "patient-access-edge", t.id());
        assertThat(post(t.id(), token, body("+919800000102", "v1", "wamid.B1", Map.of("data_processing", true))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count(t.id(), "SELECT count(*) FROM chat_consents WHERE wamid = 'wamid.B1' AND purpose = 'marketing' AND granted = false")).isEqualTo(1);
        assertThat(count(t.id(), "SELECT count(*) FROM chat_consents WHERE wamid = 'wamid.B1' AND granted")).isEqualTo(1);
    }

    @Test
    void replayOfTheSameWamidIsIdempotentEvenAfterANewerNoticeIsPublished() throws Exception {
        SeededTenant t = seedTenant();
        notice(t.id(), "v1", "en");
        String token = InternalTestTokens.mint(encoder, "patient-access-edge", t.id());
        Map<String, Object> b = body("+919800000103", "v1", "wamid.C1", Map.of("data_processing", true, "messaging", false));
        ResponseEntity<Map> first = post(t.id(), token, b);
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO privacy_notices (tenant_id, version, language, template_key, body_md, mandatory_hash, effective_from) "
                + "VALUES (?, 'v2', 'en', 'IN-DPDP-1', 'body', 'h2', now())", t.id()));
        ResponseEntity<Map> replay = post(t.id(), token, b);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().get("consentIds")).isEqualTo(first.getBody().get("consentIds"));
        assertThat(count(t.id(), "SELECT count(*) FROM chat_consents WHERE wamid = 'wamid.C1'")).isEqualTo(3);
    }

    @Test
    void staleNoticeVersionIs409_unknownPurposeAndMissingCareDecisionAre400_forgedHashIs400() throws Exception {
        SeededTenant t = seedTenant();
        notice(t.id(), "v2", "en");
        String token = InternalTestTokens.mint(encoder, "patient-access-edge", t.id());
        ResponseEntity<Map> stale = post(t.id(), token, body("+919800000104", "v1", "wamid.D1", Map.of("data_processing", true)));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(stale.getBody().get("type"))).endsWith("notice-version-stale");
        assertThat(count(t.id(), "SELECT count(*) FROM chat_consents")).isZero();
        assertThat(post(t.id(), token, body("+919800000104", "v2", "wamid.D2", Map.of("newsletter", true))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(t.id(), token, body("+919800000104", "v2", "wamid.D3", Map.of("messaging", true))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> bad = new java.util.HashMap<>(body("+919800000104", "v2", "wamid.D4", Map.of("data_processing", true)));
        bad.put("phoneHash", "not-a-hash");
        assertThat(post(t.id(), token, bad).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void currentNoticeEndpointGivesVersionAndPublicPath_otherTenantAndStaffTokenAreRefused_rlsIsolates() throws Exception {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        notice(a.id(), "v7", "en");
        String edgeA = InternalTestTokens.mint(encoder, "patient-access-edge", a.id());
        ResponseEntity<Map> cur = exchange("/internal/v1/tenants/" + a.id() + "/privacy-notice/current?language=en", HttpMethod.GET, authed(edgeA), Map.class);
        assertThat(cur.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cur.getBody()).containsEntry("version", "v7");
        assertThat((String) cur.getBody().get("path")).startsWith("/v1/public/notices/").contains("/v7?lang=en");
        assertThat(exchange("/internal/v1/tenants/" + a.id() + "/privacy-notice/current?language=hi", HttpMethod.GET, authed(edgeA), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(post(b.id(), edgeA, body("+919800000105", "v7", "wamid.E1", Map.of("data_processing", true))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        SeededStaff owner = seedStaff(a, seedFullAccessRole(a.id()), "idc005@a.com", "+919800000106", false);
        assertThat(post(a.id(), loginAndGetAccessToken(owner), body("+919800000105", "v7", "wamid.E2", Map.of("data_processing", true))).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(post(a.id(), edgeA, body("+919800000107", "v7", "wamid.F1", Map.of("data_processing", true))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count(b.id(), "SELECT count(*) FROM chat_consents")).as("RLS: the other clinic sees none").isZero();
        assertThat(count(a.id(), "SELECT count(*) FROM audit_log WHERE action = 'consent.captured_via_chat'")).isEqualTo(1);
    }
}

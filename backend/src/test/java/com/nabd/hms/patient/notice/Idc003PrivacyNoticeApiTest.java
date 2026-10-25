package com.nabd.hms.patient.notice;

import com.nabd.hms.shared.security.Audience;
import com.nabd.hms.shared.security.TokenType;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** IDC-003: seeded v1 notices with the grievance contact, locked mandatory clauses, re-acknowledgement, public page, isolation. */
class Idc003PrivacyNoticeApiTest extends ApiTestBase {

    @Autowired JwtEncoder encoder;

    private record Clinic(SeededTenant tenant, SeededStaff owner, String token) {
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "own" + t.slug() + "@a.com", "+9195" + (10000000 + (int) (Math.random() * 89999999)), false);
        return new Clinic(t, owner, loginAndGetAccessToken(owner));
    }

    private String stepUp(String access) throws Exception {
        Map<String, Object> c = objectMapper.readValue(java.util.Base64.getUrlDecoder().decode(access.split("\\.")[1]), Map.class);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300))
                .subject((String) c.get("sub")).claim("purpose", "step_up").claim("sid", c.get("sid")).claim("tenantId", c.get("tenantId"));
        TokenType.STEP_UP.stamp(b, Audience.STEP_UP);
        return encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private ResponseEntity<Map> setContact(Clinic c) {
        return exchange("/v1/setup/consent-contact", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of(
                "name", "Dr Grievance", "email", "grievance@clinic.example", "phone", "+911234567890")), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> versions(String token) {
        ResponseEntity<Map> r = exchange("/v1/settings/privacy/privacy-notice", HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("versions");
    }

    private ResponseEntity<Map> publish(Clinic c, Map<String, Object> body, boolean withStepUp) throws Exception {
        return exchange("/v1/settings/privacy/privacy-notice", HttpMethod.PUT,
                authedJsonBodyWithHeader(c.token(), "X-Step-Up-Token", withStepUp ? stepUp(c.token()) : null, body), Map.class);
    }

    private String currentBody(Clinic c, String lang) {
        return versions(c.token()).stream().filter(v -> lang.equals(v.get("language")) && Boolean.TRUE.equals(v.get("current")))
                .map(v -> (String) v.get("bodyMd")).findFirst().orElseThrow();
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    @Test
    void ac1_savingTheGrievanceContactGivesV1InFourLanguagesWithTheContactFilledIn() {
        Clinic c = clinic();
        assertThat(versions(c.token())).as("nothing before the contact is saved").isEmpty();
        assertThat(setContact(c).getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> v = versions(c.token());
        assertThat(v).extracting(x -> x.get("language")).containsExactlyInAnyOrder("en", "hi", "kn", "te");
        assertThat(v).allSatisfy(x -> {
            assertThat(x.get("version")).isEqualTo("1");
            assertThat((String) x.get("bodyMd")).contains("Dr Grievance").contains("grievance@clinic.example").contains("Test Clinic");
            assertThat(x.get("mandatoryHash")).asString().hasSize(64);
            assertThat(x.get("templateKey")).isEqualTo("IN-DPDP-1");
            assertThat(x.get("legalReviewed")).as("no legal wording is invented: the DPO has not approved it").isEqualTo(false);
        });
        assertThat(setContact(c).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(versions(c.token())).as("a second save seeds nothing more").hasSize(4);
        assertThat(su().queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'organization.privacy_notice.published.v1'", Integer.class, c.tenant().id())).isEqualTo(1);
    }

    @Test
    void ac2_deletingOrRewordingOrReorderingAMandatoryClauseIs422AndAddingTextPublishesV2() throws Exception {
        Clinic c = clinic();
        setContact(c);
        String body = currentBody(c, "en");
        String[] parts = body.split("\n\n");
        String firstClause = parts[1];
        Map<String, Object> req = Map.of("language", "en", "basedOnTemplate", "IN-DPDP-1");

        ResponseEntity<Map> deleted = publish(c, with(req, "bodyMd", body.replace(firstClause, "")), true);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(String.valueOf(deleted.getBody().get("type"))).endsWith("mandatory-clause-locked");
        ResponseEntity<Map> reworded = publish(c, with(req, "bodyMd", body.replace(firstClause, firstClause.replace("M1", "X1"))), true);
        assertThat(String.valueOf(reworded.getBody().get("type"))).endsWith("mandatory-clause-locked");
        String swapped = body.replace(parts[1], "@@").replace(parts[2], parts[1]).replace("@@", parts[2]);
        assertThat(String.valueOf(publish(c, with(req, "bodyMd", swapped), true).getBody().get("type"))).endsWith("mandatory-clause-locked");
        assertThat(versions(c.token())).as("refusals publish nothing").hasSize(4);

        assertThat(publish(c, with(req, "bodyMd", body + "\n\nExtra clinic paragraph."), false).getStatusCode()).as("step-up required").isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> ok = publish(c, with(req, "bodyMd", body + "\n\nExtra clinic paragraph."), true);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("version")).isEqualTo("2");
        assertThat(versions(c.token()).stream().filter(v -> "en".equals(v.get("language")))).hasSize(2)
                .anySatisfy(v -> assertThat(v).containsEntry("version", "2").containsEntry("current", true));

        ResponseEntity<Map> stale = publish(c, with(Map.of("language", "en", "basedOnTemplate", "IN-DPDP-1", "baseVersion", "1"), "bodyMd", body + "\n\nAnother."), true);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(stale.getBody().get("type"))).endsWith("version-conflict");
        assertThat(String.valueOf(publish(c, Map.of("language", "en", "basedOnTemplate", "NOPE", "bodyMd", body), true).getBody().get("type"))).endsWith("unknown-template");

        Map<String, Object> audit = su().queryForMap("SELECT before::text AS b, after::text AS a FROM audit_log WHERE tenant_id = ? AND action = 'organization.privacy_notice_published' ORDER BY id DESC LIMIT 1", c.tenant().id());
        assertThat((String) audit.get("a")).contains("bodySha256").contains("mandatoryHash");
        assertThat((String) audit.get("b")).contains("bodySha256");
        assertThat(su().queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'organization.privacy_notice.published.v1'", Integer.class, c.tenant().id())).isEqualTo(2);
    }

    private static Map<String, Object> with(Map<String, Object> base, String k, Object v) {
        var m = new java.util.HashMap<>(base);
        m.put(k, v);
        return m;
    }

    @Test
    void ac3_aPatientOnV1IsAskedToReAcknowledgeV2AndOneTapRecordsIt() throws Exception {
        Clinic c = clinic();
        setContact(c);
        UUID patient = UUID.randomUUID();
        UUID noConsent = UUID.randomUUID();
        inTenantTx(c.tenant().id(), () -> {
            for (UUID p : List.of(patient, noConsent)) {
                jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender) VALUES (?,?,?,?,'1990-01-01','female')",
                        p, c.tenant().id(), "Notice Test", "+9198" + (10000000 + (int) (Math.random() * 89999999)));
            }
            jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, notice_version, language, channel) VALUES (?,?,'data_processing','1','en','DESK')",
                    c.tenant().id(), patient);
        });
        String path = "/v1/patients/" + patient + "/privacy-notice";
        assertThat(exchange(path, HttpMethod.GET, authed(c.token()), Map.class).getBody()).containsEntry("needsReAcknowledgement", false);

        String body = currentBody(c, "en");
        assertThat(publish(c, Map.of("language", "en", "basedOnTemplate", "IN-DPDP-1", "bodyMd", body + "\n\nv2 addition"), true).getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked") Map<String, Object> s = exchange(path, HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat(s).containsEntry("needsReAcknowledgement", true).containsEntry("acceptedVersion", "1").containsEntry("currentVersion", "2").containsEntry("language", "en");
        assertThat(exchange("/v1/patients/" + noConsent + "/privacy-notice", HttpMethod.GET, authed(c.token()), Map.class).getBody())
                .as("no consent row is IDC-004's capture, not a re-acknowledgement").containsEntry("needsReAcknowledgement", false);

        ResponseEntity<Map> ack = exchange(path + "/acknowledge", HttpMethod.POST, authed(c.token()), Map.class);
        assertThat(ack.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ack.getBody()).containsEntry("needsReAcknowledgement", false).containsEntry("acceptedVersion", "2");
        assertThat(exchange(path + "/acknowledge", HttpMethod.POST, authed(c.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM consents WHERE patient_id = ? AND notice_version = '2' AND channel = 'DESK' AND captured_by IS NOT NULL", Integer.class, patient)))
                .as("exactly one new consent row, old v1 row untouched").isEqualTo(1);
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM consents WHERE patient_id = ?", Integer.class, patient))).isEqualTo(2);
        assertThat(exchange("/v1/patients/" + UUID.randomUUID() + "/privacy-notice", HttpMethod.GET, authed(c.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ac4_publicPageIsSmallCacheableLanguageTaggedEscapedAndTenantIsolated() throws Exception {
        Clinic c = clinic();
        setContact(c);
        Clinic other = clinic();
        setContact(other);
        String url = "/v1/public/notices/" + c.tenant().slug() + "/1?lang=hi";
        ResponseEntity<String> r = http.getForEntity(url(url), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().toString()).startsWith("text/html");
        assertThat(r.getHeaders().getCacheControl()).contains("public").contains("max-age=");
        assertThat(r.getHeaders().getETag()).isNotBlank();
        assertThat(r.getBody()).contains("<html lang=\"hi\">").contains("Dr Grievance").contains("DRAFT").contains("Test Clinic " + c.tenant().slug());
        assertThat(r.getBody().length()).as("small enough for 3G").isLessThan(4000);
        assertThat(r.getBody()).doesNotContain(other.tenant().slug());

        HttpHeaders h = new HttpHeaders();
        h.setIfNoneMatch(r.getHeaders().getETag());
        assertThat(exchange(url, HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);

        assertThat(http.getForEntity(url("/v1/public/notices/" + c.tenant().slug() + "/9?lang=hi"), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity(url("/v1/public/notices/" + c.tenant().slug() + "/1?lang=fr"), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity(url("/v1/public/notices/nosuchclinic/1"), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // markup in a clinic's own text can never reach the page as HTML
        String body = currentBody(c, "en");
        publish(c, Map.of("language", "en", "basedOnTemplate", "IN-DPDP-1", "bodyMd", body + "\n\n<script>alert(1)</script>"), true);
        String page = http.getForEntity(url("/v1/public/notices/" + c.tenant().slug() + "/2"), String.class).getBody();
        assertThat(page).contains("&lt;script&gt;").doesNotContain("<script>");
    }

    @Test
    void settingsAreTenantScopedAndNeedThePermission() {
        Clinic a = clinic();
        Clinic b = clinic();
        setContact(a);
        assertThat(versions(b.token())).isEmpty();
        assertThat(inTenantTx(b.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM privacy_notices", Integer.class))).isZero();
        UUID noPerm = seedRole(a.tenant().id(), "Nobody", false, fullGrant("queue"));
        SeededStaff s = seedStaff(a.tenant(), noPerm, "np" + a.tenant().slug() + "@a.com", "+919500000099", false);
        assertThat(exchange("/v1/settings/privacy/privacy-notice", HttpMethod.GET, authed(loginAndGetAccessToken(s)), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/settings/privacy/privacy-notice", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}

package com.nabd.hms.organization.export;

import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.messaging.NotificationService;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S21 QA (t1), ADM-015: "only the owner" with a non-owner role that holds setup:export, step-up bound to the caller, other
 * clinic's job, OTP lock after 5 wrong codes, the 4th download through /download itself, and no PHI on the public link.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S21QaAdm015ExportApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(21000);

    @Autowired JwtEncoder encoder;
    @Autowired DataExportJob job;
    @Autowired NotificationService notifications;

    private record Clinic(SeededTenant t, SeededStaff owner, String token, String ownerPhone) {
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static String phone() {
        return "+9193" + (30000000 + SEQ.incrementAndGet() * 11);
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        String mobile = phone();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "qa21-adm015-" + SEQ.incrementAndGet() + "@a.com", mobile, false);
        inTenantTx(t.id(), () -> {
            jdbc.update("INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status) "
                    + "VALUES (?, 'export_ready', ?, 'en', 'UTILITY', 'Nabd', 'Your data export is ready: {{1}}', 1, 'approved')", t.id(), "m_" + UUID.randomUUID().toString().replace("-", ""));
            jdbc.update("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?, 'Zara Khan', ?, '1971-02-03', 'female')", t.id(), phone());
        });
        return new Clinic(t, owner, loginAndGetAccessToken(owner), mobile);
    }

    private String stepUp(String access, String target) throws Exception {
        Map<String, Object> c = objectMapper.readValue(Base64.getUrlDecoder().decode(access.split("\\.")[1]), Map.class);
        Instant now = Instant.now();
        JwtClaimsSet.Builder b = JwtClaimsSet.builder().issuer("https://api.nabd.health").issuedAt(now).expiresAt(now.plusSeconds(300)).id(UUID.randomUUID().toString())
                .subject((String) c.get("sub")).claim("purpose", "step_up").claim("sid", c.get("sid")).claim("tenantId", c.get("tenantId")).claim("target", target);
        TokenType.STEP_UP.stamp(b, Audience.STEP_UP);
        return encoder.encode(JwtEncoderParameters.from(b.build())).getTokenValue();
    }

    private ResponseEntity<Map> start(String access, String stepUp, Map<String, Object> body) {
        return exchange("/v1/setup/export-jobs", HttpMethod.POST, authedJsonBodyWithHeader(access, "X-Step-Up-Token", stepUp, body), Map.class);
    }

    private String linkUrl(Clinic c, String exportId) {
        UUID req = inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT id FROM notification_requests WHERE purpose = 'export_ready' AND subject_id = ?::uuid", UUID.class, exportId));
        return notifications.claim(req).params().get(0);
    }

    private ResponseEntity<Map> requestOtp(String token) {
        return http.exchange(url("/v1/public/exports/" + token + "/otp"), HttpMethod.POST, new HttpEntity<>(new HttpHeaders()), Map.class);
    }

    /** Requests an OTP and swaps its hash for a known code (the real code only goes to the owner's phone). */
    private void knownOtp(String token, String code) {
        assertThat(requestOtp(token).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String hash = OpaqueTokens.sha256Hex(token);
        UUID challenge = su().queryForObject("SELECT otp_challenge_id FROM data_export_links WHERE token_hash = ?", UUID.class, hash);
        su().update("UPDATE data_export_links SET otp_hash = ? WHERE token_hash = ?", OpaqueTokens.sha256Hex(challenge + ":" + code), hash);
    }

    private ResponseEntity<byte[]> download(String token, String code) {
        return http.exchange(url("/v1/public/exports/" + token + "/download"), HttpMethod.POST, jsonBody(Map.of("otp", code)), byte[].class);
    }

    private void allowNextOtp(String token) {
        su().update("UPDATE data_export_links SET otp_expires_at = NULL WHERE token_hash = ?", OpaqueTokens.sha256Hex(token));
    }

    @Test
    void e01_aNonOwnerRoleHoldingSetupExport_cannotStartTheFullExport() throws Exception {
        Clinic c = clinic();
        // the Owner may grant setup:export to a custom role (RoleService allows any grant the owner holds); the spec says owner only
        UUID manager = seedRole(c.t().id(), "Practice Manager", false, fullGrant("setup"), fullGrant("staff"));
        SeededStaff m = seedStaff(c.t(), manager, "qa21-mgr-" + SEQ.incrementAndGet() + "@a.com", phone(), false);
        String mt = loginAndGetAccessToken(m);
        ResponseEntity<Map> r = start(mt, stepUp(mt, "data-export"), Map.of("scope", "full_tenant", "format", "csv"));
        assertThat(r.getStatusCode()).as("non-owner with setup:export: " + r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void e02_aStepUpMintedForTheOwnerIsRefusedWithAnotherStaffAccessToken() throws Exception {
        Clinic a = clinic();
        Clinic b = clinic();
        ResponseEntity<Map> r = start(b.token(), stepUp(a.token(), "data-export"), Map.of("format", "csv"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(su().queryForObject("SELECT count(*) FROM data_export_jobs WHERE tenant_id = ?", Integer.class, b.t().id())).isZero();
    }

    @Test
    void e03_anotherClinicsOwnerCannotReadMyJob_andBadIdsAre4xx() throws Exception {
        Clinic a = clinic();
        Clinic b = clinic();
        String id = (String) start(a.token(), stepUp(a.token(), "data-export"), Map.of("format", "csv", "datasets", List.of("patients"))).getBody().get("id");
        assertThat(exchange("/v1/setup/export-jobs/" + id, HttpMethod.GET, authed(b.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/setup/export-jobs/not-a-uuid", HttpMethod.GET, authed(a.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(start(a.token(), stepUp(a.token(), "data-export"), Map.of("format", "docx")).getStatusCode().value()).isIn(400, 409);
        assertThat(start(a.token(), stepUp(a.token(), "data-export"), Map.of("format", "csv", "datasets", List.of("passwords"))).getStatusCode().value()).isIn(400, 409);
    }

    @Test
    void e04_linkCarriesNoPhi_fiveWrongCodesLockTheOtp_andTheFourthDownloadIsRefused() throws Exception {
        Clinic c = clinic();
        String id = (String) start(c.token(), stepUp(c.token(), "data-export"), Map.of("format", "csv", "datasets", List.of("patients"))).getBody().get("id");
        job.runOnce();
        String link = linkUrl(c, id);
        String token = link.substring(link.lastIndexOf('/') + 1);
        assertThat(link).doesNotContain("Zara").doesNotContain(c.t().id().toString()).doesNotContain(id).doesNotContain(c.ownerPhone().substring(3));
        assertThat(token.length()).isGreaterThanOrEqualTo(32);
        ResponseEntity<Map> view = http.exchange(url("/v1/public/exports/" + token), HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(view.getBody()).containsOnlyKeys("clinicName", "expiresAt", "downloadsLeft");

        // brute force: 5 wrong codes, then even the right code is refused until a new code is requested
        knownOtp(token, "111111");
        for (int i = 0; i < 5; i++) {
            assertThat(download(token, "99999" + i).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(download(token, "111111").getStatusCode()).as("locked after 5 wrong codes").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(su().queryForObject("SELECT downloads FROM data_export_links WHERE token_hash = ?", Integer.class, OpaqueTokens.sha256Hex(token))).isZero();

        // a code is good for one download; three downloads, then /download itself answers 410 even with a valid code
        for (String code : List.of("222222", "333333", "444444")) {
            allowNextOtp(token);
            knownOtp(token, code);
            assertThat(download(token, code).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(download(token, code).getStatusCode()).as("code reuse").isIn(HttpStatus.UNAUTHORIZED, HttpStatus.GONE);
        }
        // force a valid live code after the third download, bypassing /otp (which already answers 410)
        UUID ch = UUID.randomUUID();
        su().update("UPDATE data_export_links SET otp_challenge_id = ?, otp_hash = ?, otp_expires_at = now() + interval '5 minutes', otp_attempts = 0 WHERE token_hash = ?",
                ch, OpaqueTokens.sha256Hex(ch + ":555555"), OpaqueTokens.sha256Hex(token));
        assertThat(download(token, "555555").getStatusCode()).as("4th download").isEqualTo(HttpStatus.GONE);
        assertThat(requestOtp(token).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'data_export.downloaded'", Integer.class, c.t().id())).isEqualTo(3);
    }

    @Test
    void e05_otpRequestedTwiceWithin30sIs429_andAnUnknownTokenIs410NotADifferentAnswer() throws Exception {
        Clinic c = clinic();
        String id = (String) start(c.token(), stepUp(c.token(), "data-export"), Map.of("format", "csv", "datasets", List.of("consents"))).getBody().get("id");
        job.runOnce();
        String link = linkUrl(c, id);
        String token = link.substring(link.lastIndexOf('/') + 1);
        assertThat(requestOtp(token).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(requestOtp(token).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(requestOtp("x".repeat(43)).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(download("x".repeat(43), "123456").getStatusCode()).isEqualTo(HttpStatus.GONE);
    }
}

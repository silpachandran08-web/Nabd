package com.nabd.hms.patient.securelink;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** IDC-015: issue, OTP, open, expiry, lock, limits, recipients, tenant isolation. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Idc015SecureLinkApiTest extends ApiTestBase {

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());
    private static final SecureRandom RND = new SecureRandom();

    private record Fx(SeededTenant t, String staffToken, UUID patient, UUID file) {
    }

    private static String sha(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private Fx fx() {
        SeededTenant t = seedTenant();
        String u = UUID.randomUUID().toString().substring(0, 8);
        String staff = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "sl" + u + "@a.com", "+9195" + String.format("%08d", Math.abs(RND.nextInt(100_000_000))), false));
        inTenantTx(t.id(), () -> jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, 'secure_link', ?, 'en', 'UTILITY', 'Clinic', 'Hello {{1}}, a secure message is ready: {{2}}', 2, 'approved')""", t.id(), "m_" + u));
        UUID p = patient(t.id(), "Asha Rao", "+9190" + String.format("%08d", Math.abs(RND.nextInt(100_000_000))), "1990-01-01", null);
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'messaging')", t.id(), p));
        return new Fx(t, staff, p, file(t.id(), p));
    }

    private UUID patient(UUID t, String name, String phone, String dob, UUID guardian) {
        return inTenantTx(t, () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender, guardian_id) VALUES (?,?,?,?::date,'other',?) RETURNING id",
                UUID.class, t, name, phone, dob, guardian));
    }

    private UUID file(UUID t, UUID patient) {
        UUID id = UUID.randomUUID();
        inTenantTx(t, () -> jdbc.update("INSERT INTO file_objects (id, tenant_id, purpose, storage_key, declared_type, detected_type, size_bytes, status, patient_id) "
                + "VALUES (?,?,'outside_report',?,'application/pdf','application/pdf',1000,'available',?)", id, t, "tenant/" + t + "/" + id, patient));
        return id;
    }

    private ResponseEntity<Map> issue(Fx f, Object body) {
        return exchange("/v1/secure-links", HttpMethod.POST, authedJsonBody(f.staffToken(), body), Map.class);
    }

    private Map<String, Object> issueBody(Fx f, String type) {
        return new HashMap<>(Map.of("documentType", type, "documentId", f.file().toString(), "recipientRef", f.patient().toString()));
    }

    /** A link inserted directly with a token we know (the issue endpoint never returns the token). */
    private record Known(String token, UUID id) {
    }

    private Known link(Fx f, UUID recipient, Instant expires) throws Exception {
        byte[] raw = new byte[48];
        RND.nextBytes(raw);
        ByteBuffer.wrap(raw).putLong(f.t().id().getMostSignificantBits()).putLong(f.t().id().getLeastSignificantBits());
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        UUID id = su.queryForObject("INSERT INTO secure_links (tenant_id, token_hash, document_type, document_id, patient_id, recipient_patient_id, expires_at, created_by) "
                        + "VALUES (?,?,'report',?,?,?,?, gen_random_uuid()) RETURNING id", UUID.class, f.t().id(), sha(token), f.file(), f.patient(), recipient, java.sql.Timestamp.from(expires));
        return new Known(token, id);
    }

    private void setOtp(Known k, String code) throws Exception {
        su.update("UPDATE secure_links SET otp_hash = ?, otp_expires_at = now() + interval '5 minutes' WHERE id = ?", sha(k.id() + ":" + code), k.id());
    }

    private ResponseEntity<Map> otp(String token) {
        return exchange("/v1/public/links/" + token + "/otp", HttpMethod.POST, new HttpEntity<>(headers()), Map.class);
    }

    private ResponseEntity<Map> open(String token, String code) {
        return exchange("/v1/public/links/" + token + "/open", HttpMethod.POST, new HttpEntity<>(Map.of("otp", code), headers()), Map.class);
    }

    private static HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("User-Agent", "idc015-test/1.0");
        return h;
    }

    private int count(String sql, Object... args) {
        return su.queryForObject(sql, Integer.class, args);
    }

    private static String code(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("type"));
    }

    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void issueStoresOnlyAHashAndSendsAPhiFreeMessageWithTheLinkThatOpensThisRow() throws Exception {
        Fx f = fx();
        ResponseEntity<Map> r = issue(f, issueBody(f, "report"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID linkId = UUID.fromString((String) r.getBody().get("linkId"));
        Instant exp = Instant.parse((String) r.getBody().get("expiresAt"));
        assertThat(Duration.between(Instant.now(), exp).toDays()).as("default 7 days").isBetween(6L, 7L);
        UUID req = su.queryForObject("SELECT id FROM notification_requests WHERE subject_id = ?", UUID.class, linkId);
        ResponseEntity<Map> claim = exchange("/internal/v1/notification-requests/" + req, HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders() {{ set("X-Internal-Token", INTERNAL_COMMS_TOKEN); }}), Map.class);
        assertThat(claim.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(claim.getBody()).containsEntry("purpose", "secure_link");
        List<String> params = (List<String>) claim.getBody().get("params");
        assertThat(params).hasSize(2);
        assertThat(params.get(0)).isEqualTo("Asha");
        String token = params.get(1).substring(params.get(1).indexOf("/l/") + 3);
        assertThat(token).hasSize(64).doesNotContain("report");
        assertThat(SecureLinkService.tenantOf(token)).contains(f.t().id());
        assertThat(su.queryForObject("SELECT token_hash FROM secure_links WHERE id = ?", String.class, linkId)).isEqualTo(sha(token)).hasSize(64);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'secure_link.issued' AND entity_id = ?", linkId)).isEqualTo(1);
        // the issued link really works end to end: OTP is sent, then opened with the code
        assertThat(otp(token).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(su.queryForObject("SELECT otp_hash FROM secure_links WHERE id = ?", String.class, linkId)).isNotNull();
        assertThat(count("SELECT count(*) FROM secure_link_opens WHERE link_id = ? AND outcome = 'otp_sent'", linkId)).isEqualTo(1);
        setOtp(new Known(token, linkId), "123456");
        assertThat(open(token, "123456").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void openWithTheRightCodeGivesAShortLivedUrlLogsTheOpenAndTheCodeWorksOnce() throws Exception {
        Fx f = fx();
        Known k = link(f, f.patient(), Instant.now().plus(Duration.ofDays(1)));
        setOtp(k, "424242");
        long t0 = System.nanoTime();
        ResponseEntity<Map> r = open(k.token(), "424242");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("mime", "application/pdf").containsEntry("title", "Report");
        assertThat((String) r.getBody().get("fileUrl")).isNotBlank().doesNotContain(k.token());
        assertThat(r.getHeaders().getCacheControl()).contains("no-store");
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).as("open is one DB round and a URL signature").isLessThan(Duration.ofSeconds(5));
        Map<String, Object> row = su.queryForMap("SELECT outcome, host(ip) AS ip, user_agent, opened_at > now() - interval '1 minute' AS fresh FROM secure_link_opens WHERE link_id = ? AND outcome = 'opened'", k.id());
        assertThat(row).containsEntry("user_agent", "idc015-test/1.0").containsEntry("fresh", true);
        assertThat((String) row.get("ip")).isNotBlank();
        assertThat(count("SELECT open_count FROM secure_links WHERE id = ?", k.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'secure_link.opened' AND entity_id = ?", k.id())).isEqualTo(1);
        ResponseEntity<Map> again = open(k.token(), "424242");
        assertThat(again.getStatusCode()).as("the code is single use").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(code(again)).endsWith("otp-invalid");
        assertThat(open(k.token(), "12ab56").getStatusCode()).as("a non-numeric code is a validation error").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void fiveWrongCodesLockTheLinkForThirtyMinutesEvenForTheRightCode_thenItOpensAgain() throws Exception {
        Fx f = fx();
        Known k = link(f, f.patient(), Instant.now().plus(Duration.ofDays(1)));
        setOtp(k, "111111");
        for (int i = 1; i <= 4; i++) {
            assertThat(open(k.token(), "000000").getStatusCode()).as("wrong attempt " + i).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<Map> fifth = open(k.token(), "000000");
        assertThat(fifth.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(code(fifth)).endsWith("link-locked");
        Instant until = su.queryForObject("SELECT locked_until FROM secure_links WHERE id = ?", java.sql.Timestamp.class, k.id()).toInstant();
        assertThat(Duration.between(Instant.now(), until).toMinutes()).isBetween(28L, 30L);
        setOtp(k, "111111");
        assertThat(open(k.token(), "111111").getStatusCode()).as("the right code does not help while locked").isEqualTo(HttpStatus.LOCKED);
        assertThat(otp(k.token()).getStatusCode()).as("no new code while locked").isEqualTo(HttpStatus.LOCKED);
        assertThat(count("SELECT count(*) FROM secure_link_opens WHERE link_id = ? AND outcome = 'otp_failed'", k.id())).isEqualTo(4);
        su.update("UPDATE secure_links SET locked_until = now() - interval '1 second' WHERE id = ?", k.id());
        setOtp(k, "222222");
        assertThat(open(k.token(), "222222").getStatusCode()).isEqualTo(HttpStatus.OK);
        // the clinic can resend: a fresh link is a new, unlocked row
        Fx g = new Fx(f.t(), f.staffToken(), f.patient(), f.file());
        assertThat(issue(g, issueBody(g, "report")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void expiredLinkIs410WithTheLinkExpiredCode_unknownAndMalformedTokensLookTheSame_boundariesOfTtlDays() throws Exception {
        Fx f = fx();
        Known k = link(f, f.patient(), Instant.now().minusSeconds(1));
        setOtp(k, "333333");
        for (ResponseEntity<Map> r : List.of(otp(k.token()), open(k.token(), "333333"))) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.GONE);
            assertThat(code(r)).endsWith("link-expired");
        }
        assertThat(count("SELECT count(*) FROM secure_link_opens WHERE link_id = ? AND outcome = 'expired'", k.id())).isEqualTo(2);
        assertThat(open("not-a-token", "333333").getStatusCode()).isEqualTo(HttpStatus.GONE);
        Known other = link(f, f.patient(), Instant.now().plus(Duration.ofDays(1)));
        String forged = other.token().substring(0, 60) + (other.token().endsWith("AAAA") ? "BBBB" : "AAAA");
        assertThat(open(forged, "333333").getStatusCode()).as("right tenant, unknown hash").isEqualTo(HttpStatus.GONE);

        Map<String, Object> b = issueBody(f, "report");
        b.put("ttlDays", 0);
        assertThat(issue(f, b).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        b.put("ttlDays", 31);
        assertThat(issue(f, b).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        b.put("ttlDays", 30);
        ResponseEntity<Map> max = issue(f, b);
        assertThat(max.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(Duration.between(Instant.now(), Instant.parse((String) max.getBody().get("expiresAt"))).toDays()).isBetween(29L, 30L);
        b.put("ttlDays", 1);
        assertThat(Duration.between(Instant.now(), Instant.parse((String) issue(f, b).getBody().get("expiresAt"))).toHours()).isBetween(22L, 24L);
        su.update("UPDATE tenants SET secure_link_ttl_days = 3 WHERE id = ?", f.t().id());
        b.remove("ttlDays");
        assertThat(Duration.between(Instant.now(), Instant.parse((String) issue(f, b).getBody().get("expiresAt"))).toDays()).as("tenant setting").isBetween(2L, 3L);
    }

    @Test
    void otpSendLimits_oneEverySixtySeconds_fivePerHourPerLink_tenPerHourPerRecipient() throws Exception {
        Fx f = fx();
        Known k = link(f, f.patient(), Instant.now().plus(Duration.ofDays(1)));
        assertThat(otp(k.token()).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<Map> second = otp(k.token());
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(code(second)).endsWith("otp-rate-limited");
        su.update("UPDATE secure_link_opens SET opened_at = now() - interval '10 minutes' WHERE link_id = ?", k.id());
        assertThat(otp(k.token()).getStatusCode()).as("after the gap").isEqualTo(HttpStatus.ACCEPTED);
        for (int i = 0; i < 3; i++) {
            su.update("INSERT INTO secure_link_opens (tenant_id, link_id, opened_at, outcome) VALUES (?,?, now() - interval '5 minutes', 'otp_sent')", f.t().id(), k.id());
        }
        su.update("UPDATE secure_link_opens SET opened_at = now() - interval '5 minutes' WHERE link_id = ? AND opened_at > now() - interval '1 minute'", k.id());
        assertThat(count("SELECT count(*) FROM secure_link_opens WHERE link_id = ? AND outcome = 'otp_sent'", k.id())).isEqualTo(5);
        assertThat(otp(k.token()).getStatusCode()).as("sixth in the hour").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // per recipient: ten sends over several links of one patient (4 + 4 more, each link itself under its own limit of 5)
        for (int link = 0; link < 2; link++) {
            Known extra = link(f, f.patient(), Instant.now().plus(Duration.ofDays(1)));
            for (int i = 0; i < 4; i++) {
                su.update("INSERT INTO secure_link_opens (tenant_id, link_id, opened_at, outcome) VALUES (?,?, now() - interval '5 minutes', 'otp_sent')", f.t().id(), extra.id());
            }
        }
        Known fresh = link(f, f.patient(), Instant.now().plus(Duration.ofDays(1)));
        assertThat(otp(fresh.token()).getStatusCode()).as("13 sends for this recipient in the hour").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void recipientRules_bookOnlyProxyGetsNoClinicalLink_guardianOfAMinorDoes_strangerAndRxAndInvoiceAreRefused() throws Exception {
        Fx f = fx();
        UUID elder = patient(f.t().id(), "Lakshmi Devi", "+919000002001", "1950-01-01", null);
        UUID caregiver = patient(f.t().id(), "Meera Mother", "+919000002002", "1988-01-01", null);
        inTenantTx(f.t().id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'messaging')", f.t().id(), caregiver));
        UUID elderFile = file(f.t().id(), elder);
        inTenantTx(f.t().id(), () -> jdbc.update("INSERT INTO chat_added_members (tenant_id, caregiver_patient_id, member_patient_id, relation, proxy_scope, proxy_status) "
                + "VALUES (?,?,?,'parent','BOOK_ONLY','pending_desk_confirmation')", f.t().id(), caregiver, elder));
        Fx forElder = new Fx(f.t(), f.staffToken(), elder, elderFile);
        Map<String, Object> toCaregiver = issueBody(forElder, "report");
        toCaregiver.put("recipientRef", caregiver.toString());
        ResponseEntity<Map> refused = issue(forElder, toCaregiver);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(refused)).endsWith("recipient-not-allowed");
        assertThat(count("SELECT count(*) FROM secure_links WHERE patient_id = ?", elder)).isZero();
        assertThat(issue(forElder, issueBody(forElder, "report")).getStatusCode()).as("the patient herself").isEqualTo(HttpStatus.CREATED);
        su.update("UPDATE chat_added_members SET proxy_scope = 'GUARDIAN' WHERE member_patient_id = ?", elder);
        assertThat(issue(forElder, toCaregiver).getStatusCode()).as("a proxy with clinical scope").isEqualTo(HttpStatus.CREATED);

        UUID child = patient(f.t().id(), "Kid Kumar", "+919000002003", LocalDateString.yearsAgo(6), caregiver);
        Fx forChild = new Fx(f.t(), f.staffToken(), child, file(f.t().id(), child));
        Map<String, Object> toGuardian = issueBody(forChild, "report");
        toGuardian.put("recipientRef", caregiver.toString());
        assertThat(issue(forChild, toGuardian).getStatusCode()).as("guardian of a minor").isEqualTo(HttpStatus.CREATED);
        Map<String, Object> toStranger = issueBody(f, "report");
        toStranger.put("recipientRef", caregiver.toString());
        assertThat(issue(f, toStranger).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(issue(f, issueBody(f, "rx")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(issue(f, issueBody(f, "invoice")).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(issue(f, issueBody(f, "unknown")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> noFile = issueBody(f, "report");
        noFile.put("documentId", UUID.randomUUID().toString());
        assertThat(issue(f, noFile).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void permissionTenantIsolationAndWithdrawnConsent() throws Exception {
        Fx f = fx();
        Fx other = fx();
        // no token: 401; a clinic without patients:edit: 403
        assertThat(exchange("/v1/secure-links", HttpMethod.POST, new HttpEntity<>(issueBody(f, "report"), headers()), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        UUID roleNoEdit = seedRole(f.t().id(), "Viewer " + UUID.randomUUID().toString().substring(0, 5), false);
        String viewer = loginAndGetAccessToken(seedStaff(f.t(), roleNoEdit, "viewer" + UUID.randomUUID().toString().substring(0, 6) + "@a.com", "+919500009999", false));
        assertThat(exchange("/v1/secure-links", HttpMethod.POST, authedJsonBody(viewer, issueBody(f, "report")), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // staff of another clinic cannot name this clinic's file or patient
        assertThat(issue(other, issueBody(f, "report")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // RLS: the other clinic sees no row of this clinic's links or opens
        Known k = link(f, f.patient(), Instant.now().plus(Duration.ofDays(1)));
        setOtp(k, "555555");
        assertThat(open(k.token(), "555555").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(inTenantTx(other.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM secure_links", Integer.class))).isZero();
        assertThat(inTenantTx(other.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM secure_link_opens", Integer.class))).isZero();
        assertThat(inTenantTx(f.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM secure_link_opens", Integer.class))).isPositive();
        // max opens
        Known one = link(f, f.patient(), Instant.now().plus(Duration.ofDays(1)));
        su.update("UPDATE secure_links SET max_opens = 1, open_count = 1 WHERE id = ?", one.id());
        setOtp(one, "777777");
        assertThat(open(one.token(), "777777").getStatusCode()).as("open_count already 1 of max 1").isEqualTo(HttpStatus.GONE);
        // a withdrawn processing consent stops an already issued link
        su.update("UPDATE tenants SET consent_default_deny = true WHERE id = ?", f.t().id());
        inTenantTx(f.t().id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, withdrawn_at) VALUES (?,?,'data_processing', now())", f.t().id(), f.patient()));
        setOtp(k, "666666");
        assertThat(open(k.token(), "666666").getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    private static final class LocalDateString {
        static String yearsAgo(int n) {
            return java.time.LocalDate.now().minusYears(n).toString();
        }
    }
}

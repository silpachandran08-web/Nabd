package com.nabd.hms.patient;

import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** IDC-009: an adult family member approves proxy access through an OTP consent link; until then the caregiver books but sees no records. */
class Idc009ProxyApiTest extends ApiTestBase {

    @Autowired JwtEncoder encoder;
    @Autowired PatientRefs refs;

    private record Clinic(SeededTenant t, String edge, UUID caregiver, String caregiverRef, String caregiverPhone) {
        String base() {
            return "/internal/v1/tenants/" + t.id();
        }
    }

    private static String phone() {
        return "+9197" + (10000000 + (int) (Math.random() * 89999999));
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private Clinic clinic(boolean withTemplate) {
        SeededTenant t = seedTenant();
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO privacy_notices (tenant_id, version, language, template_key, body_md, mandatory_hash, effective_from) "
                + "VALUES (?, 'v1', 'en', 'IN-DPDP-1', 'body', 'h1', now() - interval '1 day')", t.id()));
        if (withTemplate) {
            inTenantTx(t.id(), () -> jdbc.update("INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status) "
                    + "VALUES (?, 'proxy_invite', ?, 'en', 'UTILITY', 'Clinic', 'A family member asked for your approval: {{1}}', 1, 'approved')", t.id(), "m_" + UUID.randomUUID().toString().replace("-", "")));
        }
        String phone = phone();
        UUID caregiver = inTenantTx(t.id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?, 'Divya Pillai', ?, '1985-05-05', 'female') RETURNING id", UUID.class, t.id(), phone));
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'data_processing')", t.id(), caregiver));
        String ref = inTenantTx(t.id(), () -> refs.refFor(caregiver));
        return new Clinic(t, InternalTestTokens.mint(encoder, "patient-access-edge", t.id()), caregiver, ref, phone);
    }

    private ResponseEntity<Map> add(Clinic c, String name, String dob, String relation, String phone) {
        Map<String, Object> m = new HashMap<>();
        m.put("caregiverRef", c.caregiverRef());
        m.put("name", name);
        m.put("dob", dob);
        m.put("relation", relation);
        m.put("phone", phone);
        return exchange(c.base() + "/family/members", HttpMethod.POST, authedJsonBody(c.edge(), m), Map.class);
    }

    private ResponseEntity<Map> access(Clinic c, String memberRef, String need) {
        return exchange(c.base() + "/family/members/" + memberRef + "/access?caregiverRef=" + c.caregiverRef() + "&need=" + need, HttpMethod.GET, authed(c.edge()), Map.class);
    }

    /** The token from the WhatsApp message the member got (read through Comms' claim endpoint, the way the sender does). */
    @SuppressWarnings("unchecked")
    private String lastToken(Clinic c, String proxyId) {
        UUID req = inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT id FROM notification_requests WHERE purpose = 'proxy_invite' AND subject_id = ?::uuid ORDER BY created_at DESC LIMIT 1", UUID.class, proxyId));
        HttpHeaders h = new HttpHeaders();
        h.set("X-Internal-Token", INTERNAL_COMMS_TOKEN);
        ResponseEntity<Map> claim = exchange("/internal/v1/notification-requests/" + req, HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat(claim.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> params = (List<String>) claim.getBody().get("params");
        assertThat(params).as("the link is the only parameter: no name, no relation").hasSize(1);
        assertThat(params.get(0)).contains("/proxy-invite/");
        return params.get(0).substring(params.get(0).lastIndexOf('/') + 1);
    }

    private String proxyId(Clinic c, String memberRef) {
        UUID member = inTenantTx(c.t().id(), () -> refs.patientIdFor(memberRef).orElseThrow());
        return inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT id::text FROM patient_proxies WHERE subject_patient_id = ? AND status <> 'revoked'", String.class, member));
    }

    private ResponseEntity<Map> pub(String path, HttpMethod m, Object body) {
        return http.exchange(url(path), m, body == null ? new HttpEntity<>(new HttpHeaders()) : jsonBody(body), Map.class);
    }

    /** Asks for the OTP and swaps its hash for a known code (the real code only goes to the member's phone). */
    private void otp(String token, String code) {
        assertThat(pub("/v1/proxy-invites/" + token + "/otp", HttpMethod.POST, null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String hash = OpaqueTokens.sha256Hex(token);
        UUID challenge = su().queryForObject("SELECT otp_challenge_id FROM proxy_invites WHERE token_hash = ?", UUID.class, hash);
        su().update("UPDATE proxy_invites SET otp_hash = ? WHERE token_hash = ?", OpaqueTokens.sha256Hex(challenge + ":" + code), hash);
    }

    private ResponseEntity<Map> accept(String token, String code, String scope) {
        return pub("/v1/proxy-invites/" + token + "/accept", HttpMethod.POST, Map.of("otp", code, "scope", scope));
    }

    @Test
    void ac1_ac2_addingAnAdultSendsALinkAndLeavesTheProxyPending_bookingStaysOpenRecordsAreRefused() {
        Clinic c = clinic(true);
        ResponseEntity<Map> r = add(c, "Lakshmi Pillai", "1958-03-04", "daughter", phone());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).containsEntry("proxyStatus", "pending");
        String memberRef = (String) r.getBody().get("memberRef");
        String proxy = proxyId(c, memberRef);
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT status || '/' || coalesce(scope, '-') FROM patient_proxies WHERE id = ?::uuid", String.class, proxy))).isEqualTo("pending/-");
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM notification_requests WHERE purpose = 'proxy_invite' AND status = 'requested'", Integer.class))).isEqualTo(1);

        String token = lastToken(c, proxy);
        Map<String, Object> invite = su().queryForMap("SELECT token_hash, expires_at > now() + interval '6 days 23 hours' AS seven_days, expires_at < now() + interval '7 days 1 hour' AS not_more FROM proxy_invites WHERE proxy_id = ?::uuid", proxy);
        assertThat(invite.get("token_hash")).isEqualTo(OpaqueTokens.sha256Hex(token)).isNotEqualTo(token);
        assertThat(invite).containsEntry("seven_days", true).containsEntry("not_more", true);

        ResponseEntity<Map> records = access(c, memberRef, "records");
        assertThat(records.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(records.getBody()).containsEntry("code", "proxy-consent-pending");
        ResponseEntity<Map> book = access(c, memberRef, "book");
        assertThat(book.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(book.getBody()).containsEntry("relation", "daughter").containsEntry("status", "pending");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) exchange(c.base() + "/family/members?caregiverRef=" + c.caregiverRef(), HttpMethod.GET, authed(c.edge()), Map.class).getBody().get("members");
        assertThat(list).hasSize(1);
        assertThat(list.get(0)).containsEntry("status", "pending").containsEntry("firstName", "Lakshmi");
    }

    @Test
    void ac3_ac5_bookOnlyBecomesActiveWithEvidence_recordsStayHidden_andTheLinkIsSingleUse() {
        Clinic c = clinic(true);
        String memberRef = (String) add(c, "Kamala Pillai", "1955-01-02", "son", phone()).getBody().get("memberRef");
        String proxy = proxyId(c, memberRef);
        String token = lastToken(c, proxy);

        ResponseEntity<Map> view = pub("/v1/proxy-invites/" + token, HttpMethod.GET, null);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(view.getBody()).containsEntry("relation", "son").containsEntry("caregiverFirstName", "Divya");

        otp(token, "123456");
        ResponseEntity<Map> wrong = accept(token, "654321", "BOOK_ONLY");
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrong.getBody()).containsEntry("code", "otp-invalid");
        assertThat(accept(token, "123456", "EVERYTHING").getStatusCode()).as("unknown scope").isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> ok = accept(token, "123456", "BOOK_ONLY");
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("status", "active").containsEntry("scope", "BOOK_ONLY");
        assertThat(access(c, memberRef, "book").getBody()).containsEntry("scope", "BOOK_ONLY").containsEntry("status", "active");
        ResponseEntity<Map> records = access(c, memberRef, "records");
        assertThat(records.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(records.getBody()).containsEntry("code", "proxy-scope-insufficient");

        // evidence is stored and audited
        Map<String, Object> row = inTenantTx(c.t().id(), () -> jdbc.queryForMap("SELECT notice_version, evidence->>'otpChallengeId' AS challenge, evidence->>'ip' AS ip, evidence->>'userAgent' AS ua, "
                + "evidence->>'noticeVersion' AS nv, accepted_at IS NOT NULL AS accepted FROM patient_proxies WHERE id = ?::uuid", proxy));
        assertThat(row).containsEntry("notice_version", "v1").containsEntry("nv", "v1").containsEntry("accepted", true);
        assertThat((String) row.get("challenge")).isNotBlank();
        assertThat(row.get("ip")).isNotNull();
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'patient.proxy_accepted' AND entity_id = ?::uuid AND after->>'otpChallengeId' = ?",
                Integer.class, proxy, row.get("challenge")))).isEqualTo(1);

        // single use: the same choice again is the same answer, another choice is refused, the page says gone, and the OTP is gone
        assertThat(accept(token, "123456", "BOOK_ONLY").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accept(token, "123456", "FULL").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(pub("/v1/proxy-invites/" + token, HttpMethod.GET, null).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(su().queryForObject("SELECT otp_hash IS NULL FROM proxy_invites WHERE token_hash = ?", Boolean.class, OpaqueTokens.sha256Hex(token))).isTrue();
    }

    @Test
    void theScopeMatrix_bookAndRecordsAndFullSeeRecords() {
        Clinic c = clinic(true);
        for (String scope : List.of("BOOK_AND_RECORDS", "FULL")) {
            String memberRef = (String) add(c, "Member " + scope, "1950-01-01", "mother", phone()).getBody().get("memberRef");
            String token = lastToken(c, proxyId(c, memberRef));
            otp(token, "111111");
            assertThat(accept(token, "111111", scope).getStatusCode()).isEqualTo(HttpStatus.OK);
            ResponseEntity<Map> records = access(c, memberRef, "records");
            assertThat(records.getStatusCode()).as(scope).isEqualTo(HttpStatus.OK);
            assertThat(records.getBody()).containsEntry("scope", scope);
        }
    }

    @Test
    void ac4_aLinkOlderThanSevenDaysIsGone_theCaregiverResendsAndOnlyTheNewLinkWorks() {
        Clinic c = clinic(true);
        String memberRef = (String) add(c, "Old Link", "1950-02-02", "father", phone()).getBody().get("memberRef");
        String proxy = proxyId(c, memberRef);
        String old = lastToken(c, proxy);
        su().update("UPDATE proxy_invites SET expires_at = now() - interval '1 minute' WHERE token_hash = ?", OpaqueTokens.sha256Hex(old));
        assertThat(pub("/v1/proxy-invites/" + old, HttpMethod.GET, null).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(pub("/v1/proxy-invites/" + old + "/otp", HttpMethod.POST, null).getStatusCode()).isEqualTo(HttpStatus.GONE);
        ResponseEntity<Map> gone = accept(old, "123456", "FULL");
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(gone.getBody()).containsEntry("code", "invite-expired");
        assertThat(pub("/v1/proxy-invites/" + OpaqueTokens.generate(), HttpMethod.GET, null).getStatusCode()).as("unknown token looks the same").isEqualTo(HttpStatus.GONE);

        assertThat(exchange(c.base() + "/family/members/" + memberRef + "/resend", HttpMethod.POST, authedJsonBody(c.edge(), Map.of("caregiverRef", c.caregiverRef())), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        String fresh = lastToken(c, proxy);
        assertThat(fresh).isNotEqualTo(old);
        assertThat(pub("/v1/proxy-invites/" + fresh, HttpMethod.GET, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        otp(fresh, "222222");
        assertThat(accept(fresh, "222222", "FULL").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange(c.base() + "/family/members/" + memberRef + "/resend", HttpMethod.POST, authedJsonBody(c.edge(), Map.of("caregiverRef", c.caregiverRef())), Map.class).getStatusCode())
                .as("nothing pending to resend").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theOtpIsThrottled_lockedAfterFiveWrongCodes_andExpires() {
        Clinic c = clinic(true);
        String memberRef = (String) add(c, "Otp Limits", "1951-01-01", "aunt", phone()).getBody().get("memberRef");
        String token = lastToken(c, proxyId(c, memberRef));
        otp(token, "333333");
        assertThat(pub("/v1/proxy-invites/" + token + "/otp", HttpMethod.POST, null).getStatusCode()).as("a second code within 30 s").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        for (int i = 0; i < 5; i++) {
            assertThat(accept(token, "000000", "FULL").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(accept(token, "333333", "FULL").getStatusCode()).as("locked: even the right code fails").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT status FROM patient_proxies WHERE subject_patient_id = (SELECT id FROM patients WHERE name = 'Otp Limits')", String.class))).isEqualTo("pending");

        su().update("UPDATE proxy_invites SET otp_expires_at = now() - interval '1 second', otp_attempts = 0 WHERE token_hash = ?", OpaqueTokens.sha256Hex(token));
        assertThat(accept(token, "333333", "FULL").getStatusCode()).as("expired code").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(accept(token, "12345", "FULL").getStatusCode()).as("not six digits").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void refusals_ownPhone_duplicate_noTemplate_unknownCaregiver_andAMinorUsesTheGuardianModel() {
        Clinic c = clinic(true);
        assertThat(add(c, "Same Phone", "1950-01-01", "mother", c.caregiverPhone()).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(add(c, "No Phone", "1950-01-01", "mother", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(add(c, "Bad Relation", "1950-01-01", "My Mother!", phone()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String p = phone();
        assertThat(add(c, "Once Only", "1950-01-01", "mother", p).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(add(c, "Once Only", "1950-01-01", "mother", p).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM patients WHERE name = 'Once Only'", Integer.class))).isEqualTo(1);

        Map<String, Object> unknown = new HashMap<>(Map.of("caregiverRef", "no-such-ref", "name", "X Y", "dob", "1950-01-01", "relation", "mother", "phone", phone()));
        assertThat(exchange(c.base() + "/family/members", HttpMethod.POST, authedJsonBody(c.edge(), unknown), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> child = add(c, "Little One", LocalDate.now().minusYears(6).toString(), "son", null);
        assertThat(child.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(child.getBody()).containsEntry("proxyStatus", "guardian");
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM patients WHERE name = 'Little One' AND guardian_id = ?", Integer.class, c.caregiver()))).isEqualTo(1);
        assertThat(access(c, (String) child.getBody().get("memberRef"), "records").getStatusCode()).as("a guardian sees records").isEqualTo(HttpStatus.OK);
        assertThat(inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_proxies", Integer.class))).as("the child is not a proxy row").isEqualTo(1);

        // no approved template: the invite cannot be sent, so nothing is created
        Clinic bare = clinic(false);
        ResponseEntity<Map> noTemplate = add(bare, "No Template", "1950-01-01", "mother", phone());
        assertThat(noTemplate.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(inTenantTx(bare.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM patients WHERE name = 'No Template'", Integer.class))).isZero();
        assertThat(inTenantTx(bare.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_proxies", Integer.class))).isZero();
    }

    @Test
    void tenantIsolationAndAuth() {
        Clinic a = clinic(true), b = clinic(true);
        String memberRef = (String) add(a, "Iso Late", "1950-01-01", "mother", phone()).getBody().get("memberRef");
        String token = lastToken(a, proxyId(a, memberRef));
        // clinic B's Edge may not act in A, and a staff-less caller gets nothing
        assertThat(exchange(a.base() + "/family/members?caregiverRef=" + a.caregiverRef(), HttpMethod.GET, authed(b.edge()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(a.base() + "/family/members/" + memberRef + "/access?caregiverRef=" + a.caregiverRef(), HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // clinic B cannot use A's caregiver or member reference
        assertThat(exchange(b.base() + "/family/members/" + memberRef + "/access?caregiverRef=" + a.caregiverRef(), HttpMethod.GET, authed(b.edge()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // RLS: B sees none of A's rows
        assertThat(inTenantTx(b.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_proxies", Integer.class))).isZero();
        assertThat(inTenantTx(b.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM proxy_invites", Integer.class))).isZero();
        // the public link needs no session, and the secret is only the token
        assertThat(pub("/v1/proxy-invites/" + token, HttpMethod.GET, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pub("/v1/proxy-invites/" + token + "x", HttpMethod.GET, null).getStatusCode()).isEqualTo(HttpStatus.GONE);
    }
}

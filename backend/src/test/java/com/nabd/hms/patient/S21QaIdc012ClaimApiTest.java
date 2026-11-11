package com.nabd.hms.patient;

import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.messaging.NotificationService;
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
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S21 QA (t1), IDC-012 at real time: an adult whose 18th birthday was 2 days ago (claim open now). OTP brute force on the public
 * link, scope outside the allowed set, desk claim across tenants, desk claim without an own number, the claim after the
 * link claim, and what the handover event carries.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S21QaIdc012ClaimApiTest extends ApiTestBase {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final AtomicInteger SEQ = new AtomicInteger(21500);

    @Autowired ProfileClaimJob job;
    @Autowired NotificationService notifications;

    private record Clinic(SeededTenant t, UUID guardian, String guardianPhone, String staff) {
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static String phone() {
        return "+9191" + (10000000 + SEQ.incrementAndGet() * 7);
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", ZONE.getId(), t.id());
        String gPhone = phone();
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status) "
                + "VALUES (?, 'profile_claim_invite', ?, 'en', 'UTILITY', 'Clinic', '{{1}} can now claim their own profile: {{2}}', 2, 'approved')", t.id(), "m_" + UUID.randomUUID().toString().replace("-", "")));
        UUID g = inTenantTx(t.id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?, 'Kavya Menon', ?, '1979-04-04', 'female') RETURNING id", UUID.class, t.id(), gPhone));
        String staff = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "qa21-idc012-" + SEQ.incrementAndGet() + "@a.com", phone(), false));
        return new Clinic(t, g, gPhone, staff);
    }

    /** 18th birthday {@code days} from today in the clinic zone (negative = already 18). */
    private UUID young(Clinic c, int days, String ownPhone) {
        LocalDate dob = LocalDate.now(ZONE).plusDays(days).minusYears(18);
        UUID id = inTenantTx(c.t().id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender, guardian_id) VALUES (?, 'Riya Menon', ?, ?, 'female', ?) RETURNING id",
                UUID.class, c.t().id(), ownPhone == null ? c.guardianPhone() : ownPhone, java.sql.Date.valueOf(dob), c.guardian()));
        inTenantTx(c.t().id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, guardian_patient_id) VALUES (?,?,'guardian_access',?)", c.t().id(), id, c.guardian()));
        return id;
    }

    private String token(Clinic c, UUID patient) {
        UUID req = inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT id FROM notification_requests WHERE purpose = 'profile_claim_invite' AND subject_id = ? ORDER BY created_at LIMIT 1", UUID.class, patient));
        String link = notifications.claim(req).params().get(1);
        return link.substring(link.lastIndexOf('/') + 1);
    }

    private ResponseEntity<Map> pub(String path, HttpMethod m, Object body) {
        return http.exchange(url(path), m, body == null ? new HttpEntity<>(new HttpHeaders()) : jsonBody(body), Map.class);
    }

    private void knownCode(UUID patient, String code) {
        Map<String, Object> row = su().queryForMap("SELECT token_hash, otp_challenge_id FROM profile_claims WHERE patient_id = ? AND outcome IS NULL", patient);
        su().update("UPDATE profile_claims SET otp_hash = ? WHERE token_hash = ?", OpaqueTokens.sha256Hex(row.get("otp_challenge_id") + ":" + code), row.get("token_hash"));
    }

    @Test
    void c01_fiveWrongCodesLockTheLinkCode_theRightCodeIsThenRefused_andNothingChanges() {
        Clinic c = clinic();
        UUID p = young(c, -2, phone());
        job.runOnce();
        String base = "/v1/me/profile-claims/" + token(c, p);
        assertThat(pub(base + "/otp", HttpMethod.POST, null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        knownCode(p, "868686");
        for (int i = 0; i < 5; i++) {
            assertThat(pub(base, HttpMethod.POST, Map.of("otp", "10000" + i, "keepGuardianScope", "NONE")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(pub(base, HttpMethod.POST, Map.of("otp", "868686", "keepGuardianScope", "NONE")).getStatusCode()).as("locked").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(su().queryForObject("SELECT guardian_id FROM patients WHERE id = ?", UUID.class, p)).isEqualTo(c.guardian());
        assertThat(pub(base + "/otp", HttpMethod.POST, null).getStatusCode()).as("new code within 30 s").isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void c02_scopeOutsideTheAllowedSetIs400_andLowercaseIsNotSilentlyAccepted() {
        Clinic c = clinic();
        UUID p = young(c, -2, phone());
        job.runOnce();
        String base = "/v1/me/profile-claims/" + token(c, p);
        assertThat(pub(base + "/otp", HttpMethod.POST, null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        knownCode(p, "131313");
        assertThat(pub(base, HttpMethod.POST, Map.of("otp", "131313", "keepGuardianScope", "FULL")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(pub(base, HttpMethod.POST, Map.of("otp", "131313", "keepGuardianScope", "book_only")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(su().queryForObject("SELECT guardian_id FROM patients WHERE id = ?", UUID.class, p)).isEqualTo(c.guardian());
        // a missing scope is the safe default (NONE), never a proxy
        ResponseEntity<Map> ok = pub(base, HttpMethod.POST, Map.of("otp", "131313"));
        assertThat(ok.getStatusCode().value()).isIn(200, 400);
        if (ok.getStatusCode() == HttpStatus.OK) {
            assertThat(su().queryForObject("SELECT count(*) FROM patient_proxies WHERE subject_patient_id = ? AND status = 'active'", Integer.class, p)).isZero();
        }
    }

    @Test
    void c03_deskClaimAcrossTenantsIs404_andWithoutAnOwnNumberIs422() {
        Clinic a = clinic(), b = clinic();
        UUID adult = young(a, -3, phone());
        assertThat(exchange("/v1/patients/" + adult + "/profile-claim/otp", HttpMethod.POST, authed(b.staff()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/patients/" + adult + "/profile-claim", HttpMethod.POST, authedJsonBody(b.staff(), Map.of("otp", "123456", "keepGuardianScope", "NONE")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        UUID shared = young(a, -3, null);
        ResponseEntity<Map> r = exchange("/v1/patients/" + shared + "/profile-claim/otp", HttpMethod.POST, authed(a.staff()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody()).containsEntry("code", "own-phone-required");
        assertThat(exchange("/v1/patients/" + UUID.randomUUID() + "/profile-claim/otp", HttpMethod.POST, authed(a.staff()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void c04_afterTheLinkClaim_theDeskCannotClaimAgain_andTheRowLeavesTheWorklist() {
        Clinic c = clinic();
        UUID p = young(c, -2, phone());
        job.runOnce();
        String base = "/v1/me/profile-claims/" + token(c, p);
        assertThat(pub(base + "/otp", HttpMethod.POST, null).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        knownCode(p, "202020");
        assertThat(pub(base, HttpMethod.POST, Map.of("otp", "202020", "keepGuardianScope", "BOOK_AND_RECORDS")).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> desk = exchange("/v1/patients/" + p + "/profile-claim/otp", HttpMethod.POST, authed(c.staff()), Map.class);
        assertThat(desk.getStatusCode().value()).as(String.valueOf(desk.getBody())).isIn(404, 409);
        for (String s : List.of("due", "overdue")) {
            List<Map<String, Object>> rows = exchange("/v1/patients/guardian-reviews-due?status=" + s, HttpMethod.GET, authed(c.staff()), List.class).getBody();
            assertThat(rows).extracting(r -> r.get("patientId")).doesNotContain(p.toString());
        }
        assertThat(exchange("/v1/patients/guardian-reviews-due?status=DUE", HttpMethod.GET, authed(c.staff()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/patients/guardian-reviews-due?status=", HttpMethod.GET, authed(c.staff()), Map.class).getStatusCode().value()).isIn(200, 400);
    }

    @Test
    void c05_theHandoverEventCarriesOnlyRefsAndTheHandoverDate_noNameOrPhone() {
        Clinic c = clinic();
        UUID p = young(c, 7, phone());
        job.runOnce();
        String raw = inTenantTx(c.t().id(), () -> jdbc.queryForObject(
                "SELECT envelope->>'data' FROM outbox_cloudevents WHERE tenant_id = ? AND envelope->>'type' = 'patient.guardian.handover_due.v1'", String.class, c.t().id()));
        assertThat(raw).doesNotContain("Riya").doesNotContain("Menon").doesNotContain(c.guardianPhone().substring(3)).doesNotContain(p.toString());
        // documented by the engineer for security review: handoverDate = dob + 18 years, so the DOB is derivable by any consumer
        assertThat(raw).contains(LocalDate.now(ZONE).plusDays(7).toString());
    }
}

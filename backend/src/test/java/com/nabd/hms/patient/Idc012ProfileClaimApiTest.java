package com.nabd.hms.patient;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.messaging.NotificationService;
import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.InternalTestTokens;
import com.nabd.hms.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IDC-012: the age-18 handover. The clock is frozen around the birthday (Asia/Kolkata clinic): invitation 14 days before, claim
 * from the birthday with an OTP to the adult's own number, guardian access ending 30 days after with no claim, and the desk path.
 * HTTP calls that carry a staff or Edge token are made at real time (the decoder checks expiry against the wall clock).
 */
@Import(Idc012ProfileClaimApiTest.FrozenClock.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class Idc012ProfileClaimApiTest extends ApiTestBase {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final AtomicInteger SEQ = new AtomicInteger(5000);

    @Autowired MutableClock clock;
    @Autowired ProfileClaimJob job;
    @Autowired NotificationService notifications;
    @Autowired PatientRefs refs;
    @Autowired JwtEncoder encoder;

    @AfterEach
    void realTime() {
        clock.reset();
    }

    private record Clinic(SeededTenant t, UUID guardian, String guardianRef, String guardianPhone) {
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static String phone() {
        return "+9198" + (10000000 + SEQ.incrementAndGet() * 7);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZONE);
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", ZONE.getId(), t.id());
        String gPhone = phone();
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status) "
                + "VALUES (?, 'profile_claim_invite', ?, 'en', 'UTILITY', 'Clinic', '{{1}} can now claim their own profile: {{2}}', 2, 'approved')", t.id(), "m_" + UUID.randomUUID().toString().replace("-", "")));
        UUID g = inTenantTx(t.id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?, 'Divya Pillai', ?, '1980-05-05', 'female') RETURNING id", UUID.class, t.id(), gPhone));
        return new Clinic(t, g, inTenantTx(t.id(), () -> refs.refFor(g)), gPhone);
    }

    /** A minor whose 18th birthday is {@code days} from the clinic's today; {@code ownPhone} null = the guardian's number. */
    private UUID minor(Clinic c, int days, String ownPhone) {
        LocalDate dob = today().plusDays(days).minusYears(18);
        UUID id = inTenantTx(c.t().id(), () -> jdbc.queryForObject("INSERT INTO patients (tenant_id, name, phone, dob, gender, guardian_id) VALUES (?, 'Anika Pillai', ?, ?, 'female', ?) RETURNING id",
                UUID.class, c.t().id(), ownPhone == null ? c.guardianPhone() : ownPhone, java.sql.Date.valueOf(dob), c.guardian()));
        inTenantTx(c.t().id(), () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, guardian_patient_id) VALUES (?,?,'guardian_access',?)", c.t().id(), id, c.guardian()));
        return id;
    }

    private List<Map<String, Object>> invites(Clinic c, UUID patient) {
        return inTenantTx(c.t().id(), () -> jdbc.queryForList("SELECT id, recipient_patient_id FROM notification_requests WHERE purpose = 'profile_claim_invite' AND subject_id = ? ORDER BY created_at", patient));
    }

    /** The token from the WhatsApp link, read through the claim check the sender uses. */
    private String token(Clinic c, UUID patient) {
        UUID req = (UUID) invites(c, patient).get(0).get("id");
        List<String> params = notifications.claim(req).params();
        assertThat(params).hasSize(2);
        assertThat(params.get(0)).isEqualTo("Anika"); // names the dependant, first name only
        return params.get(1).substring(params.get(1).lastIndexOf('/') + 1);
    }

    private ResponseEntity<Map> pub(String path, HttpMethod m, Object body) {
        return http.exchange(url(path), m, body == null ? new HttpEntity<>(new HttpHeaders()) : jsonBody(body), Map.class);
    }

    /** Asks for the OTP and swaps its hash for a known code (the real code only goes to the adult's phone). */
    private ResponseEntity<Map> otp(String path, HttpEntity<?> entity, UUID patient, String code) {
        ResponseEntity<Map> r = path.startsWith("/v1/me") ? pub(path, HttpMethod.POST, null) : exchange(path, HttpMethod.POST, entity, Map.class);
        if (r.getStatusCode() == HttpStatus.ACCEPTED) {
            Map<String, Object> row = su().queryForMap("SELECT token_hash, otp_challenge_id FROM profile_claims WHERE patient_id = ? AND outcome IS NULL", patient);
            su().update("UPDATE profile_claims SET otp_hash = ? WHERE token_hash = ?", OpaqueTokens.sha256Hex(row.get("otp_challenge_id") + ":" + code), row.get("token_hash"));
        }
        return r;
    }

    private long events(Clinic c, String type) {
        return inTenantTx(c.t().id(), () -> jdbc.queryForObject("SELECT count(*) FROM outbox_cloudevents WHERE tenant_id = ? AND envelope->>'type' = ?", Long.class, c.t().id(), type));
    }

    @Test
    void ac1_fourteenDaysBefore_theGuardianAndTheOwnNumberAreInvited_once() {
        Clinic c = clinic();
        UUID due = minor(c, 14, phone());
        UUID sharedPhone = minor(c, 10, null);
        UUID later = minor(c, 15, phone());
        job.runOnce();
        assertThat(invites(c, due)).extracting(r -> r.get("recipient_patient_id")).containsExactlyInAnyOrder(c.guardian(), due);
        assertThat(invites(c, sharedPhone)).extracting(r -> r.get("recipient_patient_id")).containsExactly(c.guardian()); // no own number: guardian only
        assertThat(invites(c, later)).isEmpty();
        assertThat(events(c, "patient.guardian.handover_due.v1")).isEqualTo(2);
        String token = token(c, due);
        assertThat(su().queryForObject("SELECT token_hash FROM profile_claims WHERE patient_id = ?", String.class, due)).isEqualTo(OpaqueTokens.sha256Hex(token)).isNotEqualTo(token);

        job.runOnce(); // idempotent per patient and birthday
        assertThat(invites(c, due)).hasSize(2);
        assertThat(events(c, "patient.guardian.handover_due.v1")).isEqualTo(2);
        ResponseEntity<Map> view = pub("/v1/me/profile-claims/" + token, HttpMethod.GET, null);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(view.getBody()).containsEntry("firstName", "Anika").containsEntry("ownPhone", true).containsEntry("opensOn", today().plusDays(14).toString());
    }

    @Test
    void ac2_fromTheBirthday_anOtpClaimClearsTheGuardian_withdrawsConsent_andKeepsTheChosenProxyScope() {
        Clinic c = clinic();
        UUID p = minor(c, 14, phone());
        job.runOnce();
        String token = token(c, p);
        String base = "/v1/me/profile-claims/" + token;

        clock.setLocal(today().plusDays(13) + "T23:59:00", ZONE.getId()); // the evening before the birthday, clinic time
        ResponseEntity<Map> early = pub(base + "/otp", HttpMethod.POST, null);
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(early.getBody()).containsEntry("code", "claim-not-open");

        clock.setLocal(today().plusDays(1) + "T00:01:00", ZONE.getId()); // just past midnight: 18 today
        assertThat(otp(base + "/otp", null, p, "424242").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<Map> wrong = pub(base, HttpMethod.POST, Map.of("otp", "000000", "keepGuardianScope", "BOOK_ONLY"));
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> ok = pub(base, HttpMethod.POST, Map.of("otp", "424242", "keepGuardianScope", "BOOK_ONLY"));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("claimed", true);

        assertThat(su().queryForObject("SELECT guardian_id FROM patients WHERE id = ?", UUID.class, p)).isNull();
        assertThat(su().queryForObject("SELECT count(*) FROM consents WHERE patient_id = ? AND consent_type = 'guardian_access' AND withdrawn_at IS NOT NULL", Integer.class, p)).isEqualTo(1);
        assertThat(su().queryForMap("SELECT status, scope, relation, evidence->>'source' AS src FROM patient_proxies WHERE subject_patient_id = ? AND proxy_patient_id = ?", p, c.guardian()))
                .containsEntry("status", "active").containsEntry("scope", "BOOK_ONLY").containsEntry("relation", "guardian").containsEntry("src", "profile_claim");
        assertThat(su().queryForObject("SELECT outcome || '/' || kept_scope FROM profile_claims WHERE patient_id = ?", String.class, p)).isEqualTo("claimed/BOOK_ONLY");
        assertThat(events(c, "patient.guardian.handover_completed.v1")).isEqualTo(1);

        // single use: a replay with the same choice is the same answer, a different one is refused, nothing changes
        assertThat(pub(base, HttpMethod.POST, Map.of("otp", "424242", "keepGuardianScope", "BOOK_ONLY")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pub(base, HttpMethod.POST, Map.of("otp", "424242", "keepGuardianScope", "BOOK_AND_RECORDS")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(pub(base + "/otp", HttpMethod.POST, null).getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(events(c, "patient.guardian.handover_completed.v1")).isEqualTo(1);
        assertThat(pub("/v1/me/profile-claims/not-a-real-token-123456", HttpMethod.GET, null).getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void ac2_withoutAnOwnNumberTheLinkSendsTheAdultToTheDesk() {
        Clinic c = clinic();
        UUID p = minor(c, 3, null);
        job.runOnce();
        clock.setLocal(today().plusDays(3) + "T09:00:00", ZONE.getId());
        ResponseEntity<Map> r = pub("/v1/me/profile-claims/" + token(c, p) + "/otp", HttpMethod.POST, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody()).containsEntry("code", "own-phone-required");
    }

    @Test
    void ac3_thirtyDaysWithNoClaim_guardianAccessEnds_andTheGuardianSeesAccessEndedAt18() {
        Clinic c = clinic();
        UUID p = minor(c, 14, phone());
        job.runOnce();
        String token = token(c, p);
        clock.setLocal(today().plusDays(14 + 29) + "T23:00:00", ZONE.getId());
        job.runOnce();
        assertThat(su().queryForObject("SELECT guardian_id FROM patients WHERE id = ?", UUID.class, p)).isEqualTo(c.guardian()); // day 29: still on file
        clock.setLocal(today().plusDays(1) + "T00:05:00", ZONE.getId()); // day 30 in the clinic's zone
        job.runOnce();
        job.runOnce();
        assertThat(su().queryForObject("SELECT guardian_id FROM patients WHERE id = ?", UUID.class, p)).isNull();
        assertThat(su().queryForObject("SELECT outcome FROM profile_claims WHERE patient_id = ?", String.class, p)).isEqualTo("expired");
        assertThat(su().queryForObject("SELECT count(*) FROM consents WHERE patient_id = ? AND consent_type = 'guardian_access' AND withdrawn_at IS NOT NULL", Integer.class, p)).isEqualTo(1);
        assertThat(events(c, "patient.guardian.handover_completed.v1")).isEqualTo(1);
        assertThat(pub("/v1/me/profile-claims/" + token, HttpMethod.POST, Map.of("otp", "123456", "keepGuardianScope", "NONE")).getStatusCode()).isEqualTo(HttpStatus.GONE);

        clock.reset();
        String edge = InternalTestTokens.mint(encoder, "patient-access-edge", c.t().id());
        String base = "/internal/v1/tenants/" + c.t().id() + "/family/members";
        List<Map<String, Object>> members = (List<Map<String, Object>>) exchange(base + "?caregiverRef=" + c.guardianRef(), HttpMethod.GET, authed(edge), Map.class).getBody().get("members");
        assertThat(members).singleElement().satisfies(m -> assertThat(m).containsEntry("status", "ended_at_18").containsEntry("firstName", "Anika"));
        String memberRef = (String) members.get(0).get("memberRef");
        ResponseEntity<Map> access = exchange(base + "/" + memberRef + "/access?caregiverRef=" + c.guardianRef() + "&need=records", HttpMethod.GET, authed(edge), Map.class);
        assertThat(access.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(access.getBody()).containsEntry("code", "access-ended-at-18");
    }

    @Test
    void ac4_theWorklistShowsDueAndOverdue_andReceptionClaimsAtTheDeskWithAnOtp() {
        Clinic c = clinic();
        UUID dueSoon = minor(c, 5, phone());
        UUID overdue = minor(c, -40, phone()); // turned 18 before release: never invited, still on the list
        job.runOnce();
        SeededStaff desk = seedStaff(c.t(), seedFullAccessRole(c.t().id()), "idc012-" + SEQ.incrementAndGet() + "@a.com", phone(), false);
        String token = loginAndGetAccessToken(desk);

        List<Map<String, Object>> due = exchange("/v1/patients/guardian-reviews-due?status=due", HttpMethod.GET, authed(token), List.class).getBody();
        assertThat(due).singleElement().satisfies(r -> assertThat(r).containsEntry("patientId", dueSoon.toString()).containsEntry("status", "due")
                .containsEntry("handoverDate", today().plusDays(5).toString()).containsKey("claimSentAt"));
        List<Map<String, Object>> late = exchange("/v1/patients/guardian-reviews-due?status=overdue", HttpMethod.GET, authed(token), List.class).getBody();
        assertThat(late).extracting(r -> r.get("patientId")).containsExactly(overdue.toString());
        assertThat(exchange("/v1/patients/guardian-reviews-due", HttpMethod.GET, authed(token), List.class).getBody()).hasSize(1); // unchanged default
        assertThat(exchange("/v1/patients/guardian-reviews-due?status=soon", HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> notYet = exchange("/v1/patients/" + dueSoon + "/profile-claim/otp", HttpMethod.POST, authed(token), Map.class);
        assertThat(notYet.getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // still a minor

        assertThat(otp("/v1/patients/" + overdue + "/profile-claim/otp", authed(token), overdue, "575757").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<Map> wrong = exchange("/v1/patients/" + overdue + "/profile-claim", HttpMethod.POST, authedJsonBody(token, Map.of("otp", "111111", "keepGuardianScope", "NONE")), Map.class);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map> ok = exchange("/v1/patients/" + overdue + "/profile-claim", HttpMethod.POST, authedJsonBody(token, Map.of("otp", "575757", "keepGuardianScope", "NONE")), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(su().queryForObject("SELECT outcome FROM profile_claims WHERE patient_id = ?", String.class, overdue)).isEqualTo("claimed_at_desk");
        assertThat(su().queryForObject("SELECT guardian_id FROM patients WHERE id = ?", UUID.class, overdue)).isNull();
        assertThat(su().queryForObject("SELECT count(*) FROM patient_proxies WHERE subject_patient_id = ?", Integer.class, overdue)).isZero();
        assertThat(su().queryForObject("SELECT actor_type FROM audit_log WHERE entity_id = ? AND action = 'patient.profile_claimed'", String.class, overdue)).isEqualTo("staff");
        assertThat(exchange("/v1/patients/guardian-reviews-due?status=overdue", HttpMethod.GET, authed(token), List.class).getBody()).isEmpty();

        // a role with patients:view only sees the list but cannot claim
        UUID viewRole = seedRole(c.t().id(), "Front desk viewer", false, new ModuleGrant("patients", true, false, false, false, false, false, false));
        String viewer = loginAndGetAccessToken(seedStaff(c.t(), viewRole, "idc012v-" + SEQ.incrementAndGet() + "@a.com", phone(), false));
        assertThat(exchange("/v1/patients/guardian-reviews-due?status=due", HttpMethod.GET, authed(viewer), List.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/patients/" + dueSoon + "/profile-claim/otp", HttpMethod.POST, authed(viewer), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    static class FrozenClock {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}

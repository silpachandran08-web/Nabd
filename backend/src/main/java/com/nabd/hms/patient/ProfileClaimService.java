package com.nabd.hms.patient;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.patient.api.ConsentRegistry;
import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * IDC-012 (CGV-03/NB-082): the age-18 handover. The daily job invites a minor with a guardian on file 14 days before the 18th
 * birthday (clinic date) by WhatsApp to the guardian and, if the patient has a number of their own, to the patient; the link is
 * single use and only its hash is stored. From the birthday the young adult claims with an OTP to their own number (or at the desk
 * with the same OTP): guardian_id is cleared, the guardian_access consent is withdrawn and the adult may keep the guardian as an
 * IDC-009 proxy (BOOK_ONLY or BOOK_AND_RECORDS). 30 days after the birthday with no claim, the job ends guardian access. No name,
 * phone or token is logged; audit rows carry ids, the outcome and the scope.
 */
@Service
@Transactional
public class ProfileClaimService {

    static final int INVITE_DAYS_BEFORE = 14;
    static final int CLAIM_DAYS_AFTER = 30;
    static final Duration MIN_CLAIM_WINDOW = Duration.ofDays(7);
    static final Duration OTP_TTL = Duration.ofMinutes(10);
    static final Duration OTP_GAP = Duration.ofSeconds(30);
    static final int OTP_MAX_ATTEMPTS = 5;
    static final Set<String> KEEP_SCOPES = Set.of("NONE", "BOOK_ONLY", "BOOK_AND_RECORDS");
    private static final String GUARDIAN_ACCESS = "guardian_access";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Run(int invited, int ended) {
    }

    public record View(String clinicName, String firstName, String guardianFirstName, LocalDate opensOn, Instant expiresAt, boolean ownPhone) {
    }

    public record Otp(String phone, String code) {
    }

    public enum Outcome { CLAIMED, EXPIRED, NOT_OPEN, OTP_INVALID, CONFLICT }

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final PatientRefs refs;
    private final ConsentRegistry consents;
    private final NotificationPort notifications;
    private final AuditService audit;
    private final EventPublisher events;
    private final ActorDirectory actors;
    private final ObjectMapper json;
    private final String patientWebBase;

    ProfileClaimService(JdbcTemplate jdbc, TenantContext tenantContext, ClinicClock clock, PatientRefs refs, ConsentRegistry consents,
                        NotificationPort notifications, AuditService audit, EventPublisher events, ActorDirectory actors, ObjectMapper json,
                        @Value("${app.patient-web-base-url:http://localhost:3810}") String patientWebBase) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.refs = refs;
        this.consents = consents;
        this.notifications = notifications;
        this.audit = audit;
        this.events = events;
        this.actors = actors;
        this.json = json;
        this.patientWebBase = patientWebBase.replaceAll("/+$", "");
    }

    // ---- the daily job (one clinic, in its tenant scope) ----

    /** Idempotent: one claim per patient and 18th birthday (unique index), and an ended claim is never ended twice. */
    public Run runDaily() {
        UUID tenant = tenantContext.current();
        LocalDate today = clock.today(tenant);
        int invited = 0;
        // Birthdays up to 30 days ago are still invited (first run after release); older ones stay on the staff worklist.
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT p.id, p.guardian_id, (p.dob + interval '18 years')::date AS handover, split_part(p.name, ' ', 1) AS first_name,
                       p.phone_digits, g.phone_digits AS guardian_digits
                FROM patients p JOIN patients g ON g.id = p.guardian_id
                WHERE p.status = 'active' AND p.guardian_review_kept_at IS NULL
                  AND (p.dob + interval '18 years')::date BETWEEN ?::date - ? AND ?::date + ?
                  AND NOT EXISTS (SELECT 1 FROM profile_claims c WHERE c.patient_id = p.id AND c.handover_date = (p.dob + interval '18 years')::date)
                ORDER BY p.id""", java.sql.Date.valueOf(today), CLAIM_DAYS_AFTER, java.sql.Date.valueOf(today), INVITE_DAYS_BEFORE)) {
            invite(tenant, r);
            invited++;
        }
        int ended = 0;
        for (Map<String, Object> c : jdbc.queryForList("""
                SELECT c.token_hash, c.patient_id, c.guardian_patient_id, p.guardian_id, p.guardian_review_kept_at
                FROM profile_claims c JOIN patients p ON p.id = c.patient_id
                WHERE c.outcome IS NULL AND c.expires_at <= ? FOR UPDATE OF c""", Timestamp.from(clock.now()))) {
            UUID patient = (UUID) c.get("patient_id");
            if (c.get("guardian_review_kept_at") != null) { // staff recorded "keep proxy" meanwhile: their decision stands
                jdbc.update("UPDATE profile_claims SET outcome = 'superseded', otp_hash = NULL WHERE token_hash = ?", c.get("token_hash"));
                continue;
            }
            endGuardianAccess(tenant, patient, (UUID) c.get("guardian_patient_id"));
            jdbc.update("UPDATE profile_claims SET outcome = 'expired', otp_hash = NULL WHERE token_hash = ?", c.get("token_hash"));
            audit.record(tenant, "system", null, "profile claim job", "system", null, "patient.guardian_access_ended_at_18", "patient", patient,
                    Map.of("guardianId", String.valueOf(c.get("guardian_patient_id"))), Map.of("outcome", "expired"));
            completed(tenant, patient, "expired", "NONE");
            ended++;
        }
        return new Run(invited, ended);
    }

    private void invite(UUID tenant, Map<String, Object> r) {
        UUID patient = (UUID) r.get("id");
        UUID guardian = (UUID) r.get("guardian_id");
        LocalDate handover = ((java.sql.Date) r.get("handover")).toLocalDate();
        Instant expires = ClinicClock.startOf(handover.plusDays(CLAIM_DAYS_AFTER), clock.zone(tenant));
        Instant floor = clock.now().plus(MIN_CLAIM_WINDOW);
        String token = OpaqueTokens.generate();
        String hash = OpaqueTokens.sha256Hex(token);
        jdbc.update("INSERT INTO profile_claims (token_hash, tenant_id, patient_id, guardian_patient_id, handover_date, sent_at, expires_at) VALUES (?,?,?,?,?,?,?)",
                hash, tenant, patient, guardian, java.sql.Date.valueOf(handover), Timestamp.from(clock.now()), Timestamp.from(expires.isBefore(floor) ? floor : expires));
        List<String> params = List.of((String) r.get("first_name"), patientWebBase + "/profile-claim/" + token);
        String key = "profile-claim:" + patient + ":" + handover;
        notifications.request(new NotificationRequest(NotificationPurpose.PROFILE_CLAIM_INVITE, "patient", patient, guardian, null, null, params, null, null, null, key + ":guardian"));
        Object own = r.get("phone_digits");
        if (own != null && !own.equals(r.get("guardian_digits"))) {
            notifications.request(new NotificationRequest(NotificationPurpose.PROFILE_CLAIM_INVITE, "patient", patient, patient, null, null, params, null, null, null, key + ":own"));
        }
        audit.record(tenant, "system", null, "profile claim job", "system", null, "patient.profile_claim_invited", "patient", patient, null,
                Map.of("guardianId", guardian.toString(), "ownNumber", own != null && !own.equals(r.get("guardian_digits"))));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("patientRef", refs.refFor(patient));
        data.put("guardianRef", refs.refFor(guardian));
        data.put("handoverDate", handover.toString());
        events.publish(tenant, DomainEventType.PATIENT_GUARDIAN_HANDOVER_DUE, patient, data);
    }

    // ---- the young adult's link (public, no session) ----

    public View view(String tokenHash) {
        Map<String, Object> c = liveClaim("c.token_hash = ?", tokenHash, false);
        return new View(jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenantContext.current()), (String) c.get("first_name"),
                (String) c.get("guardian_first_name"), ((java.sql.Date) c.get("handover_date")).toLocalDate(), ((Timestamp) c.get("expires_at")).toInstant(),
                ownPhone(c));
    }

    /** Stores a salted hash of a fresh code and returns it with the patient's own number; the caller sends it after the commit. */
    public Otp issueOtp(String tokenHash) {
        return otpFor(liveClaim("c.token_hash = ?", tokenHash, true));
    }

    public Outcome claim(String tokenHash, String otp, String keep, String ip, String userAgent) {
        Map<String, Object> c = lockedClaim("c.token_hash = ?", tokenHash);
        return c == null ? Outcome.EXPIRED : apply(c, otp, keep, ip, userAgent, null);
    }

    // ---- the desk ("claimed at desk with OTP"); the caller holds patients:edit ----

    /** Opens a claim on demand for an adult who still has a guardian on file (an overdue row may have none). */
    public Otp deskOtp(UUID patientId) {
        Map<String, Object> c = lockedClaim("c.patient_id = ? AND c.outcome IS NULL", patientId);
        if (c == null) {
            UUID tenant = tenantContext.current();
            Map<String, Object> p = jdbc.queryForList("SELECT guardian_id, (dob + interval '18 years')::date AS handover FROM patients WHERE id = ? AND status = 'active'", patientId)
                    .stream().findFirst().orElseThrow(ApiException::notFound);
            if (p.get("guardian_id") == null) {
                throw new ApiException(HttpStatus.CONFLICT, "no-handover", "No handover due", "This patient has no guardian on file to hand over from.", Map.of("code", "no-handover"));
            }
            String hash = OpaqueTokens.sha256Hex(OpaqueTokens.generate()); // desk-only: the token is never sent
            jdbc.update("""
                    INSERT INTO profile_claims (token_hash, tenant_id, patient_id, guardian_patient_id, handover_date, sent_at, expires_at) VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT (tenant_id, patient_id, handover_date) DO UPDATE SET token_hash = EXCLUDED.token_hash, guardian_patient_id = EXCLUDED.guardian_patient_id,
                      sent_at = EXCLUDED.sent_at, expires_at = EXCLUDED.expires_at, outcome = NULL, claimed_at = NULL, kept_scope = NULL, otp_hash = NULL,
                      otp_expires_at = NULL, otp_attempts = 0, otp_challenge_id = NULL""",
                    hash, tenant, patientId, p.get("guardian_id"), p.get("handover"), Timestamp.from(clock.now()), Timestamp.from(clock.now().plus(Duration.ofDays(1))));
            c = lockedClaim("c.patient_id = ? AND c.outcome IS NULL", patientId);
        }
        requireLive(c);
        return otpFor(c);
    }

    public Outcome claimAtDesk(UUID patientId, UUID staffId, String otp, String keep, String ip) {
        Map<String, Object> c = lockedClaim("c.patient_id = ? AND c.outcome IS NULL", patientId);
        if (c == null) {
            throw ApiException.notFound();
        }
        return apply(c, otp, keep, ip, null, staffId);
    }

    // ---- shared ----

    private Outcome apply(Map<String, Object> c, String otp, String keep, String ip, String userAgent, UUID staffId) {
        UUID tenant = tenantContext.current();
        if (!KEEP_SCOPES.contains(keep)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "keepGuardianScope must be NONE, BOOK_ONLY or BOOK_AND_RECORDS.");
        }
        String outcome = (String) c.get("outcome");
        if (outcome != null) { // replay of a claimed link with the same choice: same answer, nothing changes
            return outcome.startsWith("claimed") ? (keep.equals(c.get("kept_scope")) ? Outcome.CLAIMED : Outcome.CONFLICT) : Outcome.EXPIRED;
        }
        Instant now = clock.now();
        if (!((Timestamp) c.get("expires_at")).toInstant().isAfter(now)) {
            return Outcome.EXPIRED;
        }
        if (clock.today(tenant).isBefore(((java.sql.Date) c.get("handover_date")).toLocalDate())) {
            return Outcome.NOT_OPEN;
        }
        UUID challenge = (UUID) c.get("otp_challenge_id");
        int attempts = (Integer) c.get("otp_attempts");
        Timestamp otpExp = (Timestamp) c.get("otp_expires_at");
        boolean live = challenge != null && otpExp != null && otpExp.toInstant().isAfter(now) && attempts < OTP_MAX_ATTEMPTS;
        if (!live || otp == null || !MessageDigest.isEqual(hashOtp(challenge, otp).getBytes(StandardCharsets.UTF_8), String.valueOf(c.get("otp_hash")).getBytes(StandardCharsets.UTF_8))) {
            if (live) {
                jdbc.update("UPDATE profile_claims SET otp_attempts = otp_attempts + 1 WHERE token_hash = ?", c.get("token_hash"));
            }
            return Outcome.OTP_INVALID;
        }
        UUID patient = (UUID) c.get("patient_id");
        UUID guardian = (UUID) c.get("guardian_patient_id");
        endGuardianAccess(tenant, patient, guardian);
        if (!"NONE".equals(keep)) {
            keepAsProxy(tenant, patient, guardian, keep, challenge, ip, userAgent);
        }
        String result = staffId == null ? "claimed" : "claimed_at_desk";
        jdbc.update("UPDATE profile_claims SET outcome = ?, claimed_at = ?, kept_scope = ?, otp_hash = NULL WHERE token_hash = ?",
                result, Timestamp.from(now), keep, c.get("token_hash"));
        Map<String, Object> after = Map.of("outcome", result, "keptScope", keep, "otpChallengeId", challenge.toString());
        if (staffId == null) {
            audit.record(tenant, "system", null, "patient", "patient", ip, "patient.profile_claimed", "patient", patient, null, after);
        } else {
            ActorDirectory.Actor actor = actors.findActor(tenant, staffId).orElseThrow(ApiException::notFound);
            audit.record(tenant, "staff", staffId, actor.name(), actor.role(), ip, "patient.profile_claimed", "patient", patient, null, after);
        }
        completed(tenant, patient, result, keep);
        return Outcome.CLAIMED;
    }

    /** Clears guardian_id only if it still names this guardian, and withdraws guardian_access (same rows and event as a staff revoke). */
    private void endGuardianAccess(UUID tenant, UUID patient, UUID guardian) {
        if (jdbc.update("UPDATE patients SET guardian_id = NULL WHERE id = ? AND guardian_id = ?", patient, guardian) == 1) {
            jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, withdrawn_at, channel) VALUES (?,?,?,now(),NULL)", tenant, patient, GUARDIAN_ACCESS);
            events.publish(tenant, DomainEventType.CONSENT_CHANGED, patient, Map.of("patientId", patient, "consentType", GUARDIAN_ACCESS, "status", "withdrawn"));
        }
    }

    /** The adult's OTP is the consent: an active IDC-009 proxy with evidence, as if they had accepted an invite. */
    private void keepAsProxy(UUID tenant, UUID patient, UUID guardian, String scope, UUID challenge, String ip, String userAgent) {
        String notice = consents.currentNotice("en").map(ConsentRegistry.NoticeRef::version).orElse(null);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("source", "profile_claim");
        evidence.put("otpChallengeId", challenge.toString());
        evidence.put("ip", ip);
        evidence.put("userAgent", userAgent == null ? null : userAgent.substring(0, Math.min(200, userAgent.length())));
        evidence.put("noticeVersion", notice);
        UUID id = jdbc.queryForObject("""
                INSERT INTO patient_proxies (id, tenant_id, proxy_patient_id, subject_patient_id, relation, scope, status, accepted_at, evidence, notice_version)
                VALUES (?,?,?,?,'guardian',?,'active',now(),?::jsonb,?)
                ON CONFLICT (tenant_id, proxy_patient_id, subject_patient_id) WHERE status <> 'revoked'
                DO UPDATE SET scope = EXCLUDED.scope, status = 'active', accepted_at = now(), evidence = EXCLUDED.evidence, notice_version = EXCLUDED.notice_version
                RETURNING id""", UUID.class, UUID.randomUUID(), tenant, guardian, patient, scope, json.writeValueAsString(evidence), notice);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("proxyRef", refs.refFor(guardian));
        data.put("subjectRef", refs.refFor(patient));
        data.put("scope", scope);
        data.put("status", "active");
        events.publish(tenant, DomainEventType.PATIENT_PROXY_CHANGED, id, data);
    }

    private void completed(UUID tenant, UUID patient, String outcome, String keptScope) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("patientRef", refs.refFor(patient));
        data.put("outcome", outcome);
        data.put("keptScope", keptScope);
        events.publish(tenant, DomainEventType.PATIENT_GUARDIAN_HANDOVER_COMPLETED, patient, data);
    }

    private Otp otpFor(Map<String, Object> c) {
        UUID tenant = tenantContext.current();
        if (clock.today(tenant).isBefore(((java.sql.Date) c.get("handover_date")).toLocalDate())) {
            throw notOpen();
        }
        if (!ownPhone(c)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "own-phone-required", "Own phone required",
                    "The code goes to your own number, and the clinic has only your guardian's. Ask the clinic desk to add your number.", Map.of("code", "own-phone-required"));
        }
        Instant now = clock.now();
        Timestamp last = (Timestamp) c.get("otp_expires_at");
        if (last != null && last.toInstant().minus(OTP_TTL).plus(OTP_GAP).isAfter(now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "otp-too-soon", "Please wait", "A code was sent a moment ago.");
        }
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        UUID challenge = UUID.randomUUID();
        jdbc.update("UPDATE profile_claims SET otp_hash = ?, otp_expires_at = ?, otp_attempts = 0, otp_challenge_id = ? WHERE token_hash = ?",
                hashOtp(challenge, code), Timestamp.from(now.plus(OTP_TTL)), challenge, c.get("token_hash"));
        return new Otp((String) c.get("phone"), code);
    }

    private static boolean ownPhone(Map<String, Object> c) {
        return c.get("phone_digits") != null && !c.get("phone_digits").equals(c.get("guardian_digits"));
    }

    private Map<String, Object> liveClaim(String where, Object arg, boolean forUpdate) {
        Map<String, Object> c = forUpdate ? lockedClaim(where, arg) : select(where, "", arg);
        requireLive(c);
        return c;
    }

    private void requireLive(Map<String, Object> c) {
        if (c == null || c.get("outcome") != null || !((Timestamp) c.get("expires_at")).toInstant().isAfter(clock.now())) {
            throw new ApiException(HttpStatus.GONE, "claim-expired", "Link expired", "This link has expired or was already used. Ask the clinic desk for help.", Map.of("code", "claim-expired"));
        }
    }

    private Map<String, Object> lockedClaim(String where, Object arg) {
        return select(where, " FOR UPDATE OF c", arg);
    }

    private Map<String, Object> select(String where, String lock, Object arg) {
        return jdbc.queryForList("SELECT c.*, split_part(p.name, ' ', 1) AS first_name, p.phone, p.phone_digits, split_part(g.name, ' ', 1) AS guardian_first_name, "
                        + "g.phone_digits AS guardian_digits FROM profile_claims c JOIN patients p ON p.id = c.patient_id JOIN patients g ON g.id = c.guardian_patient_id WHERE "
                        + where + " ORDER BY c.sent_at DESC LIMIT 1" + lock, arg).stream().findFirst().orElse(null);
    }

    private static String hashOtp(UUID challenge, String code) {
        return OpaqueTokens.sha256Hex(challenge + ":" + code);
    }

    static ApiException notOpen() {
        return new ApiException(HttpStatus.CONFLICT, "claim-not-open", "Not yet", "You can claim your profile from your 18th birthday.", Map.of("code", "claim-not-open"));
    }

}

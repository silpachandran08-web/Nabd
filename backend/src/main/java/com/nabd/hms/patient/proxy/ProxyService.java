package com.nabd.hms.patient.proxy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.OpaqueTokens;
import com.nabd.hms.common.PhoneNumbers;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.DataGatePort;
import com.nabd.hms.patient.api.ConsentRegistry;
import com.nabd.hms.patient.api.PatientRefs;
import com.nabd.hms.patient.api.ProxyAccess;
import com.nabd.hms.shared.notify.NotificationOutcome;
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
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * IDC-009: proxy access for adult family members (CGV-02). The caregiver adds the member; the member gets a WhatsApp consent link
 * (seven days, single use, only its hash is stored), proves it is them with an OTP to their own number and picks a scope. Minors keep
 * the guardian model. No name, phone or token is logged; the audit rows carry ids, the relation and the scope.
 */
@Service
@Transactional
class ProxyService implements ProxyAccess {

    static final Duration INVITE_TTL = Duration.ofDays(7);
    static final Duration OTP_TTL = Duration.ofMinutes(10);
    static final Duration OTP_GAP = Duration.ofSeconds(30);
    static final int OTP_MAX_ATTEMPTS = 5;
    static final Set<String> SCOPES = Set.of("BOOK_ONLY", "BOOK_AND_RECORDS", "FULL");
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Added(String memberRef, String proxyStatus) {
    }

    public record Member(String memberRef, String firstName, String relation, String status) {
    }

    public record Access(String relation, String scope, String status) {
    }

    public record InviteView(String clinicName, String relation, String caregiverFirstName, Instant expiresAt) {
    }

    public record Otp(String phone, String code) {
    }

    public enum Outcome { ACCEPTED, EXPIRED, OTP_INVALID, CONFLICT }

    public record Accepted(Outcome outcome, String scope) {
    }

    /** IDC-013: a proxy as the subject (the patient) sees it. */
    public record Granted(UUID id, String caregiverFirstName, String relation, String scope, String status) {
    }

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final PatientRefs refs;
    private final ConsentRegistry consents;
    private final NotificationPort notifications;
    private final AuditService audit;
    private final DataGatePort dataGate;
    private final String patientWebBase;
    private final ObjectMapper json;
    private final EventPublisher events;
    private final ActorDirectory actors;

    ProxyService(JdbcTemplate jdbc, TenantContext tenantContext, ClinicClock clock, PatientRefs refs, ConsentRegistry consents,
                 NotificationPort notifications, AuditService audit, DataGatePort dataGate, EventPublisher events, ActorDirectory actors,
                 ObjectMapper json, @Value("${app.patient-web-base-url:http://localhost:3810}") String patientWebBase) {
        this.json = json;
        this.events = events;
        this.actors = actors;
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.refs = refs;
        this.consents = consents;
        this.notifications = notifications;
        this.audit = audit;
        this.dataGate = dataGate;
        this.patientWebBase = patientWebBase;
    }

    // ---- the caregiver side (Patient Access Edge) ----

    /** Adult: a pending proxy and a WhatsApp consent link to the member's own number. Minor: the guardian model, no link. */
    public Added addMember(String caregiverRef, String name, LocalDate dob, String relation, String phone, String sex, String serviceId) {
        UUID tenant = tenantContext.current();
        dataGate.requireRealData();
        UUID caregiver = adultCaregiver(caregiverRef);
        LocalDate today = clock.today(tenant);
        if (Period.between(dob, today).getYears() < 18) {
            return addMinor(tenant, caregiver, name, dob, sex, relation, serviceId);
        }
        String caregiverPhone = jdbc.queryForObject("SELECT phone_digits FROM patients WHERE id = ?", String.class, caregiver);
        String e164 = PhoneNumbers.require(phone, jdbc.queryForObject("SELECT region_code FROM tenants WHERE id = ?", String.class, tenant), "phone");
        if (e164.replaceAll("\\D", "").equals(caregiverPhone)) {
            throw unprocessable("own-phone-required", "Own phone required", "An adult member must give their own phone number so they can approve access.");
        }
        UUID subject = jdbc.queryForList("SELECT id FROM patients WHERE phone_digits = ? AND dob = ? AND status = 'active' AND similarity(name, ?) > 0.3 LIMIT 1",
                UUID.class, e164.replaceAll("\\D", ""), java.sql.Date.valueOf(dob), name).stream().findFirst().orElse(null);
        if (subject == null) {
            subject = UUID.randomUUID();
            jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender, registration_source) VALUES (?,?,?,?,?,?,'whatsapp')",
                    subject, tenant, name, e164, java.sql.Date.valueOf(dob), sex);
        }
        if (subject.equals(caregiver) || !jdbc.queryForList("SELECT 1 FROM patient_proxies WHERE proxy_patient_id = ? AND subject_patient_id = ? AND status <> 'revoked'", caregiver, subject).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "duplicate-member", "Already a member", "This person is already on your family list.");
        }
        UUID proxy = UUID.randomUUID();
        jdbc.update("INSERT INTO patient_proxies (id, tenant_id, proxy_patient_id, subject_patient_id, relation) VALUES (?,?,?,?,?)", proxy, tenant, caregiver, subject, relation);
        invite(tenant, proxy, subject);
        audit.record(tenant, "system", null, "patient via edge", serviceId, null, "patient.proxy_invited", "patient_proxy", proxy, null,
                Map.of("relation", relation, "status", "pending"));
        return new Added(refs.refFor(subject), "pending");
    }

    /** A new link for a pending proxy; every older link stops working. */
    public void resend(String caregiverRef, String memberRef, String serviceId) {
        UUID tenant = tenantContext.current();
        UUID caregiver = adultCaregiver(caregiverRef);
        UUID subject = refs.patientIdFor(memberRef).orElseThrow(ProxyService::notFound);
        Map<String, Object> p = jdbc.queryForList("SELECT id FROM patient_proxies WHERE proxy_patient_id = ? AND subject_patient_id = ? AND status = 'pending'", caregiver, subject)
                .stream().findFirst().orElseThrow(ProxyService::notFound);
        jdbc.update("UPDATE proxy_invites SET used_at = now() WHERE proxy_id = ? AND used_at IS NULL", p.get("id"));
        invite(tenant, (UUID) p.get("id"), subject);
        audit.record(tenant, "system", null, "patient via edge", serviceId, null, "patient.proxy_invite_resent", "patient_proxy", (UUID) p.get("id"), null, Map.of("status", "pending"));
    }

    public List<Member> members(String caregiverRef) {
        UUID caregiver = adultCaregiver(caregiverRef);
        List<Member> out = new java.util.ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT s.id, split_part(s.name, ' ', 1) AS first_name, p.relation, p.status FROM patient_proxies p "
                + "JOIN patients s ON s.id = p.subject_patient_id WHERE p.proxy_patient_id = ? AND p.status <> 'revoked' ORDER BY p.invited_at", caregiver)) {
            out.add(new Member(refs.refFor((UUID) r.get("id")), (String) r.get("first_name"), (String) r.get("relation"), (String) r.get("status")));
        }
        for (Map<String, Object> r : jdbc.queryForList("SELECT id, split_part(name, ' ', 1) AS first_name FROM patients WHERE guardian_id = ? AND status = 'active' ORDER BY created_at", caregiver)) {
            out.add(new Member(refs.refFor((UUID) r.get("id")), (String) r.get("first_name"), "guardian", "guardian"));
        }
        for (UUID id : endedAt18(caregiver, null)) { // IDC-012: shown for 90 days as "access ended at 18"
            out.add(new Member(refs.refFor(id), jdbc.queryForObject("SELECT split_part(name, ' ', 1) FROM patients WHERE id = ?", String.class, id), "guardian", "ended_at_18"));
        }
        return out;
    }

    /** The gate for a patient surface: book / reschedule / check in need a live relation; records need an accepted scope that includes them. */
    public Access access(String caregiverRef, String memberRef, boolean records) {
        UUID caregiver = refs.patientIdFor(caregiverRef).orElseThrow(ProxyService::notAProxy);
        UUID subject = refs.patientIdFor(memberRef).orElseThrow(ProxyService::notAProxy);
        Acting a = relationFor(caregiver, subject).orElseThrow(() -> endedAt18(caregiver, subject).isEmpty() ? notAProxy()
                : new ApiException(HttpStatus.FORBIDDEN, "access-ended-at-18", "Access ended at 18", "Access ended when they turned 18. They can give you access again from their own phone.",
                        Map.of("code", "access-ended-at-18")));
        if (records) {
            requireRecords(caregiver, subject);
        }
        return new Access(a.relation(), a.scope(), a.status());
    }

    /** IDC-012: members whose age-18 handover ended this caregiver's access (claimed keeping nothing, or 30 days with no claim) and who
     * have not given a proxy since; subject null = all of them. */
    private List<UUID> endedAt18(UUID caregiver, UUID subject) {
        return jdbc.queryForList("SELECT DISTINCT c.patient_id FROM profile_claims c WHERE c.guardian_patient_id = ? AND (?::uuid IS NULL OR c.patient_id = ?) "
                        + "AND (c.outcome = 'expired' OR c.kept_scope = 'NONE') AND coalesce(c.claimed_at, c.expires_at) > now() - interval '90 days' "
                        + "AND NOT EXISTS (SELECT 1 FROM patients p WHERE p.id = c.patient_id AND p.guardian_id = c.guardian_patient_id) "
                        + "AND NOT EXISTS (SELECT 1 FROM patient_proxies x WHERE x.proxy_patient_id = c.guardian_patient_id AND x.subject_patient_id = c.patient_id AND x.status <> 'revoked')",
                UUID.class, caregiver, subject, subject);
    }

    // ---- the subject side (IDC-013): the patient downgrades or removes a caregiver ----

    /** The people who may act for this patient (pending or active), oldest first. */
    public List<Granted> granted(String subjectRef) {
        UUID subject = refs.patientIdFor(subjectRef).orElseThrow(ProxyService::notFound);
        return jdbc.query("SELECT p.id, split_part(c.name, ' ', 1) AS first_name, p.relation, p.scope, p.status FROM patient_proxies p "
                        + "JOIN patients c ON c.id = p.proxy_patient_id WHERE p.subject_patient_id = ? AND p.status <> 'revoked' ORDER BY p.invited_at",
                (rs, i) -> new Granted(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4) == null ? "BOOK_ONLY" : rs.getString(4), rs.getString(5)), subject);
    }

    /** Scope change or revoke by the patient (patient session / Edge on the verified phone). Idempotent: a revoked proxy revoked again stays revoked. 404 for someone else's proxy. */
    public Access change(String subjectRef, UUID proxyId, String scope, boolean revoke, String serviceId) {
        UUID subject = refs.patientIdFor(subjectRef).orElseThrow(ProxyService::notFound);
        Map<String, Object> p = lockedProxy(subject, proxyId);
        if ("revoked".equals(p.get("status"))) {
            if (revoke) {
                return new Access((String) p.get("relation"), (String) p.get("scope"), "revoked");
            }
            throw notFound();
        }
        if (revoke) {
            return revoke(p, "patient", "system", null, "patient", serviceId, null, Map.of());
        }
        if (!SCOPES.contains(scope)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", "scope must be BOOK_ONLY, BOOK_AND_RECORDS or FULL.");
        }
        jdbc.update("UPDATE patient_proxies SET scope = ? WHERE id = ?", scope, proxyId);
        audit.record(tenantContext.current(), "system", null, "patient", serviceId, null, "patient.proxy_scope_changed", "patient_proxy", proxyId,
                Map.of("scope", String.valueOf(p.get("scope"))), Map.of("scope", scope));
        publishChanged(p, scope, (String) p.get("status"));
        return new Access((String) p.get("relation"), scope, (String) p.get("status"));
    }

    /** The clinic revokes on the patient's verified request: the controller has checked that the step-up names this proxy; its jti is
     * stored here (unique), so the same token cannot revoke anything else. The reason goes to the audit row. Idempotent. */
    public void clinicRevoke(UUID patientId, UUID proxyId, UUID staffId, UUID stepUpId, String reason, String ip) {
        Map<String, Object> p = lockedProxy(patientId, proxyId);
        if ("revoked".equals(p.get("status"))) {
            return;
        }
        if (jdbc.update("UPDATE patient_proxies SET revoke_step_up_id = ? WHERE id = ? AND NOT EXISTS (SELECT 1 FROM patient_proxies WHERE revoke_step_up_id = ?)", stepUpId, proxyId, stepUpId) == 0) {
            throw new ApiException(HttpStatus.FORBIDDEN, "step-up-required", "Step-up verification required", "This confirmation was already used; confirm again.", Map.of("code", "STEP_UP_REQUIRED"));
        }
        ActorDirectory.Actor actor = actors.findActor(tenantContext.current(), staffId).orElseThrow(ProxyService::notFound);
        revoke(p, "clinic", "staff", staffId, actor.name(), actor.role(), ip, Map.of("reason", reason, "stepUpId", stepUpId.toString()));
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    Map<String, Object> lockedProxy(UUID subject, UUID proxyId) {
        return jdbc.queryForList("SELECT id, proxy_patient_id, subject_patient_id, relation, scope, status FROM patient_proxies WHERE id = ? AND subject_patient_id = ? FOR UPDATE",
                proxyId, subject).stream().findFirst().orElseThrow(ProxyService::notFound);
    }

    private Access revoke(Map<String, Object> p, String revokedBy, String actorType, UUID actorId, String actorName, String actorRole, String ip, Map<String, String> extra) {
        UUID id = (UUID) p.get("id");
        jdbc.update("UPDATE patient_proxies SET status = 'revoked', revoked_at = now(), revoked_by = ? WHERE id = ?", revokedBy, id);
        jdbc.update("UPDATE proxy_invites SET used_at = now(), otp_hash = NULL WHERE proxy_id = ? AND used_at IS NULL", id);
        Map<String, String> after = new java.util.LinkedHashMap<>(extra);
        after.put("status", "revoked");
        after.put("revokedBy", revokedBy);
        audit.record(tenantContext.current(), actorType, actorId, actorName, actorRole, ip, "patient.proxy_revoked", "patient_proxy", id,
                Map.of("status", String.valueOf(p.get("status")), "scope", String.valueOf(p.get("scope"))), after);
        publishChanged(p, (String) p.get("scope"), "revoked");
        return new Access((String) p.get("relation"), (String) p.get("scope"), "revoked");
    }

    /** patient.proxy.changed.v1: Comms drops the proxy from any dual-delivery list, the Edge and realtime refresh the caregiver's picker. Refs only. */
    private void publishChanged(Map<String, Object> p, String scope, String status) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("proxyRef", refs.refFor((UUID) p.get("proxy_patient_id")));
        data.put("subjectRef", refs.refFor((UUID) p.get("subject_patient_id")));
        data.put("scope", scope == null ? "BOOK_ONLY" : scope);
        data.put("status", status);
        events.publish(tenantContext.current(), DomainEventType.PATIENT_PROXY_CHANGED, (UUID) p.get("id"), data);
    }

    @Override
    public ActedBy resolve(String actorRef, UUID subject) {
        UUID actor = refs.patientIdFor(actorRef).orElseThrow(ProxyService::notAProxy);
        Acting a = relationFor(actor, subject).orElseThrow(ProxyService::notAProxy);
        return new ActedBy(actor, a.relation(), actorRef);
    }

    @Override
    public ActedBy onBehalf(UUID actor, String relation, UUID subject) {
        if (actor == null || actor.equals(subject) || relation == null || !relation.matches("^[a-z_]{2,20}$")
                || jdbc.queryForList("SELECT 1 FROM patients WHERE id = ? AND status = 'active'", actor).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "acted-by-invalid", "Invalid acted-by", "actedByPatientId must be another patient of this clinic and actedByRelation a lower-case word.");
        }
        return new ActedBy(actor, relation, refs.refFor(actor));
    }

    @Override
    public void audit(ActedBy by, String on, String entityType, UUID entityId) {
        audit.record(tenantContext.current(), "system", null, "family member", "proxy", null, "proxy.acted", entityType, entityId, null,
                Map.of("on", on, "actedByPatientId", by.patientId().toString(), "actedByRelation", by.relation()));
    }

    @Override
    public Optional<Acting> relationFor(UUID actor, UUID subject) {
        if (!jdbc.queryForList("SELECT 1 FROM patients WHERE id = ? AND guardian_id = ?", subject, actor).isEmpty()) {
            return Optional.of(new Acting("guardian", "FULL", "active"));
        }
        return jdbc.query("SELECT relation, scope, status FROM patient_proxies WHERE proxy_patient_id = ? AND subject_patient_id = ? AND status <> 'revoked'",
                (rs, i) -> new Acting(rs.getString(1), rs.getString(2) == null ? "BOOK_ONLY" : rs.getString(2), rs.getString(3)), actor, subject).stream().findFirst();
    }

    @Override
    public void requireRecords(UUID actor, UUID subject) {
        Acting a = relationFor(actor, subject).orElseThrow(ProxyService::notAProxy);
        if ("pending".equals(a.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "proxy-consent-pending", "Consent pending",
                    "The family member has not approved access yet; you can book for them, but records stay hidden until they do.", Map.of("code", "proxy-consent-pending"));
        }
        if ("BOOK_ONLY".equals(a.scope())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "proxy-scope-insufficient", "Not allowed", "The family member allowed booking only.", Map.of("code", "proxy-scope-insufficient"));
        }
    }

    // ---- the member side (public link) ----

    public InviteView view(String tokenHash) {
        Map<String, Object> r = liveInvite(tokenHash, false);
        return new InviteView(jdbc.queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, tenantContext.current()), (String) r.get("relation"),
                (String) r.get("caregiver_first_name"), ((Timestamp) r.get("expires_at")).toInstant());
    }

    /** Stores a salted hash of a fresh code and returns the code and the member's own number for the sender (called after commit by the caller). */
    public Otp issueOtp(String tokenHash) {
        Map<String, Object> r = liveInvite(tokenHash, true);
        Instant now = clock.now();
        Timestamp last = (Timestamp) r.get("otp_expires_at");
        if (last != null && last.toInstant().minus(OTP_TTL).plus(OTP_GAP).isAfter(now)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "otp-too-soon", "Please wait", "A code was sent a moment ago.");
        }
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        UUID challenge = UUID.randomUUID();
        jdbc.update("UPDATE proxy_invites SET otp_hash = ?, otp_expires_at = ?, otp_attempts = 0, otp_challenge_id = ? WHERE token_hash = ?",
                hashOtp(challenge, code), Timestamp.from(now.plus(OTP_TTL)), challenge, tokenHash);
        return new Otp((String) r.get("subject_phone"), code);
    }

    /** Outcome instead of an exception for the failures that must still persist (a wrong code counts an attempt). */
    public Accepted accept(String tokenHash, String otp, String scope, String ip, String userAgent) {
        UUID tenant = tenantContext.current();
        Map<String, Object> r = jdbc.queryForList("SELECT i.*, p.status AS proxy_status, p.scope AS proxy_scope, p.relation, p.subject_patient_id FROM proxy_invites i "
                + "JOIN patient_proxies p ON p.id = i.proxy_id WHERE i.token_hash = ? FOR UPDATE OF i, p", tokenHash).stream().findFirst().orElse(null);
        if (r == null) {
            return new Accepted(Outcome.EXPIRED, null);
        }
        if (r.get("used_at") != null) { // replay of an accepted link: same answer, nothing changes
            return "active".equals(r.get("proxy_status")) && scope.equals(r.get("proxy_scope")) ? new Accepted(Outcome.ACCEPTED, scope) : new Accepted(Outcome.CONFLICT, null);
        }
        Instant now = clock.now();
        if (((Timestamp) r.get("expires_at")).toInstant().isBefore(now)) {
            return new Accepted(Outcome.EXPIRED, null);
        }
        UUID challenge = (UUID) r.get("otp_challenge_id");
        int attempts = (Integer) r.get("otp_attempts");
        Timestamp otpExp = (Timestamp) r.get("otp_expires_at");
        boolean live = challenge != null && otpExp != null && otpExp.toInstant().isAfter(now) && attempts < OTP_MAX_ATTEMPTS;
        if (!live || !MessageDigest.isEqual(hashOtp(challenge, otp).getBytes(StandardCharsets.UTF_8), String.valueOf(r.get("otp_hash")).getBytes(StandardCharsets.UTF_8))) {
            if (live) {
                jdbc.update("UPDATE proxy_invites SET otp_attempts = otp_attempts + 1 WHERE token_hash = ?", tokenHash);
            }
            return new Accepted(Outcome.OTP_INVALID, null);
        }
        String notice = consents.currentNotice("en").map(ConsentRegistry.NoticeRef::version).orElse(null);
        Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("otpChallengeId", challenge.toString());
        evidence.put("ip", ip);
        evidence.put("userAgent", userAgent == null ? null : userAgent.substring(0, Math.min(200, userAgent.length())));
        evidence.put("noticeVersion", notice);
        UUID proxy = (UUID) r.get("proxy_id");
        jdbc.update("UPDATE patient_proxies SET status = 'active', scope = ?, accepted_at = now(), evidence = ?::jsonb, notice_version = ? WHERE id = ?",
                scope, json.writeValueAsString(evidence), notice, proxy);
        jdbc.update("UPDATE proxy_invites SET used_at = now(), otp_hash = NULL WHERE token_hash = ?", tokenHash);
        audit.record(tenant, "system", null, "family member", "patient", ip, "patient.proxy_accepted", "patient_proxy", proxy, Map.of("status", "pending"),
                Map.of("status", "active", "scope", scope, "otpChallengeId", challenge.toString(), "noticeVersion", notice == null ? "" : notice));
        return new Accepted(Outcome.ACCEPTED, scope);
    }

    // ---- helpers ----

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    void invite(UUID tenant, UUID proxy, UUID subject) {
        String token = OpaqueTokens.generate();
        String hash = OpaqueTokens.sha256Hex(token);
        jdbc.update("INSERT INTO proxy_invites (token_hash, tenant_id, proxy_id, expires_at) VALUES (?,?,?,?)", hash, tenant, proxy, Timestamp.from(clock.now().plus(INVITE_TTL)));
        NotificationOutcome out = notifications.request(new NotificationRequest(NotificationPurpose.PROXY_INVITE, "proxy", proxy, subject, null, null,
                List.of(patientWebBase + "/proxy-invite/" + token), null, null, null, "proxy-invite:" + proxy + ":" + hash.substring(0, 12)));
        if (!"requested".equals(out.status())) {
            throw unprocessable("invite-not-sent", "Invite not sent", "The consent link could not be sent to the family member.");
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    Map<String, Object> liveInvite(String tokenHash, boolean forUpdate) {
        Map<String, Object> r = jdbc.queryForList("SELECT i.expires_at, i.used_at, i.otp_expires_at, p.relation, split_part(c.name, ' ', 1) AS caregiver_first_name, s.phone AS subject_phone "
                + "FROM proxy_invites i JOIN patient_proxies p ON p.id = i.proxy_id JOIN patients c ON c.id = p.proxy_patient_id JOIN patients s ON s.id = p.subject_patient_id "
                + "WHERE i.token_hash = ?" + (forUpdate ? " FOR UPDATE OF i" : ""), tokenHash).stream().findFirst().orElse(null);
        if (r == null || r.get("used_at") != null || ((Timestamp) r.get("expires_at")).toInstant().isBefore(clock.now())) {
            throw new ApiException(HttpStatus.GONE, "invite-expired", "Link expired", "This link has expired or was already used. Ask the person who invited you to send a new one.", Map.of("code", "invite-expired"));
        }
        return r;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    UUID adultCaregiver(String caregiverRef) {
        UUID id = refs.patientIdFor(caregiverRef).orElseThrow(ProxyService::notAProxy);
        UUID tenant = tenantContext.current();
        boolean adult = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM patients WHERE id = ? AND status = 'active' AND dob <= ?::date - INTERVAL '18 years')",
                Boolean.class, id, java.sql.Date.valueOf(clock.today(tenant))));
        if (!adult) {
            throw notAProxy();
        }
        return id;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    Added addMinor(UUID tenant, UUID caregiver, String name, LocalDate dob, String sex, String relation, String serviceId) {
        if (!consents.isGranted(caregiver, ConsentRegistry.Purpose.DATA_PROCESSING)) {
            throw unprocessable("guardian-required", "Guardian consent required", "A guardian must have given consent before a child is added.");
        }
        UUID child = UUID.randomUUID();
        jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender, guardian_id, registration_source) SELECT ?, ?, ?, phone, ?, ?, id, 'whatsapp' FROM patients WHERE id = ?",
                child, tenant, name, java.sql.Date.valueOf(dob), sex, caregiver);
        for (String type : List.of("data_processing", "guardian_access")) {
            jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, channel, guardian_patient_id) VALUES (?,?,?,'WHATSAPP',?)", tenant, child, type, caregiver);
        }
        audit.record(tenant, "system", null, "patient via edge", serviceId, null, "patient.guardian_member_added", "patient", child, null, Map.of("relation", relation));
        return new Added(refs.refFor(child), "guardian");
    }

    private String hashOtp(UUID challenge, String code) {
        return OpaqueTokens.sha256Hex(challenge + ":" + code);
    }

    private static ApiException unprocessable(String slug, String title, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, slug, title, detail, Map.of("code", slug));
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.");
    }

    private static ApiException notAProxy() { // unknown and foreign references answer alike: nothing to probe
        return new ApiException(HttpStatus.FORBIDDEN, "not-a-proxy", "Not allowed", "You do not act for that family member.", Map.of("code", "not-a-proxy"));
    }
}

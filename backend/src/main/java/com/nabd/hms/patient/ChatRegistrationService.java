package com.nabd.hms.patient;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.PhoneNumbers;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.DataGatePort;
import com.nabd.hms.shared.notify.NotificationOutcome;
import com.nabd.hms.shared.notify.NotificationPort;
import com.nabd.hms.shared.notify.NotificationPurpose;
import com.nabd.hms.shared.notify.NotificationRequest;
import com.nabd.hms.patient.PatientModels.MatchCandidateRow;
import com.nabd.hms.patient.api.ChatConsentAttacher;
import com.nabd.hms.patient.api.ConsentRegistry;
import com.nabd.hms.patient.api.PatientRefs;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.Period;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ACC-043: the Patient Access Edge creates a patient from a WhatsApp-verified phone, after the consent taps (IDC-005) and before any
 * health question, and later completes the record the desk started. No name, phone or date of birth is logged or audited here.
 * shortcut: age without a date of birth is stored as a date of birth of (today - age years); the record is flagged by
 * registration_source, upgrade when the registry keeps an "approximate" marker.
 */
@Service
@Transactional
class ChatRegistrationService {

    record Create(String phone, String phoneHash, String firstName, String lastName, LocalDate dob, Integer age, String sex,
                  UUID consentRecordId, String guardianRef) {
    }

    /** patientRef set = created; duplicateRefs non-empty = refused (returned, not thrown, so the audit row of the refusal commits). */
    record Result(String patientRef, List<String> duplicateRefs) {
    }

    private final JdbcTemplate jdbc;
    private final PatientRepository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final PatientRefs refs;
    private final ChatConsentAttacher attacher;
    private final ConsentRegistry consents;
    private final AuditService audit;
    private final DataGatePort dataGate;
    private final NotificationPort notifications;
    private final ActorDirectory actors;

    ChatRegistrationService(JdbcTemplate jdbc, PatientRepository repo, TenantContext tenantContext, ClinicClock clock, PatientRefs refs,
                            ChatConsentAttacher attacher, ConsentRegistry consents, AuditService audit, DataGatePort dataGate,
                            NotificationPort notifications, ActorDirectory actors) {
        this.notifications = notifications;
        this.actors = actors;
        this.jdbc = jdbc;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.refs = refs;
        this.attacher = attacher;
        this.consents = consents;
        this.audit = audit;
        this.dataGate = dataGate;
    }

    /** 422 consent-required / guardian-required are thrown; a likely duplicate comes back in the Result. */
    Result create(Create r, String serviceId) {
        UUID tenant = tenantContext.current();
        dataGate.requireRealData();
        String phone = PhoneNumbers.require(r.phone(), repo.region(tenant), "phone");
        if (!sha256(phone.replaceAll("\\D", "")).equals(r.phoneHash())) {
            throw bad("phoneHash does not belong to phone.");
        }
        if (r.dob() == null && r.age() == null) {
            throw bad("dob or age is required.");
        }
        LocalDate today = clock.today(tenant);
        LocalDate dob = r.dob() != null ? r.dob() : today.minusYears(r.age());
        if (!dob.isBefore(today)) {
            throw bad("The date of birth must be in the past.");
        }
        UUID guardian = null;
        if (Period.between(dob, today).getYears() < 18) {
            guardian = requireGuardian(r, phone, today); // the guardian's own recorded consent stands for the child
        } else {
            requireConsentBeforeData(tenant, r);
        }
        String name = r.lastName() == null || r.lastName().isBlank() ? r.firstName().trim() : r.firstName().trim() + " " + r.lastName().trim();
        List<MatchCandidateRow> same = repo.findDuplicateCandidates(tenant, phone, name, dob, 5).stream()
                .filter(c -> c.matchedFields().containsAll(List.of("phone", "name", "dob"))).toList(); // same phone AND similar name AND same date of birth
        if (!same.isEmpty()) {
            audit.record(tenant, "system", null, "patient via edge", serviceId, null, "patient.chat_registration_duplicate", "patient", same.get(0).id(), null,
                    Map.of("candidates", same.size()));
            return new Result(null, same.stream().map(c -> refs.refFor(c.id())).toList());
        }
        UUID id = UUID.randomUUID();
        repo.insert(id, tenant, name, phone, dob, r.sex(), guardian, null, null, false);
        jdbc.update("UPDATE patients SET registration_source = 'whatsapp' WHERE id = ?", id);
        if (guardian == null) {
            attacher.attach(id, r.phoneHash());
        } else {
            for (String type : List.of("data_processing", "guardian_access")) {
                jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, channel, guardian_patient_id) VALUES (?,?,?,'WHATSAPP',?)", tenant, id, type, guardian);
            }
        }
        audit.record(tenant, "system", null, "patient via edge", serviceId, null, "patient.registered_via_chat", "patient", id, null,
                Map.of("minor", guardian != null, "dobFromAge", r.dob() == null));
        return new Result(refs.refFor(id), List.of());
    }

    /**
     * The desk's "Complete in chat": remembers the request (the Edge finds it when the patient answers) and asks for the WhatsApp
     * message in the same transaction. 409 when the patient has not agreed to messages (nothing is stored), 409 when already complete.
     */
    void requestCompletion(UUID staffId, UUID patientId) {
        UUID tenant = tenantContext.current();
        Map<String, Object> p = jdbc.queryForList("SELECT profile_completed_at, preferred_language FROM patients WHERE id = ? AND tenant_id = app_tenant_id() AND status = 'active'", patientId)
                .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "patient-not-found", "Patient not found", "Patient not found"));
        if (p.get("profile_completed_at") != null) {
            throw new ApiException(HttpStatus.CONFLICT, "already-completed", "Already completed", "This patient has already finished registration in chat.");
        }
        jdbc.update("UPDATE patients SET chat_completion_requested_at = now() WHERE id = ?", patientId);
        NotificationOutcome out = notifications.request(new NotificationRequest(NotificationPurpose.PROFILE_COMPLETION, "patient", patientId, patientId, null,
                (String) p.get("preferred_language"), List.of(), null, null, null, "profile-completion:" + patientId + ":" + clock.today(tenant)));
        if ("blocked".equals(out.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "messaging-consent-missing", "Messaging consent missing",
                    "The patient has not agreed to WhatsApp messages; ask for consent first.");
        }
        ActorDirectory.Actor actor = actors.findActor(tenant, staffId).orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "staff-not-found", "Forbidden", "Forbidden"));
        audit.record(tenant, "staff", staffId, actor.name(), actor.role(), null, "patient.chat_completion_requested", "patient", patientId, null, Map.of("requested", true));
    }

    /** Fills the fields the desk left (address, email, language); only what is sent changes. Stamps profile_completed_at. */
    void complete(String patientRef, String address, String email, String preferredLanguage, String serviceId) {
        UUID tenant = tenantContext.current();
        UUID id = refs.patientIdFor(patientRef).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found."));
        if (address == null && email == null && preferredLanguage == null) {
            throw bad("Send at least one of address, email, preferredLanguage.");
        }
        jdbc.update("UPDATE patients SET address = COALESCE(?, address), email = COALESCE(?, email), preferred_language = COALESCE(?, preferred_language), "
                + "profile_completed_at = now(), chat_completion_requested_at = NULL WHERE id = ?", address, email, preferredLanguage, id);
        audit.record(tenant, "system", null, "patient via edge", serviceId, null, "patient.completed_in_chat", "patient", id, null,
                Map.of("fields", List.of(address != null ? "address" : "", email != null ? "email" : "", preferredLanguage != null ? "preferredLanguage" : "")
                        .stream().filter(s -> !s.isEmpty()).toList()));
    }

    /** The patient on this phone waiting to finish in chat, if any (newest request first). */
    java.util.Optional<Map<String, String>> pendingCompletion(String phoneHash) {
        return jdbc.queryForList("SELECT id, split_part(name, ' ', 1) AS first_name FROM patients WHERE chat_completion_requested_at IS NOT NULL AND status = 'active' "
                        + "AND encode(sha256(decode(phone_digits, 'escape')), 'hex') = ? ORDER BY chat_completion_requested_at DESC LIMIT 1", phoneHash).stream()
                .map(m -> Map.of("patientRef", refs.refFor((UUID) m.get("id")), "firstName", (String) m.get("first_name"))).findFirst();
    }

    /** The decision rows of the capture call must say yes to data_processing, belong to this phone, and not be attached yet. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    void requireConsentBeforeData(UUID tenant, Create r) {
        Boolean ok = r.consentRecordId() != null && jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM chat_consents c JOIN chat_consents k ON k.tenant_id = c.tenant_id AND k.wamid = c.wamid "
                + "WHERE c.id = ? AND c.phone_hash = ? AND k.purpose = 'data_processing' AND k.granted AND k.attached_patient_id IS NULL)", Boolean.class, r.consentRecordId(), r.phoneHash());
        if (!Boolean.TRUE.equals(ok)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "consent-required", "Consent required",
                    "Care-data consent must be recorded in this chat before a patient is created.", Map.of("code", "CONSENT_REQUIRED"));
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    UUID requireGuardian(Create r, String phone, LocalDate today) {
        UUID g = r.guardianRef() == null ? null : refs.patientIdFor(r.guardianRef()).orElse(null);
        boolean ok = g != null && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM patients WHERE id = ? AND status = 'active' AND dob <= ?::date - INTERVAL '18 years' "
                + "AND phone_digits = ?)", Boolean.class, g, java.sql.Date.valueOf(today), phone.replaceAll("\\D", "")))
                && consents.isGranted(g, ConsentRegistry.Purpose.DATA_PROCESSING);
        if (!ok) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "guardian-required", "Guardian required",
                    "A patient under 18 needs a registered adult guardian on this phone who has given consent.", Map.of("code", "GUARDIAN_REQUIRED"));
        }
        return g;
    }

    static String sha256(String digits) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(digits.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException bad(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail);
    }
}

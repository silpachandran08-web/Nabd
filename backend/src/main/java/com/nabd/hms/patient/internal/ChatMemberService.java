package com.nabd.hms.patient.internal;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.PhoneNumbers;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.api.DataGatePort;
import com.nabd.hms.patient.api.ConsentRegistry;
import com.nabd.hms.patient.api.PatientRefs;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.Period;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * IDC-010: a caregiver adds a family member from the WhatsApp chat. A minor gets the caregiver as guardian and a guardian
 * consent row (OTP challenge id as evidence, one row per guardian, PED-06). An adult with a phone is created without any
 * consent and waits for the member's own consent ('awaiting_consent'). An adult without a phone is created on the caregiver's
 * number with scope BOOK_ONLY and a desk task to confirm consent in person at the next visit (assisted consent, IDC-007).
 * Default-deny (IDC-006) keeps every member without a data_processing grant out of clinical reads.
 */
@Service
@Transactional
class ChatMemberService {

    record GuardianConsent(String otpChallengeId, String noticeVersion) {
    }

    record Request(String relation, String name, LocalDate dob, String phone, String gender, String language,
                   GuardianConsent guardianConsent, String memberRef) {
    }

    record Result(String memberRef, String proxyStatus, boolean minor, boolean created) {
    }

    record Listed(String memberRef, String firstName, String relation, String proxyStatus, String proxyScope) {
    }

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ClinicClock clock;
    private final PatientRefs refs;
    private final ConsentRegistry registry;
    private final DataGatePort dataGate;
    private final EventPublisher events;
    private final AuditService audit;
    private final ObjectMapper json;

    ChatMemberService(JdbcTemplate jdbc, TenantContext tenantContext, ClinicClock clock, PatientRefs refs, ConsentRegistry registry,
                      DataGatePort dataGate, EventPublisher events, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.clock = clock;
        this.refs = refs;
        this.registry = registry;
        this.dataGate = dataGate;
        this.events = events;
        this.audit = audit;
        this.json = json;
    }

    private record Caregiver(UUID id, String phone) {
    }

    Result add(String caregiverRef, Request req, String serviceId) {
        UUID tenant = tenantContext.current();
        Caregiver cg = caregiver(caregiverRef);
        LocalDate today = clock.today(tenant);
        if (req.memberRef() != null) {
            return coGuardian(tenant, cg, req, serviceId);
        }
        if (req.dob().isAfter(today) || req.dob().isBefore(today.minusYears(120))) {
            throw bad("dob is not a plausible date of birth.");
        }
        boolean minor = Period.between(req.dob(), today).getYears() < 18;
        if (minor) {
            requireGuardianConsent(req);
        }
        dataGate.requireRealData();
        UUID same = jdbc.query("SELECT p.id FROM chat_added_members m JOIN patients p ON p.tenant_id = m.tenant_id AND p.id = m.member_patient_id "
                        + "WHERE m.tenant_id = ? AND m.caregiver_patient_id = ? AND p.dob = ? AND lower(p.name) = lower(?) LIMIT 1",
                (rs, i) -> rs.getObject(1, UUID.class), tenant, cg.id(), req.dob(), req.name().strip()).stream().findFirst().orElse(null);
        if (same != null) { // replay of the same chat step: the first answer again, nothing stored twice
            String status = jdbc.queryForObject("SELECT proxy_status FROM chat_added_members WHERE tenant_id = ? AND member_patient_id = ?", String.class, tenant, same);
            return new Result(refs.refFor(same), status, minor, false);
        }
        boolean ownPhone = !minor && req.phone() != null && !req.phone().isBlank();
        String phone = ownPhone ? PhoneNumbers.require(req.phone(), jdbc.queryForObject("SELECT region_code FROM tenants WHERE id = ?", String.class, tenant), "phone") : cg.phone();
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO patients (id, tenant_id, name, phone, dob, gender, guardian_id) VALUES (?,?,?,?,?,?,?)",
                id, tenant, req.name().strip(), phone, req.dob(), req.gender() == null ? "other" : req.gender(), minor ? cg.id() : null);
        String scope;
        String status;
        if (minor) {
            jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, version, source, channel) VALUES (?,?,'guardian_access',1,'whatsapp_chat','WHATSAPP')", tenant, id);
            guardianConsent(tenant, id, cg.id(), req);
            scope = "GUARDIAN";
            status = "guardian_consented";
        } else if (ownPhone) {
            scope = "NONE";
            status = "awaiting_consent"; // the consent invite (IDC-009) goes to the member's own number
        } else {
            scope = "BOOK_ONLY";
            status = "pending_desk_confirmation";
            jdbc.update("INSERT INTO patient_desk_tasks (tenant_id, kind, patient_id, caregiver_patient_id) VALUES (?, 'confirm_assisted_consent', ?, ?)", tenant, id, cg.id());
        }
        jdbc.update("INSERT INTO chat_added_members (tenant_id, caregiver_patient_id, member_patient_id, relation, proxy_scope, proxy_status) VALUES (?,?,?,?,?,?)",
                tenant, cg.id(), id, req.relation(), scope, status);
        audit.record(tenant, "system", null, "patient via edge", serviceId, null, "patient.member_added", "patient", id, null,
                Map.of("relation", req.relation(), "minor", minor, "proxyStatus", status, "proxyScope", scope));
        return new Result(refs.refFor(id), status, minor, true);
    }

    /** The second guardian of an existing child: one more guardian consent row of their own, nothing else changes. */
    private Result coGuardian(UUID tenant, Caregiver cg, Request req, String serviceId) {
        UUID child = refs.patientIdFor(req.memberRef()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "member-not-found", "Member not found", "No such member."));
        LocalDate dob = jdbc.queryForObject("SELECT dob FROM patients WHERE tenant_id = ? AND id = ?", LocalDate.class, tenant, child);
        if (Period.between(dob, clock.today(tenant)).getYears() >= 18 || child.equals(cg.id())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "not-a-minor", "Not a minor", "A second guardian can only be added to a patient under 18.");
        }
        requireGuardianConsent(req);
        Integer have = jdbc.queryForObject("SELECT count(*) FROM consents WHERE tenant_id = ? AND patient_id = ? AND consent_type = 'data_processing' AND guardian_patient_id = ? AND withdrawn_at IS NULL",
                Integer.class, tenant, child, cg.id());
        if (have == 0) {
            guardianConsent(tenant, child, cg.id(), req);
            audit.record(tenant, "system", null, "patient via edge", serviceId, null, "patient.member_guardian_added", "patient", child, null, Map.of("minor", true));
        }
        return new Result(req.memberRef(), "guardian_consented", true, have == 0);
    }

    List<Listed> members(String caregiverRef) {
        UUID tenant = tenantContext.current();
        Caregiver cg = caregiver(caregiverRef);
        return jdbc.query("SELECT m.member_patient_id, p.name, m.relation, m.proxy_status, m.proxy_scope FROM chat_added_members m "
                        + "JOIN patients p ON p.tenant_id = m.tenant_id AND p.id = m.member_patient_id WHERE m.tenant_id = ? AND m.caregiver_patient_id = ? ORDER BY m.created_at, m.id",
                (rs, i) -> new Listed(refs.refFor(rs.getObject(1, UUID.class)), firstName(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getString(5)), tenant, cg.id());
    }

    private void requireGuardianConsent(Request req) {
        GuardianConsent g = req.guardianConsent();
        if (g == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "guardian-required", "Guardian consent required",
                    "A patient under 18 needs the guardian's consent with its OTP evidence.");
        }
        String lang = req.language() == null ? "en" : req.language();
        if (!registry.currentNotice(lang).map(n -> n.version().equals(g.noticeVersion())).orElse(false)) {
            throw new ApiException(HttpStatus.CONFLICT, "notice-version-stale", "Notice version is stale",
                    "That privacy notice version is not the one in force for this language; show the current notice again.");
        }
    }

    private void guardianConsent(UUID tenant, UUID patient, UUID guardian, Request req) {
        String lang = req.language() == null ? "en" : req.language();
        String evidence = json.writeValueAsString(Map.of("consentBy", "guardian", "declaration", true, "otpChallengeId", req.guardianConsent().otpChallengeId()));
        jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, version, source, channel, notice_version, language, guardian_patient_id, evidence) "
                        + "VALUES (?,?,'data_processing',(SELECT COALESCE(max(version), 0) + 1 FROM consents WHERE patient_id = ? AND consent_type = 'data_processing'),'whatsapp_chat','WHATSAPP',?,?,?,?::jsonb)",
                tenant, patient, patient, req.guardianConsent().noticeVersion(), lang, guardian, evidence);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("patientId", patient);
        data.put("consentType", "data_processing");
        data.put("status", "granted");
        data.put("noticeVersion", req.guardianConsent().noticeVersion());
        data.put("guardianRef", guardian);
        events.publish(tenant, DomainEventType.CONSENT_CHANGED, patient, data);
    }

    private Caregiver caregiver(String ref) {
        UUID id = refs.patientIdFor(ref).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "No such caregiver."));
        return jdbc.query("SELECT id, phone FROM patients WHERE tenant_id = ? AND id = ? AND status = 'active'", (rs, i) -> new Caregiver(rs.getObject(1, UUID.class), rs.getString(2)),
                tenantContext.current(), id).stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "No such caregiver."));
    }

    private static String firstName(String name) {
        String n = name == null ? "" : name.trim();
        int sp = n.indexOf(' ');
        return sp < 0 ? n : n.substring(0, sp);
    }

    private static ApiException bad(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail);
    }
}

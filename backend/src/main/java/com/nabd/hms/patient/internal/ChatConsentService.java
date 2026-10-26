package com.nabd.hms.patient.internal;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.patient.api.ChatConsentAttacher;
import com.nabd.hms.patient.api.ConsentRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** IDC-005: stores the consent decisions of a WhatsApp booker before any patient row exists, then attaches them to the patient. */
@Service
@Transactional
class ChatConsentService implements ChatConsentAttacher {

    record Decision(String purpose, boolean granted) {
    }

    private static final Set<String> PURPOSES = Set.of("data_processing", "messaging", "marketing");

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ConsentRegistry registry;
    private final EventPublisher events;
    private final AuditService audit;

    ChatConsentService(JdbcTemplate jdbc, TenantContext tenantContext, ConsentRegistry registry, EventPublisher events, AuditService audit) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.registry = registry;
        this.events = events;
        this.audit = audit;
    }

    /** Idempotent on (clinic, wamid): a replay returns the ids of the first call, even after a newer notice was published. */
    List<UUID> capture(String phoneHash, List<Decision> decisions, String noticeVersion, String language, String wamid, String serviceId) {
        UUID tenant = tenantContext.current();
        List<UUID> existing = ids(tenant, wamid);
        if (!existing.isEmpty()) {
            return existing;
        }
        Set<String> seen = new HashSet<>();
        for (Decision d : decisions) {
            if (!PURPOSES.contains(d.purpose()) || !seen.add(d.purpose())) {
                throw bad("Each decision names one of data_processing, messaging, marketing, once.");
            }
        }
        if (!seen.contains("data_processing")) {
            throw bad("A data_processing decision is required.");
        }
        List<Decision> all = new ArrayList<>(decisions);
        if (!seen.contains("marketing")) {
            all.add(new Decision("marketing", false)); // never pre-selected: no answer is a stored "no"
        }
        boolean current = registry.currentNotice(language).map(n -> n.version().equals(noticeVersion)).orElse(false);
        if (!current) {
            throw new ApiException(HttpStatus.CONFLICT, "notice-version-stale", "Notice version is stale",
                    "That privacy notice version is not the one in force for this language; show the current notice again.");
        }
        for (Decision d : all) {
            jdbc.update("INSERT INTO chat_consents (tenant_id, phone_hash, purpose, granted, notice_version, language, wamid) VALUES (?,?,?,?,?,?,?) "
                    + "ON CONFLICT (tenant_id, wamid, purpose) DO NOTHING", tenant, phoneHash, d.purpose(), d.granted(), noticeVersion, language, wamid);
        }
        List<UUID> ids = ids(tenant, wamid);
        audit.record(tenant, "system", null, "patient via edge", serviceId, null, "consent.captured_via_chat", "chat_consent", ids.get(0), null,
                Map.of("noticeVersion", noticeVersion, "language", language, "wamid", wamid,
                        "granted", all.stream().filter(Decision::granted).map(Decision::purpose).toList()));
        return ids;
    }

    @Override
    public int attach(UUID patientId, String phoneHash) {
        UUID tenant = tenantContext.current();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, purpose, granted, notice_version, language, wamid, decided_at FROM chat_consents "
                + "WHERE tenant_id = ? AND phone_hash = ? AND attached_patient_id IS NULL ORDER BY decided_at, id FOR UPDATE", tenant, phoneHash);
        for (Map<String, Object> r : rows) {
            String purpose = (String) r.get("purpose");
            boolean granted = (Boolean) r.get("granted");
            int version = jdbc.queryForObject("SELECT COALESCE(max(version), 0) + 1 FROM consents WHERE patient_id = ? AND consent_type = ?",
                    Integer.class, patientId, purpose);
            // a refusal is a row that is already withdrawn: the newest row decides, so "no" stays "no" (default-deny)
            jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, granted_at, withdrawn_at, version, source, notice_version, language, channel, evidence) "
                    + "VALUES (?,?,?,?,CASE WHEN ?::boolean THEN NULL ELSE ?::timestamptz END,?,?,?,?,'WHATSAPP',jsonb_build_object('wamid', ?::text))",
                    tenant, patientId, purpose, r.get("decided_at"), granted, r.get("decided_at"), version, "whatsapp_chat",
                    r.get("notice_version"), r.get("language"), r.get("wamid"));
            jdbc.update("UPDATE chat_consents SET attached_patient_id = ?, attached_at = now() WHERE id = ?", patientId, r.get("id"));
            events.publish(tenant, DomainEventType.CONSENT_CHANGED, patientId,
                    Map.of("patientId", patientId, "consentType", purpose, "status", granted ? "granted" : "withdrawn"));
        }
        if (!rows.isEmpty()) {
            audit.record(tenant, "system", null, "chat registration", "System", null, "consent.attached_from_chat", "patient", patientId, null,
                    Map.of("decisions", rows.size()));
        }
        return rows.size();
    }

    private List<UUID> ids(UUID tenant, String wamid) {
        return jdbc.queryForList("SELECT id FROM chat_consents WHERE tenant_id = ? AND wamid = ? ORDER BY purpose", UUID.class, tenant, wamid);
    }

    private static ApiException bad(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation-failed", "Validation failed", detail);
    }
}

package com.nabd.hms.patient.internal;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.patient.api.PatientConsentCommands;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
class PatientConsentService implements PatientConsentCommands {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final EventPublisher events;
    private final AuditService audit;

    PatientConsentService(JdbcTemplate jdbc, TenantContext tenantContext, EventPublisher events, AuditService audit) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.events = events;
        this.audit = audit;
    }

    @Override
    public Result withdrawByPhone(String phoneDigits, String purpose, String source, long inboundId) {
        return change(phoneDigits, purpose, source, inboundId, false);
    }

    @Override
    public Result grantByPhone(String phoneDigits, String purpose, String source, long inboundId) {
        return change(phoneDigits, purpose, source, inboundId, true);
    }

    private Result change(String phoneDigits, String purpose, String source, long inboundId, boolean grant) {
        UUID tenant = tenantContext.current();
        // phone is stored normalised (+E.164, ACC-028); the bare-digits form covers rows from before that. Both hit idx_patients_phone.
        List<UUID> patients = jdbc.queryForList(
                "SELECT id FROM patients WHERE tenant_id = ? AND phone IN (?, ?) ORDER BY id", UUID.class,
                tenant, "+" + phoneDigits, phoneDigits);
        int changed = 0;
        int version = 0;
        for (UUID p : patients) {
            // the latest row decides (consents are append-only); null = no row yet
            Boolean withdrawn = jdbc.query("SELECT withdrawn_at IS NOT NULL FROM consents WHERE patient_id = ? AND consent_type = ? "
                    + "ORDER BY created_at DESC, id DESC LIMIT 1", (rs, i) -> rs.getBoolean(1), p, purpose).stream().findFirst().orElse(null);
            if (grant ? !Boolean.TRUE.equals(withdrawn) : Boolean.TRUE.equals(withdrawn)) {
                continue;
            }
            int v = jdbc.queryForObject("SELECT COALESCE(max(version), 0) + 1 FROM consents WHERE patient_id = ? AND consent_type = ?",
                    Integer.class, p, purpose);
            jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, withdrawn_at, version, source) "
                    + "VALUES (?,?,?," + (grant ? "NULL" : "now()") + ",?,?)", tenant, p, purpose, v, source);
            events.publish(tenant, DomainEventType.CONSENT_CHANGED, p,
                    Map.of("patientId", p, "consentType", purpose, "status", grant ? "granted" : "withdrawn"));
            audit.record(tenant, "system", null, "WhatsApp", "System", null, grant ? "consent.granted_by_phone" : "consent.withdrawn_by_phone",
                    "patient", p, null, Map.of("purpose", purpose, "source", source, "inboundId", inboundId));
            changed++;
            version = Math.max(version, v);
        }
        return new Result(patients, changed, version);
    }
}

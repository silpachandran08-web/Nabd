package com.nabd.hms.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * NB-238 / SYS-13: append-only audit trail for every PHI and financial action. record() is the
 * one write path every module should call — the append-only guarantee and the per-tenant hash
 * chain both live in V14__audit_log.sql (triggers, not application code), so nothing here can
 * accidentally weaken them. See that migration's header for the two tamper defenses.
 */
@Component
public class AuditService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final ObjectMapper objectMapper;

    AuditService(JdbcTemplate jdbc, TenantContext tenantContext, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.objectMapper = objectMapper;
    }

    /**
     * @param actorType 'staff', 'operator', or 'system'
     * @param actorId   null when actorType is 'system'
     * @param actorRole snapshot label — 'Owner' / the staff role name / the operator role / 'System'
     * @param before    prior state, serialized to jsonb; null if there was none (e.g. a create)
     * @param after     new state, serialized to jsonb; null if there is none (e.g. a delete)
     */
    @Transactional
    public void record(UUID tenantId, String actorType, UUID actorId, String actorName, String actorRole,
                        String ipAddress, String action, String entityType, UUID entityId,
                        Object before, Object after) {
        tenantContext.set(tenantId);
        jdbc.update("""
                INSERT INTO audit_log (tenant_id, actor_type, actor_id, actor_name, actor_role, ip_address,
                                        action, entity_type, entity_id, before, after)
                VALUES (?,?,?,?,?,?::inet,?,?,?,?::jsonb,?::jsonb)
                """,
                tenantId, actorType, actorId, actorName, actorRole, ipAddress, action, entityType, entityId,
                toJson(before), toJson(after));
    }

    /** Recomputes this tenant's whole chain and reports the first row whose hash doesn't match, if any. */
    @Transactional
    public AuditVerification verify(UUID tenantId) {
        tenantContext.set(tenantId);
        Long brokenAtId = jdbc.queryForObject("SELECT verify_audit_chain(?)", Long.class, tenantId);
        return new AuditVerification(brokenAtId == null, brokenAtId);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("audit before/after value is not serializable", e);
        }
    }
}

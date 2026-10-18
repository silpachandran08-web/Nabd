package com.nabd.hms.platform;

import com.nabd.hms.common.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * LIVE-08: one write path for platform-operator audit events. Resolves the operator's name/role snapshot and
 * writes into the tenant's chain when the action concerns one tenant, else into the platform chain (V126).
 * A null operatorId is recorded as the system actor (e.g. a provisioning step with no operator).
 * No secrets (PIN, tokens, MFA codes) and no PHI/owner contact details may be passed in {@code after}.
 */
@Component
public class PlatformAudit {

    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    PlatformAudit(AuditService auditService, JdbcTemplate jdbc) {
        this.auditService = auditService;
        this.jdbc = jdbc;
    }

    public void record(UUID tenantId, UUID operatorId, String ipAddress, String action, String entityType,
                       UUID entityId, Object after) {
        record(tenantId, operatorId, ipAddress, action, entityType, entityId, null, after);
    }

    public void record(UUID tenantId, UUID operatorId, String ipAddress, String action, String entityType,
                       UUID entityId, Object before, Object after) {
        String[] actor = actor(operatorId);
        String actorType = operatorId == null ? "system" : "operator";
        if (tenantId == null) {
            auditService.recordPlatform(actorType, operatorId, actor[0], actor[1], ipAddress, action, entityType,
                    entityId, before, after);
        } else {
            auditService.record(tenantId, actorType, operatorId, actor[0], actor[1], ipAddress, action, entityType,
                    entityId, before, after);
        }
    }

    // master.operators carries no RLS (platform-only data) — same lookup the other platform repositories do.
    private String[] actor(UUID operatorId) {
        if (operatorId == null) {
            return new String[] {"Nabd System", "System"};
        }
        return jdbc.query("SELECT name, role FROM master.operators WHERE id = ?",
                        (rs, i) -> new String[] {rs.getString("name"), rs.getString("role")}, operatorId)
                .stream().findFirst().orElse(new String[] {"unknown operator", "unknown"});
    }
}

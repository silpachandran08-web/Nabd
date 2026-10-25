package com.nabd.hms.identity.abdm;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** IDC-016: tenant_integrations_abdm (RLS). Transactional on purpose and separate from AbdmService, which makes the remote calls outside any transaction. */
@Component
@Transactional
class AbdmSettingsService {

    record Settings(boolean enabled, String hfrId, Instant verifiedAt) {
        boolean hfrVerified() {
            return verifiedAt != null;
        }
    }

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final AuditService audit;
    private final ActorDirectory actors;

    AbdmSettingsService(JdbcTemplate jdbc, TenantContext tenantContext, AuditService audit, ActorDirectory actors) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.audit = audit;
        this.actors = actors;
    }

    @Transactional(readOnly = true)
    Optional<Settings> find() {
        return jdbc.query("SELECT enabled, hfr_id, verified_at FROM tenant_integrations_abdm WHERE tenant_id = app_tenant_id()",
                (rs, i) -> new Settings(rs.getBoolean(1), rs.getString(2), rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant())).stream().findFirst();
    }

    /** Null hfrId / verifiedAt keep what is stored (switching off never forgets a verified facility). */
    void save(boolean enabled, String hfrId, Instant verifiedAt, UUID staffId, String ip) {
        UUID tenant = tenantContext.current();
        jdbc.update("INSERT INTO tenant_integrations_abdm AS t (tenant_id, enabled, hfr_id, verified_at, updated_by) VALUES (?,?,?,?,?) " +
                "ON CONFLICT (tenant_id) DO UPDATE SET enabled = EXCLUDED.enabled, hfr_id = COALESCE(EXCLUDED.hfr_id, t.hfr_id), " +
                "verified_at = COALESCE(EXCLUDED.verified_at, t.verified_at), updated_by = EXCLUDED.updated_by, updated_at = now()",
                tenant, enabled, hfrId, verifiedAt == null ? null : java.sql.Timestamp.from(verifiedAt), staffId);
        ActorDirectory.Actor a = actors.findActor(tenant, staffId).orElse(null);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("enabled", enabled);
        after.put("hfrVerified", verifiedAt != null);
        audit.record(tenant, "staff", staffId, a == null ? "Staff" : a.name(), a == null ? "Staff" : a.role(), ip,
                "identity.abdm_settings_changed", "abdm_integration", tenant, null, after);
    }
}

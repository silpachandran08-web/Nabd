package com.nabd.hms.staff;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/** IDC-016: writes a verified HPR id onto the staff row, audited. */
@Service
@Transactional
class StaffHprService {
    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final AuditService audit;
    private final ActorDirectory actors;

    StaffHprService(JdbcTemplate jdbc, TenantContext tenantContext, AuditService audit, ActorDirectory actors) {
        this.actors = actors;
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.audit = audit;
    }

    void store(UUID staffId, String hprId, UUID actorId) {
        UUID tenant = tenantContext.current();
        if (jdbc.update("UPDATE staff SET hpr_id = ?, hpr_verified_at = now() WHERE tenant_id = ? AND id = ?", hprId, tenant, staffId) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "staff-not-found", "Staff not found", "Staff not found");
        }
        var a = actors.findActor(tenant, actorId).orElse(null);
        audit.record(tenant, "staff", actorId, a == null ? "Staff" : a.name(), a == null ? "Staff" : a.role(), null, "identity.hpr_verified", "staff", staffId, null, Map.of("hprVerified", true));
    }
}

package com.nabd.hms.iam.internal;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.PractitionerRecords;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
class JdbcPractitionerRecords implements PractitionerRecords {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

    JdbcPractitionerRecords(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    @Override
    public List<Practitioner> find(Collection<UUID> staffIds) {
        UUID[] ids = staffIds.stream().filter(Objects::nonNull).distinct().toArray(UUID[]::new);
        if (ids.length == 0) return List.of();
        return jdbc.query("SELECT id, name, CASE WHEN hpr_verified_at IS NOT NULL THEN hpr_id END AS hpr_id, professional_registration_no "
                        + "FROM staff WHERE tenant_id = ? AND id = ANY(?) ORDER BY id",
                ps -> {
                    ps.setObject(1, tenantContext.current());
                    ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids));
                },
                (rs, i) -> new Practitioner(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("hpr_id"),
                        rs.getString("professional_registration_no")));
    }
}

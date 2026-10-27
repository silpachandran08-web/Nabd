package com.nabd.hms.queue;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.organization.api.GoLiveCheck;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** ADM-011 blocker: at least one active doctor has a weekly working-hours template. */
@Component
class DoctorScheduleGoLiveCheck implements GoLiveCheck {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final ActorDirectory actors;

    DoctorScheduleGoLiveCheck(JdbcTemplate jdbc, TenantContext tenant, ActorDirectory actors) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.actors = actors;
    }

    @Override
    public boolean blocker() {
        return true;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Result evaluate() {
        UUID tenantId = tenant.current();
        UUID[] doctors = actors.listClinicians(tenantId).stream().map(ActorDirectory.Actor::id).toArray(UUID[]::new);
        boolean ok = doctors.length > 0 && Boolean.TRUE.equals(jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT EXISTS (SELECT 1 FROM doctor_working_hours WHERE tenant_id = ? AND doctor_id = ANY (?))");
            ps.setObject(1, tenantId);
            ps.setArray(2, con.createArrayOf("uuid", doctors));
            return ps;
        }, rs -> rs.next() && rs.getBoolean(1)));
        return new Result("doctor_schedule", ok, "/schedule", null);
    }
}

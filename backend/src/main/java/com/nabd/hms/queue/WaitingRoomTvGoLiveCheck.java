package com.nabd.hms.queue;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.api.GoLiveCheck;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** ADM-011 good to have: a waiting-room display is paired. */
@Component
class WaitingRoomTvGoLiveCheck implements GoLiveCheck {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    WaitingRoomTvGoLiveCheck(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Override
    public boolean blocker() {
        return false;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Result evaluate() {
        boolean ok = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM display_devices WHERE tenant_id = ? AND revoked_at IS NULL)", Boolean.class, tenant.current()));
        return new Result("waiting_room_tv", ok, null, "Pair a waiting-room screen any time from the queue settings.");
    }
}

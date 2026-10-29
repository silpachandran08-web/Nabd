package com.nabd.hms.staff;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.api.GoLiveCheck;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** ADM-011 good to have: every invited colleague has accepted. Pending invites are listed as "later", never a blocker. */
@Component
class StaffInvitesGoLiveCheck implements GoLiveCheck {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    StaffInvitesGoLiveCheck(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Override
    public boolean blocker() {
        return false;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Result evaluate() {
        int[] c = jdbc.queryForObject("SELECT count(*) FILTER (WHERE status = 'invited'), count(*) FILTER (WHERE status IN ('invited','active')) FROM staff WHERE tenant_id = ?",
                (rs, i) -> new int[] {rs.getInt(1), rs.getInt(2)}, tenant.current());
        return new Result("staff_invited", c[0] == 0, null, c[0] == 0 ? null : c[0] + " of " + c[1] + " invites are still pending; they can join later.");
    }
}

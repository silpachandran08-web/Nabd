package com.nabd.hms.staff;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TrainingRules;
import com.nabd.hms.organization.api.GoLiveCheck;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;

/**
 * PLT-054 blocker: every active staff account holds a current pass of the PHI-handling module before the clinic goes live
 * (real patient data). Invited accounts cannot sign in yet, so they do not count; they show as due on the team list once they join.
 */
@Component
class TrainingGoLiveCheck implements GoLiveCheck {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final Clock clock;

    TrainingGoLiveCheck(JdbcTemplate jdbc, TenantContext tenant, Clock clock) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.clock = clock;
    }

    @Override
    public boolean blocker() {
        return true;
    }

    @Override
    public int order() {
        return 45;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Result evaluate() {
        Integer untrained = jdbc.queryForObject("SELECT count(*) FROM staff s WHERE s.tenant_id = ? AND s.status = 'active' AND NOT EXISTS ("
                        + "SELECT 1 FROM training_completions c WHERE c.tenant_id = s.tenant_id AND c.staff_id = s.id AND c.module_id = ? AND " + TrainingRules.CURRENT_SQL + ")",
                Integer.class, tenant.current(), TrainingRules.REQUIRED_MODULE, Timestamp.from(clock.instant()));
        return new Result("staff_phi_training", untrained == 0, "/staff", null);
    }
}

package com.nabd.hms.platform.billing;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TrialSubscriptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
class TrialSubscriptionReader implements TrialSubscriptions {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    TrialSubscriptionReader(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Override
    public Optional<Current> current() {
        return jdbc.query("SELECT status, trial_ends_at FROM master.subscriptions WHERE tenant_id = ?",
                (rs, i) -> new Current(rs.getString("status"), rs.getTimestamp("trial_ends_at") == null ? null : rs.getTimestamp("trial_ends_at").toInstant()),
                tenant.current()).stream().findFirst();
    }
}

package com.nabd.edge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * ACC-045: is the bot switched on for this clinic (clinic_rollout, Edge V21)? The table refuses `enabled` before the
 * onboarding checklist is complete. A clinic without a row follows edge.rollout.default-enabled (cohort A predates the table).
 */
@Component
public class ClinicRollout {

    private final TenantTx tx;
    private final boolean defaultEnabled;

    ClinicRollout(TenantTx tx, @Value("${edge.rollout.default-enabled:true}") boolean defaultEnabled) {
        this.tx = tx;
        this.defaultEnabled = defaultEnabled;
    }

    public boolean enabled(UUID tenantId) {
        List<Boolean> row = tx.run(tenantId, (j, s) -> j.queryForList("select enabled from clinic_rollout where tenant_id = ?", Boolean.class, tenantId));
        return row.isEmpty() ? defaultEnabled : Boolean.TRUE.equals(row.get(0));
    }
}

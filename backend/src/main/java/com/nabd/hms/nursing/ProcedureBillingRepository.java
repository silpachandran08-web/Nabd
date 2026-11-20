package com.nabd.hms.nursing;

import com.nabd.hms.nursing.api.ProcedureBilling;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** PLT-036: the statement moved unchanged from billing's CheckoutRepository.markProceduresBilled. */
@Repository
class ProcedureBillingRepository implements ProcedureBilling {

    private final JdbcTemplate jdbc;

    ProcedureBillingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void markVisitBilled(UUID tenantId, UUID visitId) {
        jdbc.update("UPDATE procedure_orders SET billed = true WHERE tenant_id = ? AND queue_entry_id = ? " +
                "AND status = 'completed' AND billed = false", tenantId, visitId);
    }
}

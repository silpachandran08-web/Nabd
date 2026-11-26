package com.nabd.hms.billing;

import com.nabd.hms.billing.api.VisitCharges;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** PHL-043: billing.api.VisitCharges on visit_pending_charges (V3630). The checkout reads and settles the rows (CheckoutRepository). */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class VisitChargesService implements VisitCharges {

    private final JdbcTemplate jdbc;

    VisitChargesService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Pending> add(UUID tenantId, UUID visitId, String source, UUID sourceRef, List<UUID> chargeItemIds) {
        List<Pending> out = new ArrayList<>();
        for (UUID chargeItemId : chargeItemIds) {
            out.add(new Pending(jdbc.queryForObject("INSERT INTO visit_pending_charges (tenant_id, queue_entry_id, source, source_ref, charge_item_id) "
                    + "VALUES (?,?,?,?,?) RETURNING id", UUID.class, tenantId, visitId, source, sourceRef, chargeItemId), chargeItemId));
        }
        return out;
    }

    @Override
    public Removed remove(UUID tenantId, String source, UUID sourceRef) {
        int removed = jdbc.update("UPDATE visit_pending_charges SET status = 'removed', settled_at = now() "
                + "WHERE tenant_id = ? AND source = ? AND source_ref = ? AND status = 'pending'", tenantId, source, sourceRef);
        Integer billed = jdbc.queryForObject("SELECT count(*) FROM visit_pending_charges WHERE tenant_id = ? AND source = ? AND source_ref = ? "
                + "AND status = 'billed'", Integer.class, tenantId, source, sourceRef);
        return new Removed(removed, billed == null ? 0 : billed);
    }
}

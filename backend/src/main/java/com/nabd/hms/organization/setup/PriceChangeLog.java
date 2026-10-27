package com.nabd.hms.organization.setup;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.setup.SetupModels.ChargeHeadRow;
import com.nabd.hms.organization.setup.dto.ChargeHeadWriteRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** FIN-016: one price_changes row per changed price-bearing field of a head, written in the update's own transaction. */
@Component
class PriceChangeLog {

    private final PricingRepository repo;
    private final TenantContext tenantContext;

    PriceChangeLog(PricingRepository repo, TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void logChargeUpdate(UUID chargeId, ChargeHeadRow old, ChargeHeadWriteRequest req, UUID actorId) {
        UUID tenantId = tenantContext.current();
        if (!PricingService.same(old.baseAmount(), req.baseAmount())) repo.log(tenantId, chargeId, null, "base_amount", PricingService.str(old.baseAmount()), PricingService.str(req.baseAmount()), actorId);
        if (!PricingService.same(old.followUpAmount(), req.followUpAmount())) repo.log(tenantId, chargeId, null, "follow_up_amount", PricingService.str(old.followUpAmount()), PricingService.str(req.followUpAmount()), actorId);
        if (!PricingService.same(old.emergencyAmount(), req.emergencyAmount())) repo.log(tenantId, chargeId, null, "emergency_amount", PricingService.str(old.emergencyAmount()), PricingService.str(req.emergencyAmount()), actorId);
        if (old.active() != req.active()) repo.log(tenantId, chargeId, null, "active", String.valueOf(old.active()), String.valueOf(req.active()), actorId);
    }
}

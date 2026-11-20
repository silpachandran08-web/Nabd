package com.nabd.hms.organization.setup;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.organization.api.ChargeCatalogue;
import com.nabd.hms.organization.setup.SetupModels.ChargeHeadRow;
import com.nabd.hms.organization.setup.dto.ChargeHeadWriteRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** PHL-041: {@link ChargeCatalogue} over the price master's own write path (SetupService: change log, catalogue event). Joins the caller's transaction. */
@Service
class ChargeCatalogueService implements ChargeCatalogue {

    private final SetupService setup;
    private final SetupRepository repo;
    private final TenantContext tenantContext;
    private final ClinicClock clock;

    ChargeCatalogueService(SetupService setup, SetupRepository repo, TenantContext tenantContext, ClinicClock clock) {
        this.setup = setup;
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Set<String> taken(Collection<String> codes) {
        return repo.chargeCodesIn(tenantContext.current(), codes);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID add(UUID actorId, String code, String name, String category, BigDecimal baseAmount, String taxCode, boolean active) {
        UUID tenantId = tenantContext.current();
        if (!repo.chargeCodesIn(tenantId, List.of(code)).isEmpty()) throw duplicate(code);
        try {
            return UUID.fromString(setup.addCharge(tenantId, actorId, new ChargeHeadWriteRequest(code, name, category, baseAmount,
                    null, null, taxCode, null, false, active, clock.today(tenantId), null, 0)).id());
        } catch (DuplicateKeyException e) { // added by another request since the check above
            throw duplicate(code);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void update(UUID actorId, UUID chargeId, String name, BigDecimal baseAmount, String taxCode, boolean active) {
        UUID tenantId = tenantContext.current();
        ChargeHeadRow c = repo.findCharge(tenantId, chargeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "charge-not-found", "Charge not found", "Charge not found"));
        setup.updateCharge(tenantId, actorId, chargeId, new ChargeHeadWriteRequest(c.code(), name, c.category(), baseAmount,
                c.followUpAmount(), c.emergencyAmount(), taxCode, c.taxRatePercent(), c.doctorOverride(), active,
                c.effectiveFrom(), c.effectiveTo(), c.displayOrder()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Charge> find(Collection<UUID> ids) {
        Map<UUID, Charge> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        for (ChargeHeadRow c : repo.findCharges(tenantContext.current(), ids)) {
            out.put(c.id(), new Charge(c.id(), c.code(), c.name(), c.baseAmount(), c.taxCode(), c.active()));
        }
        return out;
    }

    private static ApiException duplicate(String code) {
        return new ApiException(HttpStatus.CONFLICT, "duplicate-code", "Code exists",
                "The price list already has the code " + code + ".", Map.of("code", "DUPLICATE_CODE", "chargeCode", code));
    }
}

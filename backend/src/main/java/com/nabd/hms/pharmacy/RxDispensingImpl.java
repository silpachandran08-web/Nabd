package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.pharmacy.api.RxDispensing;
import com.nabd.hms.pharmacy.api.StockCommands;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** PHL-018: checkout dispense with the "dispense anyway" shortfall path. The stock write itself stays in StockCommandsImpl. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class RxDispensingImpl implements RxDispensing {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final StockCommands stock;
    private final PharmacyRepository items;
    private final RxDispensingRepository repo;
    private final TenantContext tenant;
    private final AuditService audit;
    private final ActorDirectory actors;

    RxDispensingImpl(StockCommands stock, PharmacyRepository items, RxDispensingRepository repo, TenantContext tenant, AuditService audit,
                     ActorDirectory actors) {
        this.stock = stock;
        this.items = items;
        this.repo = repo;
        this.tenant = tenant;
        this.audit = audit;
        this.actors = actors;
    }

    @Override
    public Issued issue(UUID itemId, int qty, UUID invoiceId, String shortfallReason) {
        if (shortfallReason == null) {
            return new Issued(stock.issue(itemId, qty, invoiceId, "Dispensed at checkout").scheduleClass(), 0);
        }
        if (shortfallReason.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "reason-required", "Reason required",
                    "Give a reason to dispense more than the recorded stock.");
        }
        int have = items.lockItem(tenant.current(), itemId).map(PharmacyModels.PharmacyItemRow::stockQty)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested pharmacy item was not found."));
        int take = Math.min(have, qty);
        String cls = take > 0 ? stock.issue(itemId, take, invoiceId, "Dispensed at checkout").scheduleClass()
                : items.scheduleClassOf(tenant.current(), itemId).orElse(null);
        return new Issued(cls, qty - take);
    }

    @Override
    public int shortfallOf(UUID invoiceId, UUID itemId) {
        return repo.shortfall(tenant.current(), invoiceId, itemId);
    }

    @Override
    public void record(UUID invoiceId, UUID actorId, List<Line> lines) {
        if (lines.isEmpty()) {
            return;
        }
        UUID tenantId = tenant.current();
        List<Map<String, Object>> json = new ArrayList<>();
        for (Line l : lines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chargeCode", l.chargeCode());
            m.put("itemId", l.itemId());
            m.put("qty", l.qty());
            m.put("shortQty", l.shortQty());
            m.put("prescriptionItemId", l.prescriptionItemId());
            json.add(m);
        }
        UUID dispenseId;
        try {
            dispenseId = repo.insertDispense(tenantId, invoiceId, JSON.writeValueAsString(json), actorId);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        List<Map<String, Object>> short_ = new ArrayList<>();
        for (Line l : lines) {
            if (l.shortQty() > 0) {
                repo.insertDiscrepancy(tenantId, l.itemId(), l.shortQty(), l.shortfallReason().trim(), dispenseId, actorId);
                short_.add(Map.of("itemId", l.itemId(), "shortQty", l.shortQty()));
            }
        }
        if (!short_.isEmpty()) { // the reason is free text and stays in stock_discrepancies, not in the audit row
            String[] actor = actors.findActor(tenantId, actorId).map(a -> new String[]{a.name(), a.role()}).orElse(new String[]{"Unknown", "Unknown"});
            audit.record(tenantId, "staff", actorId, actor[0], actor[1], null, "pharmacy.shortfall_dispensed", "dispense", dispenseId, null,
                    Map.of("invoiceId", invoiceId, "lines", short_));
        }
    }
}

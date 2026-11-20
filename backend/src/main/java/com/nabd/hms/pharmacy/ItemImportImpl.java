package com.nabd.hms.pharmacy;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.pharmacy.api.ItemImport;
import com.nabd.hms.pharmacy.dto.PharmacyItemWriteRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** ADM-013: {@link ItemImport} over the same service the pharmacy screen uses. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ItemImportImpl implements ItemImport {

    private final PharmacyRepository repo;
    private final BatchRepository batches;
    private final PharmacyService service;
    private final TenantContext tenant;

    ItemImportImpl(PharmacyRepository repo, BatchRepository batches, PharmacyService service, TenantContext tenant) {
        this.repo = repo;
        this.batches = batches;
        this.service = service;
        this.tenant = tenant;
    }

    @Override
    public Mode mode() {
        PharmacyMode m = PharmacyMode.of(repo.findMode(tenant.current()).orElse("external"));
        return new Mode(m != PharmacyMode.EXTERNAL, m == PharmacyMode.IN_HOUSE);
    }

    @Override
    public Optional<UUID> findByName(String name) {
        return repo.findItemIdByName(tenant.current(), name);
    }

    @Override
    public UUID create(NewItem i, UUID actorId) {
        return UUID.fromString(service.addItem(tenant.current(), actorId, null, new PharmacyItemWriteRequest(i.name(), i.rx(), i.hsnCode(), i.price(),
                i.taxRatePercent(), null, null, null, i.genericName(), i.packSize(), i.unit(), null, null, null, i.mrp(), null, null, null)).id());
    }

    @Override
    public boolean hasBatch(UUID itemId, String batchNo, LocalDate expiryDate) {
        return batches.find(tenant.current(), itemId, batchNo, expiryDate).isPresent();
    }
}

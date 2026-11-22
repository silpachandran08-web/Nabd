package com.nabd.hms.pharmacy;

import com.nabd.hms.pharmacy.api.PharmacyEdition;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
class PharmacyEditionImpl implements PharmacyEdition {

    private final PharmacyRepository repo;

    PharmacyEditionImpl(PharmacyRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional
    public void startInHouse(UUID tenantId) {
        repo.saveSettings(tenantId, "in_house", true, null, "{}", null, null);
    }
}

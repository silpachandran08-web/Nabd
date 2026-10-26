package com.nabd.hms.clinical;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** CLN-020: projector for clinical.prescription.signed.v1 into drug_usage; idempotent per prescriptionId. */
@Service
class DrugUsageService {

    private final DrugUsageRepository repo;
    private final com.nabd.hms.common.TenantContext tenantContext;

    DrugUsageService(DrugUsageRepository repo, com.nabd.hms.common.TenantContext tenantContext) {
        this.repo = repo;
        this.tenantContext = tenantContext;
    }

    @Transactional
    void onSigned(UUID prescriptionId) {
        UUID tenantId = tenantContext.current();
        if (repo.markApplied(tenantId, prescriptionId)) {
            repo.add(tenantId, prescriptionId);
        }
    }
}

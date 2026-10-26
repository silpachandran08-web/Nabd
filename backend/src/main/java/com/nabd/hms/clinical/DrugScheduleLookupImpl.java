package com.nabd.hms.clinical;

import com.nabd.hms.clinical.api.DrugScheduleLookup;
import com.nabd.hms.common.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(propagation = Propagation.MANDATORY)
class DrugScheduleLookupImpl implements DrugScheduleLookup {

    private final DrugMasterRepository drugs;
    private final PrescriptionRepository prescriptions;
    private final TenantContext tenantContext;

    DrugScheduleLookupImpl(DrugMasterRepository drugs, PrescriptionRepository prescriptions, TenantContext tenantContext) {
        this.drugs = drugs;
        this.prescriptions = prescriptions;
        this.tenantContext = tenantContext;
    }

    @Override
    public List<String> scheduleFlags(String drugId) {
        String region = prescriptions.findTenantRegion(tenantContext.current()).map(String::trim).orElse("IN");
        return drugs.find(region, drugId).map(DrugMasterRepository.Drug::schedule).orElse(List.of());
    }
}

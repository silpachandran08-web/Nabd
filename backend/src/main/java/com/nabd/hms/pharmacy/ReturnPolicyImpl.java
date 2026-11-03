package com.nabd.hms.pharmacy;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.pharmacy.api.ReturnPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** PHL-011: the item flags (V2960) and the schedule rules (PHL-002) decide what may be taken back. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class ReturnPolicyImpl implements ReturnPolicy {

    private final PharmacyRepository repo;
    private final TenantContext tenant;

    ReturnPolicyImpl(PharmacyRepository repo, TenantContext tenant) {
        this.repo = repo;
        this.tenant = tenant;
    }

    @Override
    public Optional<String> refusal(UUID itemId, String scheduleClassAsSold) {
        return repo.returnFlags(tenant.current(), itemId, scheduleClassAsSold).map(f -> {
            if (f.register() != null) return f.classLabel() + " item: controlled medicines can't be taken back.";
            if (f.coldChain()) return "Cold-chain item: it can't be taken back once it has left the pharmacy.";
            if (!f.returnable()) return "This item is marked not returnable.";
            return null;
        });
    }
}

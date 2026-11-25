package com.nabd.hms.insurance.internal;

import com.nabd.hms.billing.api.VisitCoverageSource;
import com.nabd.hms.organization.api.Entitlements;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** FIN-036: answers billing's question at checkout. A clinic without the insurance module is never split, whatever rows it has. */
@Component
class BillingCoverageSource implements VisitCoverageSource {

    private final InsuranceRepository repo;
    private final Entitlements entitlements;

    BillingCoverageSource(InsuranceRepository repo, Entitlements entitlements) {
        this.repo = repo;
        this.entitlements = entitlements;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Insured> insured(UUID tenantId, UUID visitId) {
        return repo.findCoverage(tenantId, visitId)
                .filter(c -> "insured".equals(c.mode()) && entitlements.has(InsuranceEntitlementGuard.MODULE))
                .map(c -> new Insured(c.payerId(), c.policyId(), c.copayPercent()));
    }
}

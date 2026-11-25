package com.nabd.hms.billing.api;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * FIN-036: how billing learns at checkout that a visit is insured. Implemented by the insurance module, which owns the coverage
 * decision; billing never reads insurance tables (the same shape as {@link PackageRedemption}). Runs in the caller's transaction.
 */
public interface VisitCoverageSource {

    /** @param patientPercent the co-pay rule: the share of every bill line the patient pays, 0 to 100 with at most 2 decimals */
    record Insured(UUID payerId, UUID policyId, BigDecimal patientPercent) {
    }

    /** Empty for a self-pay visit, a visit with no coverage decision and a clinic that does not accept insurance. */
    Optional<Insured> insured(UUID tenantId, UUID visitId);
}

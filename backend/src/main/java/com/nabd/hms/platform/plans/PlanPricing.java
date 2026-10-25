package com.nabd.hms.platform.plans;

/** ADM-034: the monthly recurring revenue of a subscription from its plan's pricing basis. */
public final class PlanPricing {

    private PlanPricing() {
    }

    /** per_doctor = unit price x active clinicians; per_clinic = the flat price. */
    public static int mrrCents(String pricingBasis, int unitPriceCents, int activeClinicians) {
        if (!"per_clinic".equals(pricingBasis) && !"per_doctor".equals(pricingBasis)) {
            throw new IllegalArgumentException("unknown pricing basis");
        }
        return "per_doctor".equals(pricingBasis) ? Math.multiplyExact(unitPriceCents, Math.max(0, activeClinicians)) : unitPriceCents;
    }
}

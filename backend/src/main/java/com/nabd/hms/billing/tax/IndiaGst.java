package com.nabd.hms.billing.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** FIN-009: intra-state supply = CGST + SGST (half each); inter-state = IGST. */
public final class IndiaGst implements JurisdictionRules {
    @Override
    public List<Part> split(TaxEngine.Line line) {
        BigDecimal rate = line.ratePercent();
        if (line.interState()) return List.of(new Part("IGST", rate));
        BigDecimal half = rate.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        return List.of(new Part("CGST", half), new Part("SGST", rate.subtract(half)));
    }

    @Override
    public boolean billOfSupplyWhenUnregistered() {
        return true;
    }
}

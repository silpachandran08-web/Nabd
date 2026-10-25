package com.nabd.hms.billing.tax;

import java.math.BigDecimal;
import java.util.List;

/** FIN-008: which tax components a taxable line carries; the components' rates add up to the line rate. */
public interface JurisdictionRules {

    record Part(String type, BigDecimal ratePercent) {
    }

    List<Part> split(TaxEngine.Line line);

    /** GST: a clinic without a registration issues a Bill of Supply with no tax lines. */
    default boolean billOfSupplyWhenUnregistered() {
        return false;
    }
}

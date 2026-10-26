package com.nabd.hms.billing.tax;

import java.util.List;

/** VAT regimes (Gulf VAT): one VAT component at the line's rate. A rate of 0 (Qatar, no VAT) yields a zero component. */
public final class FlatVat implements JurisdictionRules {
    @Override
    public List<Part> split(TaxEngine.Line line) {
        return List.of(new Part("VAT", line.ratePercent()));
    }
}

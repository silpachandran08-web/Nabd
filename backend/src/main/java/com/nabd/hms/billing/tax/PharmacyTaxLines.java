package com.nabd.hms.billing.tax;

import com.nabd.hms.pharmacy.api.PharmacyTaxInputs;

import java.math.BigDecimal;

/**
 * PHL-021: folds the inputs pharmacy supplies for a stocked item (zero-rated flag, tax-inclusive price) into the line's resolved
 * tax class. Pure; the tax itself is still computed only by {@link TaxEngine}.
 */
public final class PharmacyTaxLines {

    /** The reason printed under a zero-rated medicine line. */
    public static final String ZERO_RATED_NOTE = "Zero-rated medicine, VAT 0%";

    private PharmacyTaxLines() {
    }

    static TaxService.LineMeta apply(TaxService.LineMeta m, PharmacyTaxInputs.Inputs in) {
        if (in == null) return m;
        if (in.zeroRated()) return new TaxService.LineMeta(BigDecimal.ZERO, "NIL", null, false, false, m.sacHsn());
        if (in.priceInclusive() != null && "TAXABLE".equals(m.category())) {
            return new TaxService.LineMeta(m.rate(), m.category(), m.ruleId(), in.priceInclusive(), m.interState(), m.sacHsn());
        }
        return m;
    }

    /** Public entry for tests and tools: same fold on the pieces of a resolved line. */
    public static TaxEngine.Line engineLine(String key, long grossMinor, String category, BigDecimal rate, boolean inclusive, boolean interState,
                                            PharmacyTaxInputs.Inputs in) {
        var m = apply(new TaxService.LineMeta(rate, category, null, inclusive, interState, null), in);
        return new TaxEngine.Line(key, grossMinor, TaxEngine.Category.valueOf(m.category()), m.rate(), true, m.inclusive(), m.interState());
    }
}

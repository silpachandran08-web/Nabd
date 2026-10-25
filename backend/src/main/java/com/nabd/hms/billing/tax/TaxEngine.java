package com.nabd.hms.billing.tax;

import java.util.List;

/**
 * FIN-008: the tax port. Pure and deterministic (no I/O, no clock): a cart goes in, per-line taxable value, tax components and
 * totals come out, all in minor units. Jurisdiction adapters ({@link IndiaGst}, {@link FlatVat}) only decide which components a line carries.
 */
public interface TaxEngine {

    enum Category { TAXABLE, EXEMPT, NIL }

    /** @param grossMinor quantity x unit price (tax-exclusive unless {@code inclusive}); {@code interState}: supply needs IGST instead of CGST+SGST */
    record Line(String key, long grossMinor, Category category, java.math.BigDecimal ratePercent, boolean discountable,
                boolean inclusive, boolean interState) {
    }

    /** @param wholeUnitRoundOff round the total to a whole currency unit with a round-off line; @param billOfSupply no tax lines at all */
    record Cart(int minorUnits, boolean wholeUnitRoundOff, boolean billOfSupply, long invoiceDiscountMinor, List<Line> lines) {
    }

    record Component(String type, java.math.BigDecimal ratePercent, long taxableMinor, long amountMinor) {
    }

    record LineResult(String key, long discountMinor, long taxableMinor, List<Component> taxes) {
        public long taxMinor() {
            return taxes.stream().mapToLong(Component::amountMinor).sum();
        }
    }

    /** subtotal = SUM(taxable + allocated discount), so subtotal - discount + tax = total - roundOff always holds. */
    record Result(List<LineResult> lines, long subtotalMinor, long discountMinor, long taxMinor, long roundOffMinor, long totalMinor) {
    }

    Result compute(Cart cart);
}

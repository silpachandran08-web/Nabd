package com.nabd.hms.packages;

import com.nabd.hms.billing.tax.RuleBasedTaxEngine;
import com.nabd.hms.organization.api.RegionRules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * FIN-025: the package arithmetic, pure and in minor units (no I/O, no clock): the bundle price split over the components, and the
 * refund after partial use. The tax treatment of a mixed bundle is NEEDS TAX OPINION (CEO-07 GST, CEO-08 VAT point of supply).
 */
final class PackageSplit {

    private PackageSplit() {
    }

    /** CEO-07: where the tax regime is GST, splitting a bundle's tax per component needs the written GST opinion on record first. */
    static boolean needsGstOpinion(RegionRules.Region region) {
        return "GST".equals(region.taxRegime());
    }

    private static long share(long amount, long num, long den) {
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(num)).divide(BigDecimal.valueOf(den), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * The bundle price split pro rata over the components' list values (bundle x list value / sum of list values), each share rounded
     * half-up to the minor unit; the last component that has a list value takes the rounding remainder, so the shares add up to the
     * bundle price exactly. A package above its price floor never gets a negative remainder.
     */
    static long[] allocate(long bundleMinor, long[] listValueMinor) {
        int n = listValueMinor.length;
        long total = java.util.Arrays.stream(listValueMinor).sum();
        long[] out = new long[n];
        int last = n - 1;
        for (int i = n - 1; i >= 0; i--) {
            if (listValueMinor[i] > 0) { last = i; break; }
        }
        long given = 0;
        for (int i = 0; i < n; i++) {
            if (i == last || total == 0) continue;
            out[i] = share(bundleMinor, listValueMinor[i], total);
            given += out[i];
        }
        out[last] = bundleMinor - given;
        return out;
    }

    /** A sold component: what its invoice line carries and how much of it was used. */
    record Sold(String name, int quantityConsumed, long unitListMinor, long taxableMinor, long taxMinor) {
    }

    record RefundLine(String component, int usedQty, long usedValueMinor, long refundTaxableMinor, long refundTaxMinor) {
    }

    record Refund(List<RefundLine> lines, long paidMinor, long usedMinor, long refundMinor, long owedMinor) {
    }

    /**
     * PKG-08 / CEO-11: used sessions are valued at the full single-session list price, the rest of each line is refunded together with
     * the tax that was invoiced on that part, and there is no processing fee. A line used beyond its allocated share takes the
     * excess off the other lines' refunds pro rata (largest remainder), so no line gives back more than was invoiced on it or less
     * than nothing. {@code inclusive}: list prices and allocated shares contain the tax (a VAT-inclusive bundle).
     */
    static Refund refund(boolean inclusive, List<Sold> items) {
        int n = items.size();
        long[] basis = new long[n], usedValue = new long[n], left = new long[n];
        long paid = 0, used = 0, basisTotal = 0, excess = 0, leftTotal = 0;
        for (int i = 0; i < n; i++) {
            Sold s = items.get(i);
            basis[i] = s.taxableMinor() + (inclusive ? s.taxMinor() : 0);
            usedValue[i] = s.quantityConsumed() * s.unitListMinor();
            left[i] = Math.max(basis[i] - usedValue[i], 0);
            excess += Math.max(usedValue[i] - basis[i], 0);
            paid += s.taxableMinor() + s.taxMinor();
            used += usedValue[i];
            basisTotal += basis[i];
            leftTotal += left[i];
        }
        long[] cut = RuleBasedTaxEngine.allocate(Math.min(excess, leftTotal), left);
        List<RefundLine> lines = new ArrayList<>();
        long refund = 0;
        for (int i = 0; i < n; i++) {
            Sold s = items.get(i);
            long back = left[i] - cut[i];
            long tax = back == basis[i] ? s.taxMinor() : basis[i] == 0 ? 0 : share(back, s.taxMinor(), basis[i]);
            long taxable = inclusive ? back - tax : back;
            lines.add(new RefundLine(s.name(), s.quantityConsumed(), usedValue[i], taxable, tax));
            refund += taxable + tax;
        }
        return new Refund(lines, paid, used, refund, Math.max(used - basisTotal, 0));
    }
}

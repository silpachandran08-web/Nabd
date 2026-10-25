package com.nabd.hms.billing.tax;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * FIN-008: invoice discount allocated pro rata to discountable lines by largest remainder (so the parts add up to the discount to
 * the minor unit), tax per line on the discounted value, each component rounded half-up to the minor unit, optional whole-unit
 * round-off on the total.
 */
public final class RuleBasedTaxEngine implements TaxEngine {

    private final JurisdictionRules rules;

    public RuleBasedTaxEngine(JurisdictionRules rules) {
        this.rules = rules;
    }

    /** Largest-remainder split of {@code amount} over {@code weights}; ties go to the earlier index. Sum of parts == amount. */
    public static long[] allocate(long amount, long[] weights) {
        long[] out = new long[weights.length];
        BigInteger total = BigInteger.valueOf(java.util.Arrays.stream(weights).sum());
        if (amount == 0 || total.signum() == 0) return out;
        BigInteger[] rem = new BigInteger[weights.length];
        long given = 0;
        for (int i = 0; i < weights.length; i++) {
            BigInteger[] qr = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(weights[i])).divideAndRemainder(total);
            out[i] = qr[0].longValueExact();
            rem[i] = qr[1];
            given += out[i];
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) if (weights[i] > 0) order.add(i);
        order.sort((a, b) -> rem[b].compareTo(rem[a]));
        for (int k = 0; k < amount - given; k++) out[order.get(k)]++;
        return out;
    }

    private static long pct(long minor, BigDecimal ratePercent) {
        return BigDecimal.valueOf(minor).multiply(ratePercent).movePointLeft(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    @Override
    public Result compute(Cart cart) {
        List<Line> lines = cart.lines();
        long[] weights = new long[lines.size()];
        long discountable = 0;
        for (int i = 0; i < weights.length; i++) {
            if (lines.get(i).discountable()) { weights[i] = lines.get(i).grossMinor(); discountable += weights[i]; }
        }
        if (cart.invoiceDiscountMinor() < 0 || cart.invoiceDiscountMinor() > discountable) {
            throw new IllegalArgumentException("discount must be between 0 and the discountable value");
        }
        long[] alloc = allocate(cart.invoiceDiscountMinor(), weights);

        List<LineResult> out = new ArrayList<>();
        long subtotal = 0, tax = 0, raw = 0;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            long net = l.grossMinor() - alloc[i];
            long taxable = net;
            List<Component> comps = new ArrayList<>();
            if (!cart.billOfSupply() && l.category() == Category.TAXABLE && l.ratePercent().signum() > 0) {
                List<JurisdictionRules.Part> parts = rules.split(l);
                if (l.inclusive()) { // net already contains the tax: back it out, the last component takes the rounding remainder
                    taxable = BigDecimal.valueOf(net).multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(100).add(l.ratePercent()), 0, RoundingMode.HALF_UP).longValueExact();
                    long left = net - taxable;
                    for (int p = 0; p < parts.size(); p++) {
                        long amt = p == parts.size() - 1 ? left : Math.min(left, pct(taxable, parts.get(p).ratePercent()));
                        left -= amt;
                        comps.add(new Component(parts.get(p).type(), parts.get(p).ratePercent(), taxable, amt));
                    }
                } else {
                    for (var p : parts) comps.add(new Component(p.type(), p.ratePercent(), taxable, pct(taxable, p.ratePercent())));
                }
            }
            LineResult lr = new LineResult(l.key(), alloc[i], taxable, comps);
            out.add(lr);
            subtotal += taxable + alloc[i];
            tax += lr.taxMinor();
            raw += taxable + lr.taxMinor();
        }
        long total = raw;
        if (cart.wholeUnitRoundOff()) {
            long unit = BigInteger.TEN.pow(cart.minorUnits()).longValueExact();
            total = Math.floorDiv(raw + unit / 2, unit) * unit; // half-up
        }
        return new Result(out, subtotal, cart.invoiceDiscountMinor(), tax, total - raw, total);
    }
}

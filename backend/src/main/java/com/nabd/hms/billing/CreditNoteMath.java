package com.nabd.hms.billing;

import com.nabd.hms.billing.tax.RuleBasedTaxEngine;
import com.nabd.hms.billing.tax.TaxEngine.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * FIN-013: pure credit-note arithmetic in minor units (no I/O, no clock), so the FIN-010 golden pack can run it. A line is reversed
 * against what is still <em>remaining</em> of it (original minus earlier credit notes): the last unit, or the last paise, takes the
 * rest, so repeated credit notes add up to the invoice line exactly.
 */
public final class CreditNoteMath {

    private CreditNoteMath() {
    }

    /** What is left to reverse on one invoice line; {@code taxes} are the remaining component amounts (type and rate as invoiced). */
    public record Remaining(String lineKey, int quantity, long taxableMinor, List<Component> taxes) {
        long taxMinor() {
            return taxes.stream().mapToLong(Component::amountMinor).sum();
        }

        long grossMinor() {
            return taxableMinor + taxMinor();
        }
    }

    /** One reversed line of a credit note. {@code quantity} is 0 for a goodwill amount refund. */
    public record Reversal(String lineKey, int quantity, long taxableMinor, List<Component> taxes) {
        public long taxMinor() {
            return taxes.stream().mapToLong(Component::amountMinor).sum();
        }
    }

    /** {@code roundOffMinor} is the share of the invoice round-off returned with the note (only the one that completes the invoice). */
    public record Note(List<Reversal> lines, long roundOffMinor) {
        public long taxableMinor() {
            return lines.stream().mapToLong(Reversal::taxableMinor).sum();
        }

        public long taxMinor() {
            return lines.stream().mapToLong(Reversal::taxMinor).sum();
        }

        public long totalMinor() {
            return taxableMinor() + taxMinor() + roundOffMinor;
        }
    }

    /** What is left of a line after a reversal (earlier credit notes are applied one by one with this). */
    public static Remaining after(Remaining r, Reversal rev) {
        List<Component> left = new ArrayList<>();
        for (Component c : r.taxes()) {
            Component taken = rev.taxes().stream().filter(t -> t.type().equals(c.type())).findFirst().orElse(null);
            left.add(taken == null ? c : new Component(c.type(), c.ratePercent(), c.taxableMinor() - taken.taxableMinor(), c.amountMinor() - taken.amountMinor()));
        }
        return new Remaining(r.lineKey(), r.quantity() - rev.quantity(), r.taxableMinor() - rev.taxableMinor(), left);
    }

    private static long share(long amount, long num, long den) {
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(num)).divide(BigDecimal.valueOf(den), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Reverse {@code qty} of the {@code remaining.quantity()} units still open on a line (1 <= qty <= remaining). */
    public static Reversal byQuantity(Remaining r, int qty) {
        if (qty < 1 || qty > r.quantity()) throw new IllegalArgumentException("quantity must be between 1 and " + r.quantity());
        if (qty == r.quantity()) return new Reversal(r.lineKey(), qty, r.taxableMinor(), r.taxes());
        List<Component> comps = new ArrayList<>();
        for (Component c : r.taxes()) {
            comps.add(new Component(c.type(), c.ratePercent(), share(c.taxableMinor(), qty, r.quantity()), share(c.amountMinor(), qty, r.quantity())));
        }
        return new Reversal(r.lineKey(), qty, share(r.taxableMinor(), qty, r.quantity()), comps);
    }

    /**
     * Goodwill refund of {@code amountMinor} (tax-inclusive) spread over the open lines by their remaining value, largest remainder;
     * inside a line the tax part is the same proportion as invoiced. Returns only lines that take something.
     */
    public static Note byAmount(List<Remaining> open, long amountMinor, long remainingRoundOffMinor) {
        long gross = open.stream().mapToLong(Remaining::grossMinor).sum();
        long roundOff = 0;
        if (amountMinor == gross + remainingRoundOffMinor) { // the whole rest of the invoice, round-off included
            roundOff = remainingRoundOffMinor;
            amountMinor = gross;
        }
        if (amountMinor < 1 || amountMinor > gross) throw new IllegalArgumentException("amount exceeds what is left to refund");
        long[] w = open.stream().mapToLong(Remaining::grossMinor).toArray();
        long[] part = RuleBasedTaxEngine.allocate(amountMinor, w);
        List<Reversal> out = new ArrayList<>();
        for (int i = 0; i < open.size(); i++) {
            if (part[i] == 0) continue;
            Remaining r = open.get(i);
            if (part[i] == w[i]) {
                out.add(new Reversal(r.lineKey(), 0, r.taxableMinor(), r.taxes()));
                continue;
            }
            long taxable = share(r.taxableMinor(), part[i], w[i]);
            long[] cw = r.taxes().stream().mapToLong(Component::amountMinor).toArray();
            long[] ca = RuleBasedTaxEngine.allocate(part[i] - taxable, cw);
            List<Component> comps = new ArrayList<>();
            for (int k = 0; k < cw.length; k++) {
                Component c = r.taxes().get(k);
                comps.add(new Component(c.type(), c.ratePercent(), taxable, ca[k]));
            }
            // no tax components (exempt line): the whole share is taxable value
            out.add(new Reversal(r.lineKey(), 0, cw.length == 0 ? part[i] : taxable, comps));
        }
        return new Note(out, roundOff);
    }

    /** Wraps quantity reversals; when nothing at all is left on the invoice afterwards, the remaining round-off goes back too. */
    public static Note ofQuantities(List<Remaining> open, List<Reversal> lines, long remainingRoundOffMinor) {
        boolean completes = open.stream().allMatch(r -> {
            long taxable = lines.stream().filter(l -> l.lineKey().equals(r.lineKey())).mapToLong(Reversal::taxableMinor).sum();
            long tax = lines.stream().filter(l -> l.lineKey().equals(r.lineKey())).mapToLong(Reversal::taxMinor).sum();
            return taxable == r.taxableMinor() && tax == r.taxMinor();
        });
        return new Note(lines, completes ? remainingRoundOffMinor : 0);
    }
}

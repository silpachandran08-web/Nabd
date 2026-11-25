package com.nabd.hms.billing.gst;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * FIN-011: the GSTR-1 sections of one month, built from stored invoice lines and credit-note lines. Pure (no I/O, no clock) and in
 * minor units only, so the golden test runs it without a database. Nothing is recomputed: every amount is the one stored at issue.
 * <ul>
 * <li>B2B (table 4): invoice-wise and rate-wise, taxable lines of tax invoices that carry a buyer GSTIN. Place of supply is the
 *     buyer's state for a line billed with IGST and the clinic's state otherwise (what the tax engine charged, FIN-009).</li>
 * <li>B2CS (table 7): by place of supply and rate, taxable lines without a buyer GSTIN, net of their credit notes.</li>
 * <li>CDNR (table 9B): note-wise and rate-wise, credit notes against invoices with a buyer GSTIN (taxable lines).</li>
 * <li>EXEMP (table 8): nil-rated, exempt and non-GST value, net of credit notes.</li>
 * <li>HSN (table 12): by HSN/SAC and rate, every line, net of credit notes.</li>
 * </ul>
 * The reconciliation compares the sections with the invoice and credit-note registers (the document headers), to the paisa.
 * NEEDS TAX CHECK: which table a supply belongs to and the netting rules are the common reading of GSTR-1, not yet confirmed by a CA.
 */
public final class Gstr1 {

    private Gstr1() {
    }

    /**
     * One invoice line, or one reversed line of a credit note, with its stored tax components.
     * @param docValueMinor the document total (invoice total or credit-note total), repeated on each of its lines
     * @param originalNumber,originalDate credit notes only: the invoice the note is against
     * @param category TAXABLE, EXEMPT or NIL as stored on the invoice line
     */
    public record Line(String docNumber, LocalDate docDate, long docValueMinor, String buyerGstin, boolean billOfSupply,
                       String originalNumber, LocalDate originalDate, String hsn, int quantity, String category, BigDecimal ratePercent,
                       boolean interState, long taxableMinor, long igstMinor, long cgstMinor, long sgstMinor, long cessMinor) {
        Amounts amounts() {
            return new Amounts(taxableMinor, igstMinor, cgstMinor, sgstMinor, cessMinor);
        }
    }

    /** The registers: sums of the document headers, read independently of the lines. */
    public record Register(int invoices, long taxableMinor, long taxMinor, long roundOffMinor, long totalMinor,
                           int creditNotes, long creditTaxableMinor, long creditTaxMinor, long creditRoundOffMinor, long creditTotalMinor) {
    }

    public record Amounts(long taxableMinor, long igstMinor, long cgstMinor, long sgstMinor, long cessMinor) {
        static final Amounts ZERO = new Amounts(0, 0, 0, 0, 0);

        public long taxMinor() {
            return igstMinor + cgstMinor + sgstMinor + cessMinor;
        }

        Amounts plus(Amounts o) {
            return new Amounts(taxableMinor + o.taxableMinor, igstMinor + o.igstMinor, cgstMinor + o.cgstMinor, sgstMinor + o.sgstMinor, cessMinor + o.cessMinor);
        }

        Amounts minus(Amounts o) {
            return new Amounts(taxableMinor - o.taxableMinor, igstMinor - o.igstMinor, cgstMinor - o.cgstMinor, sgstMinor - o.sgstMinor, cessMinor - o.cessMinor);
        }
    }

    public record B2b(String buyerGstin, String invoiceNumber, LocalDate invoiceDate, long invoiceValueMinor, String placeOfSupply,
                      BigDecimal ratePercent, Amounts amounts) {
    }

    public record B2cs(String placeOfSupply, boolean interState, BigDecimal ratePercent, Amounts amounts) {
    }

    public record Cdnr(String buyerGstin, String noteNumber, LocalDate noteDate, String invoiceNumber, LocalDate invoiceDate, long noteValueMinor,
                       String placeOfSupply, BigDecimal ratePercent, Amounts amounts) {
    }

    public record Hsn(String hsn, BigDecimal ratePercent, long quantity, Amounts amounts) {
    }

    public record Exempt(boolean interState, boolean registeredBuyer, long nilRatedMinor, long exemptedMinor, long nonGstMinor) {
        long totalMinor() {
            return nilRatedMinor + exemptedMinor + nonGstMinor;
        }
    }

    /**
     * Net = invoices minus credit notes of the month. sections = B2B - CDNR + B2CS (+ EXEMP for the taxable value).
     * Every difference is zero when the export reconciles; registerDifference / creditRegisterDifference check that each register
     * adds up by itself (taxable + tax + round-off = total).
     */
    public record Reconciliation(Register register, long netTaxableMinor, long netTaxMinor, long sectionsTaxableMinor, long sectionsTaxMinor,
                                 long hsnTaxableMinor, long hsnTaxMinor, long taxableDifferenceMinor, long taxDifferenceMinor,
                                 long hsnTaxableDifferenceMinor, long hsnTaxDifferenceMinor, long registerDifferenceMinor,
                                 long creditRegisterDifferenceMinor, boolean reconciled) {
    }

    public record Document(String gstin, YearMonth period, List<B2b> b2b, List<B2cs> b2cs, List<Cdnr> cdnr, List<Hsn> hsn, List<Exempt> exempt,
                           Reconciliation reconciliation) {
    }

    private enum Kind { TAXABLE, NIL, EXEMPT, NON_GST }

    /** A bill of supply charges no tax at all, so none of its lines is a taxable supply of the return. */
    private static Kind kind(Line l) {
        boolean exempt = "EXEMPT".equals(l.category()), nil = "NIL".equals(l.category());
        if (l.billOfSupply()) return exempt ? Kind.EXEMPT : nil ? Kind.NIL : Kind.NON_GST;
        return exempt ? Kind.EXEMPT : nil ? Kind.NIL : Kind.TAXABLE;
    }

    private static boolean registered(Line l) {
        return l.buyerGstin() != null && !l.buyerGstin().isBlank();
    }

    private static String state(String gstin) {
        return gstin != null && gstin.length() >= 2 ? gstin.substring(0, 2) : "";
    }

    private static BigDecimal rate(Line l) {
        return (l.ratePercent() == null ? BigDecimal.ZERO : l.ratePercent()).setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    /** {@code invoiceLines}: lines of the month's non-void invoices in register order; {@code creditLines}: lines of the month's credit notes. */
    public static Document build(String sellerGstin, YearMonth period, List<Line> invoiceLines, List<Line> creditLines, Register register) {
        String home = state(sellerGstin);
        Map<String, B2b> b2b = new LinkedHashMap<>();
        Map<String, B2cs> b2cs = new TreeMap<>();
        Map<String, Cdnr> cdnr = new LinkedHashMap<>();
        Map<String, Hsn> hsn = new TreeMap<>();
        Map<String, long[]> exempt = new TreeMap<>(); // key -> {nil, exempt, nonGst}

        for (int pass = 0; pass < 2; pass++) {
            boolean credit = pass == 1;
            for (Line l : credit ? creditLines : invoiceLines) {
                Amounts a = l.amounts();
                BigDecimal rate = rate(l);
                // the tax engine's decision as stored: IGST lines go to the buyer's state, everything else is supplied at home
                String pos = l.interState() && registered(l) ? state(l.buyerGstin()) : home;
                Kind kind = kind(l);

                String hsnCode = l.hsn() == null || l.hsn().isBlank() ? "NA" : l.hsn().trim();
                hsn.merge(hsnCode + "|" + rate, new Hsn(hsnCode, rate, credit ? -l.quantity() : l.quantity(), credit ? Amounts.ZERO.minus(a) : a),
                        (x, y) -> new Hsn(x.hsn(), x.ratePercent(), x.quantity() + y.quantity(), x.amounts().plus(y.amounts())));

                if (kind != Kind.TAXABLE) {
                    long[] e = exempt.computeIfAbsent((l.interState() ? "1" : "0") + (registered(l) ? "1" : "0"), k -> new long[3]);
                    // value only: a tax amount on such a line (there should be none) stays out and shows up as a tax difference below
                    e[kind == Kind.NIL ? 0 : kind == Kind.EXEMPT ? 1 : 2] += (credit ? -1 : 1) * a.taxableMinor();
                } else if (registered(l) && !credit) {
                    b2b.merge(l.docNumber() + "|" + pos + "|" + rate, new B2b(l.buyerGstin(), l.docNumber(), l.docDate(), l.docValueMinor(), pos, rate, a),
                            (x, y) -> new B2b(x.buyerGstin(), x.invoiceNumber(), x.invoiceDate(), x.invoiceValueMinor(), x.placeOfSupply(), x.ratePercent(), x.amounts().plus(y.amounts())));
                } else if (registered(l)) {
                    cdnr.merge(l.docNumber() + "|" + pos + "|" + rate, new Cdnr(l.buyerGstin(), l.docNumber(), l.docDate(), l.originalNumber(), l.originalDate(), l.docValueMinor(), pos, rate, a),
                            (x, y) -> new Cdnr(x.buyerGstin(), x.noteNumber(), x.noteDate(), x.invoiceNumber(), x.invoiceDate(), x.noteValueMinor(), x.placeOfSupply(), x.ratePercent(), x.amounts().plus(y.amounts())));
                } else {
                    b2cs.merge(pos + "|" + (l.interState() ? "1" : "0") + "|" + rate, new B2cs(pos, l.interState(), rate, credit ? Amounts.ZERO.minus(a) : a),
                            (x, y) -> new B2cs(x.placeOfSupply(), x.interState(), x.ratePercent(), x.amounts().plus(y.amounts())));
                }
            }
        }

        List<Exempt> exemptRows = new ArrayList<>();
        exempt.forEach((k, v) -> exemptRows.add(new Exempt(k.charAt(0) == '1', k.charAt(1) == '1', v[0], v[1], v[2])));

        long b2bTaxable = b2b.values().stream().mapToLong(r -> r.amounts().taxableMinor()).sum();
        long b2bTax = b2b.values().stream().mapToLong(r -> r.amounts().taxMinor()).sum();
        long b2csTaxable = b2cs.values().stream().mapToLong(r -> r.amounts().taxableMinor()).sum();
        long b2csTax = b2cs.values().stream().mapToLong(r -> r.amounts().taxMinor()).sum();
        long cdnrTaxable = cdnr.values().stream().mapToLong(r -> r.amounts().taxableMinor()).sum();
        long cdnrTax = cdnr.values().stream().mapToLong(r -> r.amounts().taxMinor()).sum();
        long exemptValue = exemptRows.stream().mapToLong(Exempt::totalMinor).sum();
        long hsnTaxable = hsn.values().stream().mapToLong(r -> r.amounts().taxableMinor()).sum();
        long hsnTax = hsn.values().stream().mapToLong(r -> r.amounts().taxMinor()).sum();

        long netTaxable = register.taxableMinor() - register.creditTaxableMinor();
        long netTax = register.taxMinor() - register.creditTaxMinor();
        long sectionsTaxable = b2bTaxable - cdnrTaxable + b2csTaxable + exemptValue;
        long sectionsTax = b2bTax - cdnrTax + b2csTax;
        long registerDiff = register.totalMinor() - (register.taxableMinor() + register.taxMinor() + register.roundOffMinor());
        long creditDiff = register.creditTotalMinor() - (register.creditTaxableMinor() + register.creditTaxMinor() + register.creditRoundOffMinor());
        long[] diffs = {sectionsTaxable - netTaxable, sectionsTax - netTax, hsnTaxable - netTaxable, hsnTax - netTax, registerDiff, creditDiff};
        boolean ok = java.util.Arrays.stream(diffs).allMatch(d -> d == 0);
        Reconciliation rec = new Reconciliation(register, netTaxable, netTax, sectionsTaxable, sectionsTax, hsnTaxable, hsnTax,
                diffs[0], diffs[1], diffs[2], diffs[3], diffs[4], diffs[5], ok);
        return new Document(sellerGstin, period, List.copyOf(b2b.values()), List.copyOf(b2cs.values()), List.copyOf(cdnr.values()),
                List.copyOf(hsn.values()), exemptRows, rec);
    }
}

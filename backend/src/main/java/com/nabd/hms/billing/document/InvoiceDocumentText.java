package com.nabd.hms.billing.document;

import com.nabd.hms.billing.dto.InvoiceResponse;
import com.nabd.hms.billing.dto.LineItemResponse;
import com.nabd.hms.billing.dto.PaymentResponse;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** FIN-012: the text of an invoice or receipt for a given column width (90 for A4, 48 for the 80 mm roll). Pure, so the golden test needs no database. */
final class InvoiceDocumentText {

    record Supplier(String legalName, String gstin, String stateCode, ZoneId zone) {
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", java.util.Locale.ENGLISH);

    private InvoiceDocumentText() {
    }

    static List<String> build(InvoiceResponse inv, Supplier s, String kind, String receiptNo, int cols, List<String> sacHsn) {
        boolean thermal = cols <= 48;
        boolean receipt = "receipt".equals(kind);
        String rule = "-".repeat(cols);
        List<String> out = new ArrayList<>();
        String title = receipt ? "PAYMENT RECEIPT" : inv.documentTitle() == null ? "Invoice" : inv.documentTitle();
        out.addAll(center(s.legalName(), cols));
        out.add(s.gstin() == null ? "GSTIN: not registered" : "GSTIN: " + s.gstin() + "  State code: " + s.stateCode());
        out.addAll(center(title.toUpperCase(java.util.Locale.ROOT), cols));
        out.add(rule);
        out.add("Invoice No: " + inv.invoiceNumber());
        if (receipt && receiptNo != null) out.add("Receipt No: " + receiptNo);
        out.add("Date: " + DATE.format(inv.createdAt().atZone(s.zone())));
        out.add("Patient: " + inv.patientName());
        String pos = inv.buyerGstin() != null && inv.buyerGstin().length() >= 2 ? inv.buyerGstin().substring(0, 2) : s.stateCode();
        if (inv.buyerGstin() != null) out.add("Buyer GSTIN: " + inv.buyerGstin());
        if (pos != null) out.add("Place of supply: " + pos);
        out.add(rule);
        if (!receipt) {
            lines(inv, thermal, cols, out, sacHsn);
            out.add(rule);
            row(out, "Subtotal", money(inv.subtotal()), cols);
            if (inv.discount().signum() != 0) row(out, "Discount", "-" + money(inv.discount()), cols);
            taxTotals(inv, out, cols);
            if (inv.roundOff().signum() != 0) row(out, "Round off", money(inv.roundOff()), cols);
            row(out, "TOTAL " + inv.currency(), money(inv.total()), cols);
            if ("INR".equals(inv.currency())) out.addAll(wrap("In words: " + Words.indianRupees(inv.total()), cols));
            out.add(rule);
        }
        out.add("Payments:");
        for (PaymentResponse p : inv.payments()) row(out, "  " + p.method(), money(p.amount()), cols);
        if (inv.payments().isEmpty()) out.add("  none yet");
        row(out, "Paid", money(inv.paid()), cols);
        row(out, "Balance due", money(inv.balanceDue()), cols);
        out.add(rule);
        out.add("Thank you.");
        List<String> fitted = new ArrayList<>();
        for (String l : out) { if (l.length() <= cols) fitted.add(l); else fitted.addAll(wrap(l, cols)); }
        return fitted;
    }

    private static void lines(InvoiceResponse inv, boolean thermal, int cols, List<String> out, List<String> sacHsn) {
        if (!thermal) out.add(String.format("%-28s %-8s %3s %10s %10s %6s %10s", "Description", "SAC/HSN", "Qty", "Rate", "Taxable", "Tax%", "Tax"));
        for (int i = 0; i < inv.lineItems().size(); i++) {
            LineItemResponse l = inv.lineItems().get(i);
            String hsn = i < sacHsn.size() && sacHsn.get(i) != null ? sacHsn.get(i) : "-";
            BigDecimal taxable = l.taxableMinor() == null ? l.lineTotal() : BigDecimal.valueOf(l.taxableMinor(), 2);
            BigDecimal tax = BigDecimal.valueOf(l.taxes().stream().mapToLong(LineItemResponse.TaxPart::amountMinor).sum(), 2);
            String parts = l.taxes().isEmpty() ? "" : l.taxes().stream().map(t -> t.type() + " " + t.ratePercent().stripTrailingZeros().toPlainString() + "% " + money(BigDecimal.valueOf(t.amountMinor(), 2))).reduce((a, b) -> a + ", " + b).get();
            if (thermal) {
                out.addAll(wrap(l.chargeName() + " [" + hsn + "]", cols));
                out.add(String.format("%d x %s  Taxable %s", l.quantity(), money(l.unitPrice()), money(taxable)));
                if (!parts.isEmpty()) out.addAll(wrap("  " + parts, cols));
            } else {
                out.add(String.format("%-28.28s %-8.8s %3d %10s %10s %6s %10s", l.chargeName(), hsn, l.quantity(), money(l.unitPrice()), money(taxable),
                        l.taxRatePercent().stripTrailingZeros().toPlainString(), money(tax)));
                if (!parts.isEmpty()) out.add("    " + parts);
            }
        }
    }

    private static void taxTotals(InvoiceResponse inv, List<String> out, int cols) {
        java.util.Map<String, Long> byType = new java.util.TreeMap<>();
        for (LineItemResponse l : inv.lineItems()) for (LineItemResponse.TaxPart t : l.taxes()) byType.merge(t.type(), t.amountMinor(), Long::sum);
        byType.forEach((type, minor) -> row(out, type, money(BigDecimal.valueOf(minor, 2)), cols));
        if (byType.isEmpty()) row(out, "Tax", money(inv.tax()), cols);
    }

    private static void row(List<String> out, String label, String value, int cols) {
        int gap = Math.max(1, cols - label.length() - value.length());
        out.add(label + " ".repeat(gap) + value);
    }

    private static List<String> center(String s, int cols) {
        List<String> r = new ArrayList<>();
        for (String w : wrap(s, cols)) r.add(" ".repeat(Math.max(0, (cols - w.length()) / 2)) + w);
        return r;
    }

    static List<String> wrap(String s, int cols) {
        List<String> r = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
            while (word.length() > cols) { // a token longer than the page (never a GSTIN or total: those are 15 / short) is cut, not dropped
                if (cur.length() > 0) { r.add(cur.toString()); cur.setLength(0); }
                r.add(word.substring(0, cols));
                word = word.substring(cols);
            }
            if (cur.length() > 0 && cur.length() + 1 + word.length() > cols) { r.add(cur.toString()); cur.setLength(0); }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word);
        }
        if (cur.length() > 0) r.add(cur.toString());
        return r;
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}

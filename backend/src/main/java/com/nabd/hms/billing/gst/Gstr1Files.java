package com.nabd.hms.billing.gst;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * FIN-011: the two files of the GSTR-1 export. JSON follows the field names of the GSTN offline-tool file (b2b, b2cs, cdnr, hsn, nil)
 * plus a "reconciliation" block that is ours; the workbook has one sheet per section and a summary sheet first.
 * Amounts leave here as exact decimals with two places (minor units / 100), never as floating point.
 * NEEDS TAX CHECK: column and field layout follow the public GSTR-1 templates as understood here, not a validated upload.
 * shortcut: the workbook writer repeats the zip/XML skeleton of reports.ReportFiles (ADM-047, one sheet, another module's
 * internals); move both to one shared writer when a third export needs it.
 */
final class Gstr1Files {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("MMyyyy");

    private Gstr1Files() {
    }

    static BigDecimal money(long minor) {
        return BigDecimal.valueOf(minor, 2);
    }

    private static Map<String, Object> taxes(Gstr1.Amounts a, BigDecimal rate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rt", rate);
        m.put("txval", money(a.taxableMinor()));
        m.put("iamt", money(a.igstMinor()));
        m.put("camt", money(a.cgstMinor()));
        m.put("samt", money(a.sgstMinor()));
        m.put("csamt", money(a.cessMinor()));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> doc) {
        return (List<Map<String, Object>>) doc.computeIfAbsent("itms", k -> new ArrayList<Map<String, Object>>());
    }

    private static void addItem(Map<String, Object> doc, Gstr1.Amounts a, BigDecimal rate) {
        List<Map<String, Object>> itms = items(doc);
        Map<String, Object> it = new LinkedHashMap<>();
        it.put("num", itms.size() + 1);
        it.put("itm_det", taxes(a, rate));
        itms.add(it);
    }

    /** The export as a JSON-ready map (insertion ordered). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> json(Gstr1.Document d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gstin", d.gstin());
        out.put("fp", PERIOD.format(d.period().atDay(1)));

        Map<String, Map<String, Object>> byBuyer = new LinkedHashMap<>();
        Map<String, Map<String, Object>> invoices = new LinkedHashMap<>();
        for (Gstr1.B2b r : d.b2b()) {
            Map<String, Object> inv = invoices.computeIfAbsent(r.invoiceNumber(), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("inum", r.invoiceNumber());
                m.put("idt", DAY.format(r.invoiceDate()));
                m.put("val", money(r.invoiceValueMinor()));
                m.put("pos", r.placeOfSupply());
                m.put("rchrg", "N");
                m.put("inv_typ", "R");
                Map<String, Object> buyer = byBuyer.computeIfAbsent(r.buyerGstin(), g -> {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("ctin", g);
                    b.put("inv", new ArrayList<Map<String, Object>>());
                    return b;
                });
                ((List<Map<String, Object>>) buyer.get("inv")).add(m);
                return m;
            });
            addItem(inv, r.amounts(), r.ratePercent());
        }
        out.put("b2b", new ArrayList<>(byBuyer.values()));

        List<Map<String, Object>> b2cs = new ArrayList<>();
        for (Gstr1.B2cs r : d.b2cs()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sply_ty", r.interState() ? "INTER" : "INTRA");
            m.put("pos", r.placeOfSupply());
            m.put("typ", "OE");
            m.putAll(taxes(r.amounts(), r.ratePercent()));
            b2cs.add(m);
        }
        out.put("b2cs", b2cs);

        Map<String, Map<String, Object>> noteBuyers = new LinkedHashMap<>();
        Map<String, Map<String, Object>> notes = new LinkedHashMap<>();
        for (Gstr1.Cdnr r : d.cdnr()) {
            Map<String, Object> nt = notes.computeIfAbsent(r.noteNumber(), k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ntty", "C");
                m.put("nt_num", r.noteNumber());
                m.put("nt_dt", DAY.format(r.noteDate()));
                m.put("inum", r.invoiceNumber());
                m.put("idt", DAY.format(r.invoiceDate()));
                m.put("val", money(r.noteValueMinor()));
                m.put("pos", r.placeOfSupply());
                m.put("rchrg", "N");
                m.put("inv_typ", "R");
                Map<String, Object> buyer = noteBuyers.computeIfAbsent(r.buyerGstin(), g -> {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("ctin", g);
                    b.put("nt", new ArrayList<Map<String, Object>>());
                    return b;
                });
                ((List<Map<String, Object>>) buyer.get("nt")).add(m);
                return m;
            });
            addItem(nt, r.amounts(), r.ratePercent());
        }
        out.put("cdnr", new ArrayList<>(noteBuyers.values()));

        List<Map<String, Object>> hsn = new ArrayList<>();
        for (Gstr1.Hsn r : d.hsn()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("num", hsn.size() + 1);
            m.put("hsn_sc", r.hsn());
            m.put("uqc", uqc(r.hsn()));
            m.put("qty", r.quantity());
            m.putAll(taxes(r.amounts(), r.ratePercent()));
            hsn.add(m);
        }
        out.put("hsn", Map.of("data", hsn));

        List<Map<String, Object>> nil = new ArrayList<>();
        for (Gstr1.Exempt r : d.exempt()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sply_ty", supplyType(r));
            m.put("nil_amt", money(r.nilRatedMinor()));
            m.put("expt_amt", money(r.exemptedMinor()));
            m.put("ngsup_amt", money(r.nonGstMinor()));
            nil.add(m);
        }
        out.put("nil", Map.of("inv", nil));

        Gstr1.Reconciliation c = d.reconciliation();
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("reconciled", c.reconciled());
        rec.put("invoices", c.register().invoices());
        rec.put("creditNotes", c.register().creditNotes());
        for (Object[] row : checks(c)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("register", row[1]);
            m.put("export", row[2]);
            m.put("difference", row[3]);
            rec.put((String) row[4], m);
        }
        rec.put("registerInvoiceTotal", money(c.register().totalMinor()));
        rec.put("registerRoundOff", money(c.register().roundOffMinor()));
        rec.put("registerCreditNoteTotal", money(c.register().creditTotalMinor()));
        out.put("reconciliation", rec);
        return out;
    }

    /** {label, register side, export side, difference, json key}: the rows of the reconciliation, the same in both files. */
    private static List<Object[]> checks(Gstr1.Reconciliation c) {
        Gstr1.Register r = c.register();
        return List.of(
                new Object[]{"Taxable value, net of credit notes: registers vs B2B - CDNR + B2CS + EXEMP", money(c.netTaxableMinor()), money(c.sectionsTaxableMinor()), money(c.taxableDifferenceMinor()), "taxable"},
                new Object[]{"Tax, net of credit notes: registers vs B2B - CDNR + B2CS", money(c.netTaxMinor()), money(c.sectionsTaxMinor()), money(c.taxDifferenceMinor()), "tax"},
                new Object[]{"Taxable value, net of credit notes: registers vs HSN", money(c.netTaxableMinor()), money(c.hsnTaxableMinor()), money(c.hsnTaxableDifferenceMinor()), "hsnTaxable"},
                new Object[]{"Tax, net of credit notes: registers vs HSN", money(c.netTaxMinor()), money(c.hsnTaxMinor()), money(c.hsnTaxDifferenceMinor()), "hsnTax"},
                new Object[]{"Invoice register: total vs taxable + tax + round-off", money(r.totalMinor()), money(r.totalMinor() - c.registerDifferenceMinor()), money(c.registerDifferenceMinor()), "invoiceRegister"},
                new Object[]{"Credit-note register: total vs taxable + tax + round-off", money(r.creditTotalMinor()), money(r.creditTotalMinor() - c.creditRegisterDifferenceMinor()), money(c.creditRegisterDifferenceMinor()), "creditNoteRegister"});
    }

    private static String supplyType(Gstr1.Exempt r) {
        return (r.interState() ? "INTR" : "INTRA") + (r.registeredBuyer() ? "B2B" : "B2C");
    }

    /** SAC (chapter 99) is a service and has no unit; goods are reported as "others" because line units are not stored. */
    private static String uqc(String hsn) {
        return hsn.startsWith("99") || "NA".equals(hsn) ? "NA" : "OTH";
    }

    /** Sheet name -> rows; a Number becomes a numeric cell, everything else text. */
    static Map<String, List<List<Object>>> sheets(Gstr1.Document d) {
        Map<String, List<List<Object>>> out = new LinkedHashMap<>();
        Gstr1.Reconciliation c = d.reconciliation();
        List<List<Object>> sum = new ArrayList<>();
        sum.add(List.of("GSTR-1 export", d.period().toString()));
        sum.add(List.of("GSTIN", d.gstin() == null ? "not registered" : d.gstin()));
        sum.add(List.of("Status", c.reconciled() ? "RECONCILED to the invoice register" : "DOES NOT RECONCILE: do not file, check the differences below"));
        sum.add(List.of("Invoices", c.register().invoices(), "Credit notes", c.register().creditNotes()));
        sum.add(List.of("Layout and treatment need the accountant's check before filing."));
        sum.add(List.of());
        sum.add(List.of("Check", "Register", "Export", "Difference"));
        for (Object[] row : checks(c)) sum.add(List.of(row[0], row[1], row[2], row[3]));
        out.put("summary", sum);

        List<List<Object>> b2b = new ArrayList<>();
        b2b.add(List.of("GSTIN/UIN of Recipient", "Invoice Number", "Invoice date", "Invoice Value", "Place Of Supply", "Reverse Charge", "Invoice Type",
                "Rate", "Taxable Value", "Integrated Tax", "Central Tax", "State/UT Tax", "Cess Amount"));
        for (Gstr1.B2b r : d.b2b()) {
            b2b.add(row(List.of(r.buyerGstin(), r.invoiceNumber(), DAY.format(r.invoiceDate()), money(r.invoiceValueMinor()), r.placeOfSupply(), "N", "Regular"),
                    r.ratePercent(), r.amounts()));
        }
        out.put("b2b", b2b);

        List<List<Object>> b2cs = new ArrayList<>();
        b2cs.add(List.of("Type", "Place Of Supply", "Supply", "Rate", "Taxable Value", "Integrated Tax", "Central Tax", "State/UT Tax", "Cess Amount"));
        for (Gstr1.B2cs r : d.b2cs()) b2cs.add(row(List.of("OE", r.placeOfSupply(), r.interState() ? "Inter-State" : "Intra-State"), r.ratePercent(), r.amounts()));
        out.put("b2cs", b2cs);

        List<List<Object>> cdnr = new ArrayList<>();
        cdnr.add(List.of("GSTIN/UIN of Recipient", "Note Number", "Note Date", "Note Type", "Original Invoice Number", "Original Invoice date", "Place Of Supply",
                "Note Value", "Rate", "Taxable Value", "Integrated Tax", "Central Tax", "State/UT Tax", "Cess Amount"));
        for (Gstr1.Cdnr r : d.cdnr()) {
            cdnr.add(row(List.of(r.buyerGstin(), r.noteNumber(), DAY.format(r.noteDate()), "C", r.invoiceNumber(), DAY.format(r.invoiceDate()), r.placeOfSupply(),
                    money(r.noteValueMinor())), r.ratePercent(), r.amounts()));
        }
        out.put("cdnr", cdnr);

        List<List<Object>> hsn = new ArrayList<>();
        hsn.add(List.of("HSN", "UQC", "Total Quantity", "Total Value", "Rate", "Taxable Value", "Integrated Tax", "Central Tax", "State/UT Tax", "Cess Amount"));
        for (Gstr1.Hsn r : d.hsn()) {
            hsn.add(row(List.of(r.hsn(), uqc(r.hsn()), r.quantity(), money(r.amounts().taxableMinor() + r.amounts().taxMinor())), r.ratePercent(), r.amounts()));
        }
        out.put("hsn", hsn);

        List<List<Object>> exemp = new ArrayList<>();
        exemp.add(List.of("Description", "Nil Rated Supplies", "Exempted (other than nil rated/non GST supply)", "Non-GST Supplies"));
        for (Gstr1.Exempt r : d.exempt()) {
            exemp.add(List.of((r.interState() ? "Inter-State" : "Intra-State") + " supplies to " + (r.registeredBuyer() ? "registered" : "unregistered") + " persons",
                    money(r.nilRatedMinor()), money(r.exemptedMinor()), money(r.nonGstMinor())));
        }
        out.put("exemp", exemp);
        return out;
    }

    private static List<Object> row(List<?> head, BigDecimal rate, Gstr1.Amounts a) {
        List<Object> r = new ArrayList<>(head);
        r.addAll(List.of(rate, money(a.taxableMinor()), money(a.igstMinor()), money(a.cgstMinor()), money(a.sgstMinor()), money(a.cessMinor())));
        return r;
    }

    /** The smallest workbook Excel opens: one worksheet per entry, inline strings, no styles. */
    static byte[] xlsx(Map<String, List<List<Object>>> sheets) {
        List<String> names = new ArrayList<>(sheets.keySet());
        StringBuilder types = new StringBuilder(), book = new StringBuilder(), rels = new StringBuilder();
        for (int i = 1; i <= names.size(); i++) {
            types.append("<Override PartName=\"/xl/worksheets/sheet").append(i).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            book.append("<sheet name=\"").append(xml(names.get(i - 1))).append("\" sheetId=\"").append(i).append("\" r:id=\"rId").append(i).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(i).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        }
        String head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            put(z, "[Content_Types].xml", head + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + types + "</Types>");
            put(z, "_rels/.rels", head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            put(z, "xl/workbook.xml", head + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" + book + "</sheets></workbook>");
            put(z, "xl/_rels/workbook.xml.rels", head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + rels + "</Relationships>");
            for (int i = 1; i <= names.size(); i++) put(z, "xl/worksheets/sheet" + i + ".xml", head + sheet(sheets.get(names.get(i - 1))));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static String sheet(List<List<Object>> rows) {
        StringBuilder s = new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int i = 0; i < rows.size(); i++) {
            s.append("<row r=\"").append(i + 1).append("\">");
            for (int j = 0; j < rows.get(i).size(); j++) {
                Object v = rows.get(i).get(j);
                String ref = col(j) + (i + 1);
                if (v instanceof Number n) {
                    s.append("<c r=\"").append(ref).append("\"><v>").append(n instanceof BigDecimal b ? b.toPlainString() : n.toString()).append("</v></c>");
                } else if (v != null && !v.toString().isEmpty()) {
                    s.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(xml(formulaSafe(v.toString()))).append("</t></is></c>");
                }
            }
            s.append("</row>");
        }
        return s.append("</sheetData></worksheet>").toString();
    }

    /** Text a spreadsheet would run as a formula (an invoice prefix typed by the clinic) gets a leading apostrophe. */
    private static String formulaSafe(String v) {
        return "=+-@\t\r".indexOf(v.charAt(0)) >= 0 ? "'" + v : v;
    }

    private static String col(int i) {
        StringBuilder s = new StringBuilder();
        for (int n = i; n >= 0; n = n / 26 - 1) s.insert(0, (char) ('A' + n % 26));
        return s.toString();
    }

    private static String xml(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') continue;
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static void put(ZipOutputStream z, String name, String body) throws java.io.IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(body.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}

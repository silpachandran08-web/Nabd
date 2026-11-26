package com.nabd.hms.billing.gst;

import com.nabd.hms.billing.gst.Gstr1.Amounts;
import com.nabd.hms.billing.gst.Gstr1.Line;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-011 golden: one month with mixed rates (5, 12, 18), exempt and nil lines, a tax-inclusive line with an odd paisa between CGST
 * and SGST, an inter-state B2B invoice, round-offs in both directions and three credit notes (a whole line, 2 of 7 units, a
 * goodwill amount spread over an exempt and two taxable lines). Every expected figure below was worked out by hand in paise.
 */
class Gstr1GoldenTest {

    private static final String SELLER = "29ABCDE1234F1Z5", KA = "29XYZAB9876C1Z2", MH = "27ABCDE1234F1Z5";
    private static final YearMonth OCT = YearMonth.of(2026, 10);

    private static LocalDate day(int d) {
        return OCT.atDay(d);
    }

    private static Line inv(String no, int d, long value, String buyer, String hsn, int qty, String cat, String rate, boolean inter,
                            long taxable, long igst, long cgst, long sgst) {
        return new Line(no, day(d), value, buyer, false, null, null, hsn, qty, cat, new BigDecimal(rate), inter, taxable, igst, cgst, sgst, 0);
    }

    private static Line cn(String no, int d, long value, String buyer, String invNo, int invDay, String hsn, int qty, String cat, String rate,
                           boolean inter, long taxable, long igst, long cgst, long sgst) {
        return new Line(no, day(d), value, buyer, false, invNo, day(invDay), hsn, qty, cat, new BigDecimal(rate), inter, taxable, igst, cgst, sgst, 0);
    }

    private static List<Line> invoices() {
        return List.of(
                // INV-1, B2C: 400.00 exempt + 966.67 @18 + 357.99 @5 = 1,724.66 + 191.90 tax + 0.44 round-off = 1,917.00
                inv("INV-1", 3, 191700, null, "9993", 1, "EXEMPT", "0.00", false, 40000, 0, 0, 0),
                inv("INV-1", 3, 191700, null, "999799", 1, "TAXABLE", "18.00", false, 96667, 0, 8700, 8700),
                inv("INV-1", 3, 191700, null, "3004", 3, "TAXABLE", "5.00", false, 35799, 0, 895, 895),
                // INV-2, B2B same state: 1,000.00 @18 + 846.61 @18 (999.00 tax-inclusive: 76.20 + 76.19) + 400.00 exempt = 2,579.00
                inv("INV-2", 10, 257900, KA, "999799", 1, "TAXABLE", "18.00", false, 100000, 0, 9000, 9000),
                inv("INV-2", 10, 257900, KA, "999799", 1, "TAXABLE", "18.00", false, 84661, 0, 7620, 7619),
                inv("INV-2", 10, 257900, KA, "9993", 1, "EXEMPT", "0.00", false, 40000, 0, 0, 0),
                // INV-3, B2B other state: goods 864.15 @5 IGST 43.21, service 1,000.00 @18 = 2,087.36 - 0.36 round-off = 2,087.00
                inv("INV-3", 15, 208700, MH, "3004", 7, "TAXABLE", "5.00", true, 86415, 4321, 0, 0),
                inv("INV-3", 15, 208700, MH, "999799", 1, "TAXABLE", "18.00", false, 100000, 0, 9000, 9000),
                // INV-4, B2C: 1,693.22 @18 + 50.00 nil (no SAC) + 250.00 @12 = 2,328.00
                inv("INV-4", 28, 232800, null, "999799", 2, "TAXABLE", "18.00", false, 169322, 0, 15239, 15239),
                inv("INV-4", 28, 232800, null, null, 1, "NIL", "0.00", false, 5000, 0, 0, 0),
                inv("INV-4", 28, 232800, null, "9018", 1, "TAXABLE", "12.00", false, 25000, 0, 1500, 1500));
    }

    private static List<Line> credits() {
        return List.of(
                // CN-1 on INV-2: the whole 1,000.00 line = 1,180.00
                cn("CN-1", 20, 118000, KA, "INV-2", 10, "999799", 1, "TAXABLE", "18.00", false, 100000, 0, 9000, 9000),
                // CN-2 on INV-3: 2 of 7 units: 246.90 + IGST 12.35 = 259.25
                cn("CN-2", 22, 25925, MH, "INV-3", 15, "3004", 2, "TAXABLE", "5.00", true, 24690, 1235, 0, 0),
                // CN-3 on INV-1: goodwill 100.00 = 20.87 exempt + (51.38 + 4.62 + 4.63) + (17.61 + 0.44 + 0.45)
                cn("CN-3", 25, 10000, null, "INV-1", 3, "9993", 0, "EXEMPT", "0.00", false, 2087, 0, 0, 0),
                cn("CN-3", 25, 10000, null, "INV-1", 3, "999799", 0, "TAXABLE", "18.00", false, 5138, 0, 462, 463),
                cn("CN-3", 25, 10000, null, "INV-1", 3, "3004", 0, "TAXABLE", "5.00", false, 1761, 0, 44, 45));
    }

    /** The registers as the document headers give them: 4 invoices, 3 credit notes. */
    private static Gstr1.Register register() {
        return new Gstr1.Register(4, 782864, 108228, 8, 891100, 3, 133676, 20249, 0, 153925);
    }

    private static Gstr1.Document doc() {
        return Gstr1.build(SELLER, OCT, invoices(), credits(), register());
    }

    private static Amounts a(long taxable, long igst, long cgst, long sgst) {
        return new Amounts(taxable, igst, cgst, sgst, 0);
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void b2bIsInvoiceWiseAndRateWise_withThePlaceOfSupplyTheTaxWasChargedFor() {
        assertThat(doc().b2b()).containsExactly(
                new Gstr1.B2b(KA, "INV-2", day(10), 257900, "29", bd("18.00"), a(184661, 0, 16620, 16619)),
                new Gstr1.B2b(MH, "INV-3", day(15), 208700, "27", bd("5.00"), a(86415, 4321, 0, 0)),
                new Gstr1.B2b(MH, "INV-3", day(15), 208700, "29", bd("18.00"), a(100000, 0, 9000, 9000)));
    }

    @Test
    void b2csIsByStateAndRate_netOfTheB2cCreditNote() {
        assertThat(doc().b2cs()).containsExactlyInAnyOrder(
                new Gstr1.B2cs("29", false, bd("18.00"), a(260851, 0, 23477, 23476)),
                new Gstr1.B2cs("29", false, bd("5.00"), a(34038, 0, 851, 850)),
                new Gstr1.B2cs("29", false, bd("12.00"), a(25000, 0, 1500, 1500)));
    }

    @Test
    void cdnrListsTheCreditNotesAgainstB2bInvoices_withTheOriginalInvoice() {
        assertThat(doc().cdnr()).containsExactly(
                new Gstr1.Cdnr(KA, "CN-1", day(20), "INV-2", day(10), 118000, "29", bd("18.00"), a(100000, 0, 9000, 9000)),
                new Gstr1.Cdnr(MH, "CN-2", day(22), "INV-3", day(15), 25925, "27", bd("5.00"), a(24690, 1235, 0, 0)));
    }

    @Test
    void hsnSummaryIsNetOfCreditNotes_perCodeAndRate() {
        assertThat(doc().hsn()).containsExactlyInAnyOrder(
                new Gstr1.Hsn("9993", bd("0.00"), 2, a(77913, 0, 0, 0)),
                new Gstr1.Hsn("999799", bd("18.00"), 5, a(445512, 0, 40097, 40095)),
                new Gstr1.Hsn("3004", bd("5.00"), 8, a(95763, 3086, 851, 850)),
                new Gstr1.Hsn("9018", bd("12.00"), 1, a(25000, 0, 1500, 1500)),
                new Gstr1.Hsn("NA", bd("0.00"), 1, a(5000, 0, 0, 0)));
    }

    @Test
    void exemptAndNilSuppliesAreSplitByBuyerKind_netOfCreditNotes() {
        assertThat(doc().exempt()).containsExactlyInAnyOrder(
                new Gstr1.Exempt(false, false, 5000, 37913, 0),
                new Gstr1.Exempt(false, true, 0, 40000, 0));
    }

    @Test
    void theSectionsReconcileToTheRegistersToThePaisa() {
        Gstr1.Reconciliation r = doc().reconciliation();
        assertThat(r.netTaxableMinor()).isEqualTo(649188); // 7,828.64 - 1,336.76
        assertThat(r.netTaxMinor()).isEqualTo(87979);      // 1,082.28 - 202.49
        assertThat(r.sectionsTaxableMinor()).isEqualTo(649188); // B2B 3,710.76 - CDNR 1,246.90 + B2CS 3,198.89 + EXEMP 829.13
        assertThat(r.sectionsTaxMinor()).isEqualTo(87979);      // B2B 555.60 - CDNR 192.35 + B2CS 516.54
        assertThat(r.hsnTaxableMinor()).isEqualTo(649188);
        assertThat(r.hsnTaxMinor()).isEqualTo(87979);
        assertThat(List.of(r.taxableDifferenceMinor(), r.taxDifferenceMinor(), r.hsnTaxableDifferenceMinor(), r.hsnTaxDifferenceMinor(),
                r.registerDifferenceMinor(), r.creditRegisterDifferenceMinor())).containsOnly(0L);
        assertThat(r.reconciled()).isTrue();
    }

    @Test
    void aSinglePaisaOffIsReportedAndNeverHidden() {
        // the invoice register says one paisa more tax than the lines carry
        Gstr1.Reconciliation r = Gstr1.build(SELLER, OCT, invoices(), credits(),
                new Gstr1.Register(4, 782864, 108229, 8, 891101, 3, 133676, 20249, 0, 153925)).reconciliation();
        assertThat(r.reconciled()).isFalse();
        assertThat(r.taxDifferenceMinor()).isEqualTo(-1);
        assertThat(r.hsnTaxDifferenceMinor()).isEqualTo(-1);
        assertThat(r.taxableDifferenceMinor()).isZero();

        // a register whose total is not taxable + tax + round-off
        assertThat(Gstr1.build(SELLER, OCT, invoices(), credits(), new Gstr1.Register(4, 782864, 108228, 8, 891101, 3, 133676, 20249, 0, 153925))
                .reconciliation()).satisfies(x -> {
                    assertThat(x.reconciled()).isFalse();
                    assertThat(x.registerDifferenceMinor()).isEqualTo(1);
                });

        // tax stored on an exempt line belongs to no section: it shows as a difference instead of vanishing
        List<Line> bad = new ArrayList<>(invoices());
        bad.set(0, inv("INV-1", 3, 191700, null, "9993", 1, "EXEMPT", "0.00", false, 40000, 0, 5, 0));
        Gstr1.Reconciliation t = Gstr1.build(SELLER, OCT, bad, credits(), new Gstr1.Register(4, 782864, 108233, 3, 891100, 3, 133676, 20249, 0, 153925))
                .reconciliation();
        assertThat(t.reconciled()).isFalse();
        assertThat(t.taxDifferenceMinor()).isEqualTo(-5);
        assertThat(t.hsnTaxDifferenceMinor()).isZero();
    }

    @Test
    void aBillOfSupplyChargesNoTax_soItsLinesAreNeverATaxableSupply() {
        List<Line> lines = List.of(
                new Line("BOS-1", day(5), 90000, null, true, null, null, "999799", 1, "TAXABLE", new BigDecimal("0.00"), false, 50000, 0, 0, 0, 0),
                new Line("BOS-1", day(5), 90000, null, true, null, null, "9993", 1, "EXEMPT", new BigDecimal("0.00"), false, 40000, 0, 0, 0, 0));
        Gstr1.Document d = Gstr1.build(null, OCT, lines, List.of(), new Gstr1.Register(1, 90000, 0, 0, 90000, 0, 0, 0, 0, 0));
        assertThat(d.b2b()).isEmpty();
        assertThat(d.b2cs()).isEmpty();
        assertThat(d.exempt()).containsExactly(new Gstr1.Exempt(false, false, 0, 40000, 50000));
        assertThat(d.reconciliation().reconciled()).isTrue();
    }

    @Test
    void anEmptyMonthReconcilesWithEmptySections() {
        Gstr1.Document d = Gstr1.build(SELLER, OCT, List.of(), List.of(), new Gstr1.Register(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThat(d.b2b()).isEmpty();
        assertThat(d.hsn()).isEmpty();
        assertThat(d.reconciliation().reconciled()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theJsonFileCarriesExactTwoPlaceDecimalsInTheGstnShape() {
        Map<String, Object> j = Gstr1Files.json(doc());
        assertThat(j).containsEntry("gstin", SELLER).containsEntry("fp", "102026");
        List<Map<String, Object>> b2b = (List<Map<String, Object>>) j.get("b2b");
        assertThat(b2b).extracting(m -> m.get("ctin")).containsExactly(KA, MH);
        Map<String, Object> inv3 = ((List<Map<String, Object>>) b2b.get(1).get("inv")).get(0);
        assertThat(inv3).containsEntry("inum", "INV-3").containsEntry("idt", "15-10-2026").containsEntry("val", bd("2087.00")).containsEntry("pos", "27");
        List<Map<String, Object>> items = (List<Map<String, Object>>) inv3.get("itms");
        assertThat(items).hasSize(2);
        assertThat((Map<String, Object>) items.get(0).get("itm_det")).containsEntry("rt", bd("5.00")).containsEntry("txval", bd("864.15"))
                .containsEntry("iamt", bd("43.21")).containsEntry("camt", bd("0.00"));
        Map<String, Object> note = ((List<Map<String, Object>>) ((List<Map<String, Object>>) j.get("cdnr")).get(1).get("nt")).get(0);
        assertThat(note).containsEntry("ntty", "C").containsEntry("nt_num", "CN-2").containsEntry("inum", "INV-3").containsEntry("val", bd("259.25"));
        assertThat((List<Map<String, Object>>) ((Map<String, Object>) j.get("hsn")).get("data")).anySatisfy(h ->
                assertThat(h).containsEntry("hsn_sc", "3004").containsEntry("qty", 8L).containsEntry("txval", bd("957.63")).containsEntry("iamt", bd("30.86")));
        assertThat((List<Map<String, Object>>) ((Map<String, Object>) j.get("nil")).get("inv")).contains(
                Map.of("sply_ty", "INTRAB2C", "nil_amt", bd("50.00"), "expt_amt", bd("379.13"), "ngsup_amt", bd("0.00")));
        Map<String, Object> rec = (Map<String, Object>) j.get("reconciliation");
        assertThat(rec).containsEntry("reconciled", true).containsEntry("invoices", 4).containsEntry("creditNotes", 3);
        assertThat((Map<String, Object>) rec.get("tax")).containsEntry("register", bd("879.79")).containsEntry("export", bd("879.79")).containsEntry("difference", bd("0.00"));
    }

    @Test
    void theWorkbookHasOneSheetPerSectionAndSaysWhetherItReconciles() throws Exception {
        Map<String, List<List<Object>>> sheets = Gstr1Files.sheets(doc());
        assertThat(sheets.keySet()).containsExactly("summary", "b2b", "b2cs", "cdnr", "hsn", "exemp");
        assertThat(sheets.get("summary").get(2)).containsExactly("Status", "RECONCILED to the invoice register");
        assertThat(sheets.get("b2b")).hasSize(4); // header + 3 rows
        assertThat(sheets.get("b2b").get(1)).containsExactly(KA, "INV-2", "10-10-2026", bd("2579.00"), "29", "N", "Regular", bd("18.00"), bd("1846.61"),
                bd("0.00"), bd("166.20"), bd("166.19"), bd("0.00"));

        byte[] xlsx = Gstr1Files.xlsx(sheets);
        List<String> entries = new ArrayList<>();
        String b2b = null;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (var e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                entries.add(e.getName());
                if (e.getName().equals("xl/worksheets/sheet2.xml")) b2b = new String(z.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        assertThat(entries).contains("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet6.xml");
        assertThat(b2b).contains("<v>1846.61</v>").contains("INV-2").contains(KA); // amounts are numeric cells with exact decimals

        Map<String, List<List<Object>>> broken = Gstr1Files.sheets(Gstr1.build(SELLER, OCT, invoices(), credits(),
                new Gstr1.Register(4, 782864, 108229, 8, 891101, 3, 133676, 20249, 0, 153925)));
        assertThat(broken.get("summary").get(2).get(1).toString()).startsWith("DOES NOT RECONCILE");
    }
}

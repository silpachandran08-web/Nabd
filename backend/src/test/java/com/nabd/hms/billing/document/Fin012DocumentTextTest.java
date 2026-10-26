package com.nabd.hms.billing.document;

import com.nabd.hms.billing.dto.InvoiceResponse;
import com.nabd.hms.billing.dto.LineItemResponse;
import com.nabd.hms.billing.dto.PaymentResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FIN-012 golden text: GST rule 46 fields, 48-column thermal fit, amount in words, Bill of Supply variant. No Spring, no database. */
class Fin012DocumentTextTest {

    private static final InvoiceDocumentText.Supplier SUPPLIER =
            new InvoiceDocumentText.Supplier("Sunrise Family Clinic and Diagnostics Private Limited", "29ABCDE1234F1Z5", "29", ZoneId.of("Asia/Kolkata"));

    private static InvoiceResponse invoice(String title, String buyerGstin, boolean interState) {
        var taxes = interState ? List.of(new LineItemResponse.TaxPart("IGST", new BigDecimal("18"), 9000L))
                : List.of(new LineItemResponse.TaxPart("CGST", new BigDecimal("9"), 4500L), new LineItemResponse.TaxPart("SGST", new BigDecimal("9"), 4500L));
        var line = new LineItemResponse("XRAY", "Digital X-ray chest PA view with report", "Procedure", 1, new BigDecimal("500.00"), new BigDecimal("18"),
                new BigDecimal("590.00"), new BigDecimal("500.00"), null, null, 50000L, taxes);
        return new InvoiceResponse(UUID.randomUUID(), "INV-000123", null, UUID.randomUUID(), "Asha Verma Of A Very Long Patient Name Indeed", null, "Dr X", "INR",
                new BigDecimal("500.00"), BigDecimal.ZERO, new BigDecimal("90.00"), new BigDecimal("0.00"), new BigDecimal("590.00"), new BigDecimal("590.00"),
                BigDecimal.ZERO, "paid", List.of(line), List.of(new PaymentResponse(UUID.randomUUID(), "cash", new BigDecimal("590.00"), Instant.parse("2026-10-26T08:30:00Z"), null)),
                Instant.parse("2026-10-26T08:00:00Z"), title, buyerGstin);
    }

    @Test
    void a4HasEveryRule46FieldAndTheWordsAndTheSplitTax() {
        List<String> t = InvoiceDocumentText.build(invoice("Invoice", null, false), SUPPLIER, "invoice", null, 90, List.of("999316"));
        String all = String.join("\n", t);
        assertThat(all).contains("Sunrise Family Clinic", "GSTIN: 29ABCDE1234F1Z5", "State code: 29", "INVOICE", "Invoice No: INV-000123",
                "Date: 26-Oct-2026 13:30", "Patient: Asha Verma", "Place of supply: 29", "999316", "CGST 9% 45.00", "SGST 9% 45.00",
                "TOTAL INR", "590.00", "Rupees Five Hundred Ninety Only", "cash", "Balance due");
        assertThat(t).allSatisfy(l -> assertThat(l.length()).isLessThanOrEqualTo(90));
    }

    @Test
    void interStateUsesIgstAndTheBuyerStateAsPlaceOfSupply() {
        String all = String.join("\n", InvoiceDocumentText.build(invoice("Invoice", "07AAACB1234C1Z2", true), SUPPLIER, "invoice", null, 90, List.of("999316")));
        assertThat(all).contains("IGST 18% 90.00", "Buyer GSTIN: 07AAACB1234C1Z2", "Place of supply: 07").doesNotContain("CGST");
    }

    @Test
    void thermalFitsFortyEightColumnsWithoutCuttingGstinInvoiceNumberOrTotals() {
        List<String> t = InvoiceDocumentText.build(invoice("Invoice", null, false), SUPPLIER, "invoice", null, 48, List.of("999316"));
        assertThat(t).allSatisfy(l -> assertThat(l.length()).isLessThanOrEqualTo(48));
        String all = String.join("\n", t);
        assertThat(all).contains("GSTIN: 29ABCDE1234F1Z5", "Invoice No: INV-000123", "TOTAL INR", "590.00", "Rupees Five Hundred Ninety Only");
        assertThat(t.stream().filter(l -> l.startsWith("TOTAL INR")).findFirst().orElseThrow()).endsWith("590.00");
        // the very long patient name wraps instead of being cut
        assertThat(all.replace("\n", " ")).contains("Very Long Patient Name Indeed");
    }

    @Test
    void billOfSupplyVariantNamesItselfAndReceiptCarriesItsNumber() {
        String bos = String.join("\n", InvoiceDocumentText.build(invoice("Bill of Supply", null, false), SUPPLIER, "invoice", null, 48, List.of()));
        assertThat(bos).contains("BILL OF SUPPLY");
        String rc = String.join("\n", InvoiceDocumentText.build(invoice("Invoice", null, false), SUPPLIER, "receipt", "RCP-000009", 48, List.of()));
        assertThat(rc).contains("PAYMENT RECEIPT", "Receipt No: RCP-000009", "Paid", "590.00").doesNotContain("Digital X-ray");
    }

    @Test
    void amountInWordsUsesLakhAndCrore() {
        assertThat(Words.indianRupees(new BigDecimal("1234567.50"))).isEqualTo("Rupees Twelve Lakh Thirty Four Thousand Five Hundred Sixty Seven and Fifty Paise Only");
        assertThat(Words.indianRupees(new BigDecimal("0"))).isEqualTo("Rupees Zero Only");
        assertThat(Words.indianRupees(new BigDecimal("100000000"))).isEqualTo("Rupees Ten Crore Only");
    }

    @Test
    void pdfIsWellFormedReadableAndThermalIsOnePageAt80mm() {
        byte[] a4 = SimplePdf.render(java.util.Collections.nCopies(200, "line (with) parens \\ and 590.00"), 595.3f, 841.9f, 40f, 9f, false);
        String raw = new String(a4, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(raw).startsWith("%PDF-1.4").endsWith("%%EOF\n").contains("/Count 3"); // 200 lines at 67 per page = 3 pages
        assertThat(S15BillingTestBase.pdfLines(a4)).hasSize(200).first().isEqualTo("line (with) parens \\ and 590.00");
        byte[] roll = SimplePdf.render(List.of("a", "b"), 226.8f, 0, 12f, 7f, true);
        assertThat(new String(roll, java.nio.charset.StandardCharsets.ISO_8859_1)).contains("/MediaBox [0 0 226.80").contains("/Count 1");
        // 48 columns of 7pt Courier (0.6 em per glyph) must fit the 80 mm roll inside its margins
        assertThat(48 * 0.6f * 7f).isLessThan(226.8f - 2 * 12f);
    }
}

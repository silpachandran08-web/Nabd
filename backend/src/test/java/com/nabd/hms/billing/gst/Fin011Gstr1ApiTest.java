package com.nabd.hms.billing.gst;

import com.nabd.hms.common.ModuleGrant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FIN-011 AC1: GET /v1/billing/exports/gstr1 over a month of real bills (checkout, tax engine, refunds with credit notes):
 * mixed rates, an exempt and a nil line, a tax-inclusive head, a discount that leaves odd paise, whole-rupee round-off, a B2B
 * invoice in the clinic's state and one to another state (IGST on goods), and three credit notes. The sections are compared
 * with the invoice register (the invoice and credit-note headers) to the paisa, and with the lines the invoice API shows.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Fin011Gstr1ApiTest extends Fin011TestBase {

    @org.springframework.beans.factory.annotation.Autowired
    com.nabd.hms.common.ClinicClock clinicClock;

    /** The month before this one in the clinic's zone (test clinics run on UTC): finished, so it can be exported. */
    private static final YearMonth LAST = YearMonth.now(ZoneOffset.UTC).minusMonths(1);

    private Env gstClinic() {
        Env e = env();
        registration(e, CLINIC_KA);
        head(e, "CONSX", "400.00", 0, "exempt", "9993", false, false);
        head(e, "LASER", "1000.00", 18, "taxable", "999799", false, false);
        head(e, "COSM", "999.00", 18, "taxable", "999799", false, true); // tax-inclusive
        head(e, "MED5", "123.45", 5, "taxable", "3004", true, false);    // goods
        head(e, "NIL0", "50.00", 0, "nil", null, false, false);
        return e;
    }

    private static Instant at(YearMonth m, int day, String time) {
        return Instant.parse(m.atDay(day) + "T" + time + "Z");
    }

    private void backdate(Map invoice, Instant at) {
        su().update("UPDATE invoices SET created_at = ? WHERE id = ?::uuid", Timestamp.from(at), invoice.get("id"));
    }

    private ResponseEntity<Map> export(String token, Object period, String format) {
        return exchange("/v1/billing/exports/gstr1?period=" + period + "&format=" + format, HttpMethod.GET, authed(token), Map.class);
    }

    private static byte[] file(ResponseEntity<Map> r) {
        return Base64.getDecoder().decode((String) r.getBody().get("contentBase64"));
    }

    private static long paise(Object amount) {
        return new BigDecimal(amount.toString()).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    private static long l(Object o) {
        return ((Number) o).longValue();
    }

    private static List<Map<String, Object>> lines(Map invoice) {
        return (List<Map<String, Object>>) invoice.get("lineItems");
    }

    private static Map<String, Object> lineOf(Map invoice, String code) {
        return lines(invoice).stream().filter(x -> code.equals(x.get("chargeCode"))).findFirst().orElseThrow();
    }

    private static long taxable(Map<String, Object> line) {
        return l(line.get("taxableMinor"));
    }

    private static long tax(Map<String, Object> line, String type) {
        return ((List<Map<String, Object>>) line.get("taxes")).stream().filter(t -> type.equals(t.get("type"))).mapToLong(t -> l(t.get("amountMinor"))).sum();
    }

    private Map invoice(Env e, Object id) {
        return exchange("/v1/billing/invoices/" + id, HttpMethod.GET, authed(e.ownerToken()), Map.class).getBody();
    }

    /** Pays the bill in full in cash and returns it with the payment on it. */
    private Map paid(Env e, Map invoice) {
        assertThat(pay(e, (String) invoice.get("id"), ((Number) invoice.get("total")).doubleValue()).getStatusCode().is2xxSuccessful()).isTrue();
        return invoice(e, invoice.get("id"));
    }

    /** A refund asked by the biller and approved by the owner; returns the credit note number. */
    private String refund(Env e, String biller, Map invoice, Map<String, Object> basis, long amountMinor) {
        BigDecimal amount = BigDecimal.valueOf(amountMinor, 2);
        Map<String, Object> body = new HashMap<>(basis);
        body.put("reasonCode", "billing_error");
        body.put("refundTo", List.of(Map.of("paymentId", ((Map) ((List) invoice.get("payments")).get(0)).get("id"), "amount", amount)));
        ResponseEntity<Map> req = exchange("/v1/billing/invoices/" + invoice.get("id") + "/refund-requests", HttpMethod.POST, authedJsonBody(biller, body), Map.class);
        assertThat(req.getStatusCode()).as("refund request: %s", req.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> ok = exchange("/v1/billing/refund-requests/" + req.getBody().get("id") + "/approve", HttpMethod.POST,
                authedJsonBody(e.ownerToken(), Map.of("expectedAmount", amount)), Map.class);
        assertThat(ok.getStatusCode()).as("refund approval: %s", ok.getBody()).isEqualTo(HttpStatus.OK);
        return (String) ok.getBody().get("creditNoteNumber");
    }

    private static long share(long amount, long num, long den) {
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(num)).divide(BigDecimal.valueOf(den), 0, RoundingMode.HALF_UP).longValueExact();
    }

    private static Map<String, Object> det(List<Map<String, Object>> items, String rate) {
        return items.stream().map(i -> (Map<String, Object>) i.get("itm_det")).filter(d -> new BigDecimal(d.get("rt").toString()).compareTo(new BigDecimal(rate)) == 0)
                .findFirst().orElseThrow();
    }

    private static Map<String, Object> by(List<Map<String, Object>> rows, String key, Object value) {
        return rows.stream().filter(r -> value.equals(r.get(key))).findFirst().orElseThrow(() -> new AssertionError(key + "=" + value + " not in " + rows));
    }

    private static Map<String, Object> rated(List<Map<String, Object>> rows, String rate) {
        return rows.stream().filter(r -> new BigDecimal(r.get("rt").toString()).compareTo(new BigDecimal(rate)) == 0).findFirst()
                .orElseThrow(() -> new AssertionError("rate " + rate + " not in " + rows));
    }

    private static long sum(List<Map<String, Object>> rows, String... keys) {
        long s = 0;
        for (Map<String, Object> r : rows) for (String k : keys) s += paise(r.get(k));
        return s;
    }

    @Test
    void aMonthOfMixedBillsAndCreditNotesReconcilesToTheInvoiceRegisterToThePaisa() {
        Env e = gstClinic();
        String biller = staffToken(e, "Biller", new ModuleGrant("billing", true, true, true, false, false, false, false));

        // A: B2C, exempt + 18% + goods at 5%, with a 33.33 discount spread over the lines
        Map a = paid(e, checkout(e, Map.of("lineItems", List.of(line("CONSX", 1), line("LASER", 1), line("MED5", 3)),
                "discounts", List.of(Map.of("kind", "amount", "value", 33.33, "reasonCode", "goodwill")))).getBody());
        // B: B2B in the clinic's state: 18% + a tax-inclusive 18% line + an exempt line
        Map b = paid(e, checkout(e, Map.of("lineItems", List.of(line("LASER", 1), line("COSM", 1), line("CONSX", 1)), "buyerGstin", BUYER_KA)).getBody());
        // C: B2B to another state: goods go IGST, the service stays CGST + SGST
        Map c = paid(e, checkout(e, Map.of("lineItems", List.of(line("MED5", 7), line("LASER", 1)), "buyerGstin", BUYER_MH)).getBody());
        // D: B2C, two tax-inclusive units and a nil-rated line
        Map d = checkout(e, Map.of("lineItems", List.of(line("COSM", 2), line("NIL0", 1)))).getBody();
        // not part of the month's return: a voided bill, a bill of two months ago, a bill of the running month
        Map voided = checkout(e, Map.of("lineItems", List.of(line("LASER", 1)))).getBody();
        assertThat(exchange("/v1/billing/invoices/" + voided.get("id") + "/void", HttpMethod.POST, authedJsonBody(e.ownerToken(), Map.of("reason", "wrong patient")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        Map older = checkout(e, Map.of("lineItems", List.of(line("LASER", 1)), "buyerGstin", BUYER_KA)).getBody();
        Map running = checkout(e, Map.of("lineItems", List.of(line("LASER", 1)))).getBody();

        // what the tax engine stored, as the invoice API shows it
        assertThat(a.get("buyerGstin")).isNull();
        assertThat(taxable(lineOf(b, "LASER"))).isEqualTo(100_000);
        assertThat(tax(lineOf(b, "LASER"), "CGST")).isEqualTo(9_000);
        assertThat(taxable(lineOf(b, "COSM")) + tax(lineOf(b, "COSM"), "CGST") + tax(lineOf(b, "COSM"), "SGST")).isEqualTo(99_900); // inclusive: 999.00 all in
        assertThat((List<?>) lineOf(b, "CONSX").get("taxes")).isEmpty();
        assertThat(taxable(lineOf(c, "MED5"))).isEqualTo(86_415);
        assertThat(tax(lineOf(c, "MED5"), "IGST")).isEqualTo(4_321); // 864.15 x 5% = 43.2075
        assertThat(tax(lineOf(c, "LASER"), "IGST")).isZero();

        // credit notes: a whole line of B, 2 of the 7 units of C's goods, a goodwill 100.00 on A
        String cnB = refund(e, biller, b, Map.of("lines", List.of(Map.of("lineId", lineOf(b, "LASER").get("id"), "quantity", 1))), 118_000);
        long cTaxable = share(86_415, 2, 7), cIgst = share(4_321, 2, 7);
        assertThat(cTaxable).isEqualTo(24_690);
        assertThat(cIgst).isEqualTo(1_235);
        String cnC = refund(e, biller, c, Map.of("lines", List.of(Map.of("lineId", lineOf(c, "MED5").get("id"), "quantity", 2))), cTaxable + cIgst);
        refund(e, biller, a, Map.of("amount", 100), 10_000);

        // move everything into last month (A on its first instant, D on its last second), the others out of it
        backdate(a, at(LAST, 1, "00:00:00"));
        backdate(b, at(LAST, 10, "09:30:00"));
        backdate(c, at(LAST, 15, "11:00:00"));
        backdate(d, at(LAST, LAST.lengthOfMonth(), "23:59:59"));
        backdate(voided, at(LAST, 12, "10:00:00"));
        backdate(older, at(LAST.minusMonths(1), 20, "10:00:00"));
        backdate(running, at(LAST.plusMonths(1), 1, "00:00:00")); // the first instant of the running month is not last month
        for (Map inv : List.of(a, b, c)) {
            su().update("UPDATE credit_notes SET issued_at = ? WHERE invoice_id = ?::uuid", Timestamp.from(at(LAST, 20, "12:00:00")), inv.get("id"));
        }

        // the invoice register and the credit-note register: the document headers of exactly these bills
        String ids = "('" + a.get("id") + "','" + b.get("id") + "','" + c.get("id") + "','" + d.get("id") + "')";
        Map<String, Object> reg = su().queryForMap("SELECT SUM(subtotal_minor - discount_minor) AS taxable, SUM(tax_minor) AS tax, SUM(round_off_minor) AS round_off, " +
                "SUM(total_minor) AS total FROM invoices WHERE id::text IN " + ids);
        Map<String, Object> cn = su().queryForMap("SELECT count(*) AS n, SUM(taxable_minor) AS taxable, SUM(tax_minor) AS tax, SUM(total_minor) AS total " +
                "FROM credit_notes WHERE invoice_id::text IN " + ids);
        assertThat(l(cn.get("n"))).isEqualTo(3);
        assertThat(l(cn.get("total"))).isEqualTo(118_000 + 25_925 + 10_000);
        assertThat(l(reg.get("taxable")) + l(reg.get("tax")) + l(reg.get("round_off"))).isEqualTo(l(reg.get("total")));
        // the goodwill note's reversal per line of A (the credit-note register's own detail)
        Map<String, Map<String, Object>> cnA = new HashMap<>();
        for (Map<String, Object> row : su().queryForList("SELECT li.charge_code, l.taxable_minor, l.tax_minor FROM credit_note_lines l " +
                "JOIN credit_notes n ON n.id = l.credit_note_id JOIN invoice_line_items li ON li.id = l.invoice_line_id WHERE n.invoice_id = ?::uuid", a.get("id"))) {
            cnA.put((String) row.get("charge_code"), row);
        }
        assertThat(cnA.keySet()).contains("CONSX", "LASER", "MED5");

        // ── the export ──
        ResponseEntity<Map> r = export(e.ownerToken(), LAST, "json");
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("reconciled", true).containsEntry("invoices", 4).containsEntry("creditNotes", 3)
                .containsEntry("period", LAST.toString()).containsEntry("fileName", "gstr1-" + LAST + ".json").containsEntry("contentType", "application/json");
        String raw = new String(file(r), StandardCharsets.UTF_8);
        assertThat(raw).contains("\"txval\":1000.00"); // exact two-place decimals on the wire, not floating point
        assertThat(raw).doesNotContain((String) voided.get("invoiceNumber")).doesNotContain((String) older.get("invoiceNumber"))
                .doesNotContain((String) running.get("invoiceNumber"));
        Map<String, Object> g = objectMapper.readValue(file(r), Map.class);
        assertThat(g).containsEntry("gstin", CLINIC_KA);

        // B2B: invoice-wise, rate-wise
        List<Map<String, Object>> b2b = (List<Map<String, Object>>) g.get("b2b");
        assertThat(b2b).extracting(x -> x.get("ctin")).containsExactly(BUYER_KA, BUYER_MH);
        Map<String, Object> invB = ((List<Map<String, Object>>) by(b2b, "ctin", BUYER_KA).get("inv")).get(0);
        assertThat(invB).containsEntry("inum", b.get("invoiceNumber")).containsEntry("pos", "29");
        assertThat(paise(invB.get("val"))).isEqualTo(paise(b.get("total")));
        List<Map<String, Object>> itemsB = (List<Map<String, Object>>) invB.get("itms");
        assertThat(itemsB).hasSize(1); // both taxable lines are 18%; the exempt line is not a B2B item
        assertThat(paise(det(itemsB, "18").get("txval"))).isEqualTo(taxable(lineOf(b, "LASER")) + taxable(lineOf(b, "COSM")));
        assertThat(paise(det(itemsB, "18").get("camt"))).isEqualTo(tax(lineOf(b, "LASER"), "CGST") + tax(lineOf(b, "COSM"), "CGST"));
        assertThat(paise(det(itemsB, "18").get("samt"))).isEqualTo(tax(lineOf(b, "LASER"), "SGST") + tax(lineOf(b, "COSM"), "SGST"));
        Map<String, Object> invC = ((List<Map<String, Object>>) by(b2b, "ctin", BUYER_MH).get("inv")).get(0);
        List<Map<String, Object>> itemsC = (List<Map<String, Object>>) invC.get("itms");
        assertThat(invC).containsEntry("inum", c.get("invoiceNumber")).containsEntry("pos", "27");
        assertThat(paise(det(itemsC, "5").get("txval"))).isEqualTo(86_415);
        assertThat(paise(det(itemsC, "5").get("iamt"))).isEqualTo(4_321);
        assertThat(paise(det(itemsC, "5").get("camt"))).isZero();
        assertThat(paise(det(itemsC, "18").get("txval"))).isEqualTo(100_000);
        assertThat(paise(det(itemsC, "18").get("camt"))).isEqualTo(9_000);

        // CDNR: the two notes against B2B invoices, each naming its invoice
        List<Map<String, Object>> cdnr = (List<Map<String, Object>>) g.get("cdnr");
        Map<String, Object> ntB = ((List<Map<String, Object>>) by(cdnr, "ctin", BUYER_KA).get("nt")).get(0);
        assertThat(ntB).containsEntry("nt_num", cnB).containsEntry("ntty", "C").containsEntry("inum", b.get("invoiceNumber"));
        assertThat(paise(ntB.get("val"))).isEqualTo(118_000);
        assertThat(paise(det((List<Map<String, Object>>) ntB.get("itms"), "18").get("txval"))).isEqualTo(100_000);
        assertThat(paise(det((List<Map<String, Object>>) ntB.get("itms"), "18").get("samt"))).isEqualTo(9_000);
        Map<String, Object> ntC = ((List<Map<String, Object>>) by(cdnr, "ctin", BUYER_MH).get("nt")).get(0);
        assertThat(ntC).containsEntry("nt_num", cnC).containsEntry("inum", c.get("invoiceNumber"));
        assertThat(paise(ntC.get("val"))).isEqualTo(25_925);
        assertThat(paise(det((List<Map<String, Object>>) ntC.get("itms"), "5").get("txval"))).isEqualTo(24_690);
        assertThat(paise(det((List<Map<String, Object>>) ntC.get("itms"), "5").get("iamt"))).isEqualTo(1_235);

        // B2CS: by state and rate, net of the goodwill note on A
        List<Map<String, Object>> b2cs = (List<Map<String, Object>>) g.get("b2cs");
        assertThat(b2cs).hasSize(2).allSatisfy(x -> assertThat(x).containsEntry("pos", "29").containsEntry("sply_ty", "INTRA"));
        assertThat(paise(rated(b2cs, "18").get("txval"))).isEqualTo(taxable(lineOf(a, "LASER")) + taxable(lineOf(d, "COSM")) - l(cnA.get("LASER").get("taxable_minor")));
        assertThat(paise(rated(b2cs, "18").get("camt")) + paise(rated(b2cs, "18").get("samt")))
                .isEqualTo(tax(lineOf(a, "LASER"), "CGST") + tax(lineOf(a, "LASER"), "SGST") + tax(lineOf(d, "COSM"), "CGST") + tax(lineOf(d, "COSM"), "SGST")
                        - l(cnA.get("LASER").get("tax_minor")));
        assertThat(paise(rated(b2cs, "5").get("txval"))).isEqualTo(taxable(lineOf(a, "MED5")) - l(cnA.get("MED5").get("taxable_minor")));

        // EXEMP: exempt and nil value by buyer kind, net of the note
        List<Map<String, Object>> nil = (List<Map<String, Object>>) ((Map<String, Object>) g.get("nil")).get("inv");
        assertThat(paise(by(nil, "sply_ty", "INTRAB2C").get("expt_amt"))).isEqualTo(taxable(lineOf(a, "CONSX")) - l(cnA.get("CONSX").get("taxable_minor")));
        assertThat(paise(by(nil, "sply_ty", "INTRAB2C").get("nil_amt"))).isEqualTo(5_000);
        assertThat(paise(by(nil, "sply_ty", "INTRAB2B").get("expt_amt"))).isEqualTo(40_000);

        // HSN: per code and rate, net of credit notes, quantities included
        List<Map<String, Object>> hsn = (List<Map<String, Object>>) ((Map<String, Object>) g.get("hsn")).get("data");
        assertThat(hsn).extracting(x -> x.get("hsn_sc")).containsExactlyInAnyOrder("9993", "999799", "3004", "NA");
        assertThat(l(by(hsn, "hsn_sc", "3004").get("qty"))).isEqualTo(3 + 7 - 2);
        assertThat(l(by(hsn, "hsn_sc", "9993").get("qty"))).isEqualTo(2);
        assertThat(paise(by(hsn, "hsn_sc", "3004").get("iamt"))).isEqualTo(4_321 - 1_235);

        // ── reconciliation to the registers, to the paisa ──
        List<Map<String, Object>> b2bItems = new ArrayList<>(), cdnrItems = new ArrayList<>();
        b2b.forEach(x -> ((List<Map<String, Object>>) x.get("inv")).forEach(i -> ((List<Map<String, Object>>) i.get("itms")).forEach(t -> b2bItems.add((Map<String, Object>) t.get("itm_det")))));
        cdnr.forEach(x -> ((List<Map<String, Object>>) x.get("nt")).forEach(i -> ((List<Map<String, Object>>) i.get("itms")).forEach(t -> cdnrItems.add((Map<String, Object>) t.get("itm_det")))));
        String[] taxKeys = {"iamt", "camt", "samt", "csamt"};
        long netTaxable = l(reg.get("taxable")) - l(cn.get("taxable")), netTax = l(reg.get("tax")) - l(cn.get("tax"));
        assertThat(sum(b2bItems, "txval") - sum(cdnrItems, "txval") + sum(b2cs, "txval") + sum(nil, "nil_amt", "expt_amt", "ngsup_amt")).isEqualTo(netTaxable);
        assertThat(sum(b2bItems, taxKeys) - sum(cdnrItems, taxKeys) + sum(b2cs, taxKeys)).isEqualTo(netTax);
        assertThat(sum(hsn, "txval")).isEqualTo(netTaxable);
        assertThat(sum(hsn, taxKeys)).isEqualTo(netTax);
        // and the same figures as the lines the invoice API shows for these four bills
        long apiTaxable = 0, apiTax = 0;
        for (Map inv : List.of(a, b, c, d)) {
            for (Map<String, Object> li : lines(inv)) {
                apiTaxable += taxable(li);
                apiTax += tax(li, "CGST") + tax(li, "SGST") + tax(li, "IGST");
            }
        }
        assertThat(apiTaxable).isEqualTo(l(reg.get("taxable")));
        assertThat(apiTax).isEqualTo(l(reg.get("tax")));
        Map<String, Object> rec = (Map<String, Object>) g.get("reconciliation");
        assertThat(rec).containsEntry("reconciled", true);
        for (String check : List.of("taxable", "tax", "hsnTaxable", "hsnTax", "invoiceRegister", "creditNoteRegister")) {
            assertThat(paise(((Map<String, Object>) rec.get(check)).get("difference"))).as(check).isZero();
        }
        assertThat(paise(((Map<String, Object>) rec.get("taxable")).get("register"))).isEqualTo(netTaxable);
        assertThat(paise(((Map<String, Object>) rec.get("tax")).get("register"))).isEqualTo(netTax);
        assertThat(paise(rec.get("registerInvoiceTotal"))).isEqualTo(l(reg.get("total")));

        // the workbook: same month, one sheet per section, the B2B invoice on the b2b sheet
        ResponseEntity<Map> x = export(e.ownerToken(), LAST, "xlsx");
        assertThat(x.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(x.getBody()).containsEntry("reconciled", true).containsEntry("fileName", "gstr1-" + LAST + ".xlsx");
        Map<String, String> parts = unzip(file(x));
        assertThat(parts.keySet()).contains("xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet6.xml");
        assertThat(parts.get("xl/workbook.xml")).contains("name=\"summary\"", "name=\"b2b\"", "name=\"b2cs\"", "name=\"cdnr\"", "name=\"hsn\"", "name=\"exemp\"");
        assertThat(parts.get("xl/worksheets/sheet1.xml")).contains("RECONCILED to the invoice register");
        assertThat(parts.get("xl/worksheets/sheet2.xml")).contains((String) b.get("invoiceNumber"), BUYER_KA, "<v>1000.00</v>");
        assertThat(parts.get("xl/worksheets/sheet4.xml")).contains(cnB, cnC);

        // both downloads are in the audit log, with the period and the result, by the owner
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForList("SELECT after::text FROM audit_log WHERE action = 'billing.gstr1_exported' AND actor_id = ?",
                String.class, e.owner().id()))).hasSize(2).allSatisfy(s -> assertThat(s).contains(LAST.toString()).contains("reconciled"));
    }

    private static Map<String, String> unzip(byte[] zip) {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var en = z.getNextEntry(); en != null; en = z.getNextEntry()) out.put(en.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        return out;
    }

    @Test
    void aMonthThatIsNotOverCannotBeExported_andBadInputIsRefused() {
        Env e = gstClinic();
        YearMonth now = YearMonth.now(ZoneOffset.UTC);
        ResponseEntity<Map> open = export(e.ownerToken(), now, "json");
        assertThat(open.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(open)).isEqualTo("PERIOD_OPEN");
        assertThat(code(export(e.ownerToken(), now.plusMonths(2), "xlsx"))).isEqualTo("PERIOD_OPEN");
        assertThat(export(e.ownerToken(), "2026-13", "json").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(export(e.ownerToken(), "last-month", "json").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(export(e.ownerToken(), LAST, "csv").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // a finished month with no bills at all is an empty return that reconciles
        ResponseEntity<Map> empty = export(e.ownerToken(), LAST, "json");
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(empty.getBody()).containsEntry("reconciled", true).containsEntry("invoices", 0).containsEntry("creditNotes", 0);
        // nothing was exported by the refused calls
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'billing.gstr1_exported'", Integer.class))).isEqualTo(1);
    }

    @Test
    void onlyTheOwnerWithTheExportGrantCanDownload() {
        Env e = gstClinic();
        // an accountant role with reports:export but not the Owner role
        String accountant = staffToken(e, "Accountant", new ModuleGrant("reports", true, false, false, false, false, false, true),
                new ModuleGrant("billing", true, false, false, false, false, false, false));
        ResponseEntity<Map> notOwner = export(accountant, LAST, "json");
        assertThat(notOwner.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(notOwner)).isEqualTo("OWNER_REQUIRED");
        // a cashier without reports:export
        String cashier = staffToken(e, "Cashier", new ModuleGrant("billing", true, true, true, false, false, false, false),
                new ModuleGrant("reports", true, false, false, false, false, false, false));
        assertThat(export(cashier, LAST, "json").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/billing/exports/gstr1?period=" + LAST, HttpMethod.GET, new org.springframework.http.HttpEntity<>(new org.springframework.http.HttpHeaders()), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'billing.gstr1_exported'", Integer.class))).isZero();
    }

    @Test
    void anotherClinicsExportHoldsNothingOfThisClinic() {
        Env mine = gstClinic(), other = gstClinic();
        Map inv = checkout(mine, Map.of("lineItems", List.of(line("LASER", 1)), "buyerGstin", BUYER_KA)).getBody();
        backdate(inv, at(LAST, 5, "10:00:00"));
        ResponseEntity<Map> theirs = export(other.ownerToken(), LAST, "json");
        assertThat(theirs.getBody()).containsEntry("invoices", 0).containsEntry("reconciled", true);
        assertThat(new String(file(theirs), StandardCharsets.UTF_8)).doesNotContain((String) inv.get("invoiceNumber")).doesNotContain(BUYER_KA);
        ResponseEntity<Map> own = export(mine.ownerToken(), LAST, "json");
        assertThat(own.getBody()).containsEntry("invoices", 1).containsEntry("reconciled", true);
        assertThat(new String(file(own), StandardCharsets.UTF_8)).contains((String) inv.get("invoiceNumber"));
    }

    @Test
    void aClinicOutsideGstGetsNoGstr1() {
        Env e = env();
        su().execute("SET session_replication_role = replica; UPDATE tenants SET region_code = 'SA' WHERE id = '" + e.tenant().id() + "'");
        clinicClock.evict(e.tenant().id());
        ResponseEntity<Map> r = export(e.ownerToken(), LAST, "json");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(r)).isEqualTo("NOT_APPLICABLE");
    }
}

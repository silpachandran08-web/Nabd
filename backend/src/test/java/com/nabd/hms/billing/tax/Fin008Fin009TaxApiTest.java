package com.nabd.hms.billing.tax;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** FIN-008 (tax engine through checkout and quotes) and FIN-009 (India GST rules) end to end. */
class Fin008Fin009TaxApiTest extends ApiTestBase {

    private static final AtomicInteger PHONES = new AtomicInteger((int) (System.nanoTime() % 5_000_000));
    private static final AtomicInteger CODES = new AtomicInteger(10_000_000 + (int) (System.nanoTime() % 80_000_000));
    private static final String KA = "29ABCDE1234F1Z5", MH = "27ABCDE1234F1Z5";
    private int seq = 0;

    @org.springframework.beans.factory.annotation.Autowired
    com.nabd.hms.common.ClinicClock clinicClock;

    private record Env(SeededTenant tenant, SeededStaff owner, String token) {
    }

    private String phone() {
        return "+91971" + String.format("%07d", 1_000_000 + PHONES.getAndIncrement());
    }

    private Env env() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "own" + t.id() + "@a.com", phone(), false);
        return new Env(t, owner, loginAndGetAccessToken(owner));
    }

    private JdbcTemplate admin() {
        return new JdbcTemplate(superuserDataSource());
    }

    /** A head; taxCategory/sacHsn may be null (a legacy head that only has a rate). goods = pharmacy item (HSN on the head). */
    private void head(Env e, String code, double base, double rate, String taxCategory, String sacHsn, boolean goods, boolean inclusive) {
        inTenantTx(e.tenant().id(), () -> jdbc.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, tax_rate_percent, " +
                        "tax_category, sac_hsn, tax_inclusive, hsn_code) VALUES (?,?,?,?,?,?,?,?,?,?)",
                e.tenant().id(), code, "Head " + code, "Service", base, rate, taxCategory, sacHsn, inclusive, goods ? "3004" : null));
    }

    private void registration(Env e, String gstin, boolean registered) {
        String owner = "ow" + e.tenant().id() + "@a.com";
        UUID ownerId = seedOwner(owner).id();
        UUID entity = UUID.randomUUID(), reg = UUID.randomUUID();
        JdbcTemplate a = admin();
        a.update("INSERT INTO legal_entities (id, owner_id, name, entity_type) VALUES (?,?,?,?)", entity, ownerId, "LE", "company");
        a.update("INSERT INTO tax_registrations (id, legal_entity_id, jurisdiction_code, gstin_or_vat_no, registered) VALUES (?,?,?,?,?)",
                reg, entity, "IN", gstin, registered);
        a.update("UPDATE tenants SET tax_registration_id = ? WHERE id = ?", reg, e.tenant().id());
    }

    private Map<String, Object> line(String code, int qty) {
        return new HashMap<>(Map.of("chargeCode", code, "quantity", qty));
    }

    private ResponseEntity<Map> quote(Env e, Map<String, Object> body) {
        return exchange("/v1/billing/quotes", HttpMethod.POST, authedJsonBody(e.token(), body), Map.class);
    }

    private static Map<String, Object> disc(double v) {
        return Map.of("kind", "amount", "value", v, "reasonCode", "goodwill");
    }

    private String pending(Env e) {
        String pid = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                "name", "P" + seq++, "phone", phone(), "dob", "1990-01-01", "gender", "male")), Map.class).getBody().get("id");
        String id = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                "patientId", pid, "doctorId", e.owner().id().toString())), Map.class).getBody().get("id");
        for (String s : new String[]{"waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending"}) {
            exchange("/v1/queue/" + id + "/status", HttpMethod.PATCH, authedJsonBody(e.token(), Map.of("status", s)), Map.class);
        }
        return id;
    }

    private ResponseEntity<Map> checkout(Env e, Map<String, Object> body) {
        return exchange("/v1/billing/checkout/" + pending(e), HttpMethod.POST, authedJsonBody(e.token(), body), Map.class);
    }

    private static List<Map<String, Object>> lines(ResponseEntity<Map> r, String key) {
        return (List<Map<String, Object>>) r.getBody().get(key);
    }

    private static long l(Object o) {
        return ((Number) o).longValue();
    }

    private static double d(Object o) {
        return ((Number) o).doubleValue();
    }

    private static List<String> types(Map<String, Object> line) {
        return ((List<Map<String, Object>>) line.get("taxes")).stream().map(t -> (String) t.get("type")).toList();
    }

    /** SUM(line taxable + line tax) + round_off = total, to the minor unit (the nightly invariant query). */
    private void assertInvariant(String invoiceId) {
        Map<String, Object> row = admin().queryForMap("SELECT i.total_minor, i.round_off_minor, " +
                "(SELECT COALESCE(SUM(li.taxable_minor), 0) FROM invoice_line_items li WHERE li.invoice_id = i.id) AS taxable, " +
                "(SELECT COALESCE(SUM(t.amount_minor), 0) FROM invoice_line_taxes t JOIN invoice_line_items li ON li.id = t.line_id WHERE li.invoice_id = i.id) AS tax " +
                "FROM invoices i WHERE i.id = ?::uuid", invoiceId);
        assertThat(l(row.get("taxable")) + l(row.get("tax")) + l(row.get("round_off_minor"))).isEqualTo(l(row.get("total_minor")));
    }

    // FIN-008 AC1 (QA-13) and AC5
    @Test
    void discountIsTakenBeforeTaxAndTheBreakdownIsStored() {
        Env e = env();
        head(e, "LASER", 1000, 18, "taxable", "999799", false, false);
        ResponseEntity<Map> r = checkout(e, Map.of("lineItems", List.of(line("LASER", 1)), "discounts", List.of(disc(100))));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(d(r.getBody().get("tax"))).isEqualTo(162.0);
        assertThat(d(r.getBody().get("total"))).isEqualTo(1062.0);
        assertThat(d(r.getBody().get("roundOff"))).isZero();
        Map<String, Object> li = lines(r, "lineItems").get(0);
        assertThat(l(li.get("taxableMinor"))).isEqualTo(90_000);
        assertThat(types(li)).containsExactly("CGST", "SGST");
        assertThat(lines(r, "lineItems").get(0)).containsKey("taxes");
        assertThat(r.getBody().get("documentTitle")).isEqualTo("Invoice");
        String id = (String) r.getBody().get("id");
        assertInvariant(id);
        // reprint reads what was stored
        ResponseEntity<Map> again = exchange("/v1/billing/invoices/" + id, HttpMethod.GET, authed(e.token()), Map.class);
        assertThat(lines(again, "lineItems").get(0)).isEqualTo(li);
    }

    // FIN-008 AC2
    @Test
    void discountIsAllocatedAcrossLinesAndEachLineTaxedOnItsReducedValue() {
        Env e = env();
        head(e, "EXM", 500, 0, "exempt", "9993", false, false);
        head(e, "G18", 1000, 18, "taxable", "999799", false, false);
        head(e, "G05", 200, 5, "taxable", "999798", false, false);
        Map<String, Object> body = Map.of("lines", List.of(line("EXM", 1), line("G18", 1), line("G05", 1)), "discounts", List.of(disc(170)));
        ResponseEntity<Map> q = quote(e, body);
        assertThat(q.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> ls = lines(q, "lines");
        assertThat(ls).extracting(m -> l(m.get("discountMinor"))).containsExactly(5_000L, 10_000L, 2_000L);
        assertThat(types(ls.get(0))).isEmpty();
        Map<String, Object> totals = (Map<String, Object>) q.getBody().get("totals");
        assertThat(l(totals.get("discountMinor"))).isEqualTo(17_000);
        assertThat(l(totals.get("taxMinor"))).isEqualTo(16_200 + 900);
        assertThat(l(totals.get("totalMinor"))).isEqualTo(170_000 - 17_000 + 17_100);
        // the quote is the bill
        ResponseEntity<Map> bill = checkout(e, Map.of("lineItems", List.of(line("EXM", 1), line("G18", 1), line("G05", 1)), "discounts", List.of(disc(170))));
        assertThat(d(bill.getBody().get("total"))).isEqualTo(totals.get("totalMinor") instanceof Number n ? n.doubleValue() / 100 : 0);
        assertInvariant((String) bill.getBody().get("id"));
        assertThat(admin().queryForObject("SELECT SUM(discount_allocated_minor) FROM invoice_line_items WHERE invoice_id = ?::uuid", Long.class,
                bill.getBody().get("id"))).isEqualTo(17_000L);
    }

    // FIN-008 AC3
    @Test
    void sarInvoiceKeepsTwoDecimalsAndHasNoRoundOff() {
        Env e = env();
        admin().execute("SET session_replication_role = replica; UPDATE tenants SET region_code = 'SA' WHERE id = '" + e.tenant().id() + "'");
        head(e, "CHK", 100.55, 15, null, null, false, false);
        ResponseEntity<Map> q = quote(e, Map.of("lines", List.of(line("CHK", 1))));
        assertThat(q.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(q.getBody().get("currency")).isEqualTo("SAR");
        Map<String, Object> t = (Map<String, Object>) q.getBody().get("totals");
        assertThat(l(t.get("roundOffMinor"))).isZero();
        assertThat(l(t.get("totalMinor"))).isEqualTo(11_563);
        assertThat(types(lines(q, "lines").get(0))).containsExactly("VAT");
    }

    // FIN-008 AC4
    @Test
    void aRateChangeEffectiveTomorrowDoesNotTouchTodaysBillOrTheStoredReprint() {
        Env e = env();
        String code = String.valueOf(CODES.getAndIncrement());
        JdbcTemplate a = admin();
        java.time.LocalDate day = clinicClock.today(e.tenant().id()); // the clinic's day, not the DB/JVM/system date
        a.update("INSERT INTO tax_rules (jurisdiction, category, tax_category, rate, effective_from, effective_to, source_note) " +
                "VALUES ('IN', ?, 'taxable', 5, DATE '2020-01-01', ?, 'test')", code, day);
        a.update("INSERT INTO tax_rules (jurisdiction, category, tax_category, rate, effective_from, source_note) " +
                "VALUES ('IN', ?, 'taxable', 12, ?, 'test')", code, day.plusDays(1));
        head(e, "MED", 1000, 99, "taxable", code, false, false); // the head's own rate (99) must not win over the rule
        ResponseEntity<Map> today = checkout(e, Map.of("lineItems", List.of(line("MED", 1))));
        assertThat(d(today.getBody().get("tax"))).isEqualTo(50.0);
        // tomorrow arrives: the old rule ends, the new one is in force
        a.update("UPDATE tax_rules SET effective_to = ? WHERE category = ? AND effective_from = DATE '2020-01-01'", day.minusDays(1), code);
        a.update("UPDATE tax_rules SET effective_from = ? WHERE category = ? AND rate = 12", day, code);
        ResponseEntity<Map> fresh = checkout(e, Map.of("lineItems", List.of(line("MED", 1))));
        assertThat(d(fresh.getBody().get("tax"))).isEqualTo(120.0);
        ResponseEntity<Map> reprint = exchange("/v1/billing/invoices/" + today.getBody().get("id"), HttpMethod.GET, authed(e.token()), Map.class);
        assertThat(d(reprint.getBody().get("tax"))).isEqualTo(50.0);
        assertThat(d(lines(reprint, "lineItems").get(0).get("taxRatePercent"))).isEqualTo(5.0);
        assertThat(types(lines(reprint, "lineItems").get(0))).containsExactly("CGST", "SGST");
    }

    // FIN-009 AC1, AC2
    @Test
    void consultationIsExemptAndCosmeticIsCgstPlusSgst() {
        Env e = env();
        registration(e, KA, true);
        head(e, "CONS", 500, 0, "exempt", "999312", false, false);
        head(e, "LHR", 2000, 18, "taxable", "999799", false, false);
        ResponseEntity<Map> r = checkout(e, Map.of("lineItems", List.of(line("CONS", 1), line("LHR", 1))));
        List<Map<String, Object>> ls = lines(r, "lineItems");
        assertThat(types(ls.get(0))).isEmpty();
        assertThat(l(ls.get(0).get("taxableMinor"))).isEqualTo(50_000);
        assertThat(ls.get(0).get("taxRatePercent")).isEqualTo(0.0);
        List<Map<String, Object>> parts = (List<Map<String, Object>>) ls.get(1).get("taxes");
        assertThat(parts).extracting(p -> (String) p.get("type")).containsExactly("CGST", "SGST");
        assertThat(parts).extracting(p -> d(p.get("ratePercent"))).containsExactly(9.0, 9.0);
        assertThat(parts).extracting(p -> l(p.get("amountMinor"))).containsExactly(18_000L, 18_000L);
        assertInvariant((String) r.getBody().get("id"));
    }

    // FIN-009 AC3
    @Test
    void goodsToAnOutOfStateBuyerGstinAreIgstWhileServicesAndSameStateStayCgstSgst() {
        Env e = env();
        registration(e, KA, true);
        head(e, "PARA", 100, 5, "taxable", "3004", true, false);
        head(e, "LHR", 2000, 18, "taxable", "999799", false, false);
        ResponseEntity<Map> inter = quote(e, Map.of("lines", List.of(line("PARA", 10)), "buyerGstin", MH));
        assertThat(types(lines(inter, "lines").get(0))).containsExactly("IGST");
        assertThat(l(((List<Map<String, Object>>) lines(inter, "lines").get(0).get("taxes")).get(0).get("amountMinor"))).isEqualTo(5_000);
        assertThat(types(lines(quote(e, Map.of("lines", List.of(line("PARA", 10)), "buyerGstin", "29XYZAB9876C1Z2")), "lines").get(0))).containsExactly("CGST", "SGST");
        assertThat(types(lines(quote(e, Map.of("lines", List.of(line("LHR", 1)), "buyerGstin", MH)), "lines").get(0))).containsExactly("CGST", "SGST");
        ResponseEntity<Map> bad = quote(e, Map.of("lines", List.of(line("PARA", 1)), "buyerGstin", "123"));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<Map> bill = checkout(e, Map.of("lineItems", List.of(line("PARA", 10)), "buyerGstin", MH));
        assertThat(bill.getBody().get("buyerGstin")).isEqualTo(MH);
        assertThat(types(lines(bill, "lineItems").get(0))).containsExactly("IGST");
    }

    // FIN-009 AC4
    @Test
    void anUnregisteredClinicIssuesABillOfSupplyWithNoTaxLines() {
        Env e = env();
        registration(e, null, false);
        head(e, "LHR", 2000, 18, "taxable", "999799", false, false);
        ResponseEntity<Map> q = quote(e, Map.of("lines", List.of(line("LHR", 1))));
        assertThat(q.getBody().get("documentType")).isEqualTo("bill_of_supply");
        assertThat(l(((Map<String, Object>) q.getBody().get("totals")).get("taxMinor"))).isZero();
        ResponseEntity<Map> bill = checkout(e, Map.of("lineItems", List.of(line("LHR", 1))));
        assertThat(bill.getBody().get("documentTitle")).isEqualTo("Bill of Supply");
        assertThat(d(bill.getBody().get("tax"))).isZero();
        assertThat(d(bill.getBody().get("total"))).isEqualTo(2000.0);
        assertThat(lines(bill, "lineItems").get(0).get("taxes")).isEqualTo(List.of());
        assertThat(d(lines(bill, "lineItems").get(0).get("taxRatePercent"))).isZero();
    }

    // FIN-009 AC5 + NFR (no free rate) + permission + tenant isolation
    @Test
    void thePickerSetsCategoryCodeAndRateAndRefusesAnythingOutsideTheAllowedSet() {
        Env e = env();
        head(e, "NEW", 1000, 0, null, null, false, false);
        UUID id = inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT id FROM charge_catalogue WHERE tenant_id = ? AND code = 'NEW'", UUID.class, e.tenant().id()));
        String path = "/v1/setup/charges/" + id + "/tax";

        ResponseEntity<Map> picker = exchange("/v1/billing/tax/guided-picker?jurisdiction=IN", HttpMethod.GET, authed(e.token()), Map.class);
        assertThat(picker.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) picker.getBody().get("questions")).isNotEmpty();
        assertThat(((List<Map<String, Object>>) picker.getBody().get("outcomes")).stream().map(o -> (String) o.get("id"))).contains("cosmetic", "exempt", "goods");
        assertThat((List<?>) exchange("/v1/billing/tax/guided-picker?jurisdiction=SA", HttpMethod.GET, authed(e.token()), Map.class).getBody().get("questions")).isEmpty();

        assertThat(patch(e, path, Map.of("taxCategory", "taxable", "sacHsn", "99", "gstRate", 18)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<Map> badRate = patch(e, path, Map.of("taxCategory", "taxable", "sacHsn", "999799", "gstRate", 7));
        assertThat(badRate.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(String.valueOf(badRate.getBody().get("type"))).contains("rate-not-allowed");
        assertThat(patch(e, path, Map.of("taxCategory", "exempt", "sacHsn", "999312", "gstRate", 5)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(String.valueOf(patch(e, path, Map.of("taxCategory", "taxable", "sacHsn", "999799")).getBody().get("type"))).contains("rate-not-allowed");

        ResponseEntity<Map> ok = patch(e, path, Map.of("taxCategory", "taxable", "sacHsn", "999799", "gstRate", 18));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(d(ok.getBody().get("gstRate"))).isEqualTo(18.0);
        Map<String, Object> preview = (Map<String, Object>) ok.getBody().get("preview");
        assertThat(l(preview.get("taxMinor"))).isEqualTo(18_000);
        assertThat(admin().queryForObject("SELECT count(*) FROM audit_log WHERE entity_id = ?::uuid AND action = 'billing.charge_tax_set'", Integer.class, id.toString())).isEqualTo(1);

        // another clinic cannot touch this head
        Env other = env();
        assertThat(patch(other, path, Map.of("taxCategory", "exempt", "sacHsn", "999312")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // a role without setup:edit has no access
        UUID billingOnly = seedRole(e.tenant().id(), "BillingOnly", false, new ModuleGrant("billing", true, true, true, false, false, false, false));
        String weak = loginAndGetAccessToken(seedStaff(e.tenant(), billingOnly, "w" + e.tenant().id() + "@a.com", phone(), false));
        ResponseEntity<Map> denied = exchange(path, HttpMethod.PATCH, authedJsonBody(weak, Map.of("taxCategory", "exempt", "sacHsn", "999312")), Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // RLS: invoice_line_taxes belongs to its tenant
    @Test
    void lineTaxesAreInvisibleToAnotherTenant() {
        Env e = env();
        head(e, "LHR", 2000, 18, "taxable", "999799", false, false);
        checkout(e, Map.of("lineItems", List.of(line("LHR", 1))));
        Env other = env();
        assertThat(inTenantTx(other.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM invoice_line_taxes", Integer.class))).isZero();
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM invoice_line_taxes", Integer.class))).isEqualTo(2);
    }

    private ResponseEntity<Map> patch(Env e, String path, Map<String, Object> body) {
        return exchange(path, HttpMethod.PATCH, authedJsonBody(e.token(), body), Map.class);
    }
}

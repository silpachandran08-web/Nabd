package com.nabd.hms.pharmacy;

import com.nabd.hms.billing.document.S15BillingTestBase;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.pharmacy.api.StockCommands;
import com.nabd.hms.pharmacy.api.StockCommands.BatchReceipt;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHL-019: the counter sale's backend: item index with ETag and barcode, payments taken with the sale (split tender), the
 * sale event, and the 80 mm receipt (clinic name, tax identity, drug licences, HSN, batch and expiry per line).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl019CounterSaleApiTest extends ApiTestBase {

    private static final AtomicInteger PHONE = new AtomicInteger(1900000);
    private static final String GSTIN = "29ABCDE1234F1Z5";

    @Autowired
    private StockCommands stock;
    @Autowired
    private ClinicClock clock;

    private record Clinic(SeededTenant tenant, String token, UUID staffId) {
        UUID t() {
            return tenant.id();
        }
    }

    private static String phone() {
        return "+9195" + String.format("%08d", PHONE.incrementAndGet());
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private Clinic clinic(String mode) {
        return clinic(mode, t -> { });
    }

    /** @param beforeLogin changes to the tenant row made before anyone signs in (market, tax identity) */
    private Clinic clinic(String mode, java.util.function.Consumer<UUID> beforeLogin) {
        SeededTenant t = seedTenant();
        beforeLogin.accept(t.id());
        SeededStaff staff = seedStaff(t, seedFullAccessRole(t.id()), "own" + t.id() + "@p19.test", phone(), false);
        String token = loginAndGetAccessToken(staff);
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", mode, "reason", "PHL-019 test")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Clinic(t, token, staff.id());
    }

    /** A token of the same clinic whose role holds only these grants. */
    private String tokenWith(Clinic c, String role, ModuleGrant... grants) {
        return loginAndGetAccessToken(seedStaff(c.tenant(), seedRole(c.t(), role, false, grants), role + c.t() + "@p19.test", phone(), false));
    }

    private ResponseEntity<Map> addItem(Clinic c, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Item " + UUID.randomUUID().toString().substring(0, 6), "isRx", false, "price", 100.00,
                "taxRatePercent", 0, "stockQty", 50));
        body.putAll(extra);
        return exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), body), Map.class);
    }

    private ResponseEntity<List> index(String token, String ifNoneMatch) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        if (ifNoneMatch != null) h.set(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
        return exchange("/v1/pharmacy/items/index", HttpMethod.GET, new HttpEntity<>(h), List.class);
    }

    private static Map<String, Object> line(Object code, int qty) {
        return new HashMap<>(Map.of("chargeCode", code, "quantity", qty));
    }

    private ResponseEntity<Map> sell(String token, List<Map<String, Object>> lines, List<Map<String, Object>> payments) {
        Map<String, Object> body = new HashMap<>(Map.of("lineItems", lines));
        if (payments != null) body.put("payments", payments);
        return exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private static Map<String, Object> pay(String method, double amount) {
        return Map.of("method", method, "amount", amount);
    }

    private int invoices(Clinic c) {
        return su().queryForObject("SELECT count(*) FROM invoices WHERE tenant_id = ?", Integer.class, c.t());
    }

    private int stockOf(Object itemId) {
        return su().queryForObject("SELECT stock_qty FROM charge_catalogue WHERE id = ?::uuid", Integer.class, itemId);
    }

    private List<String> pdf(Clinic c, Object invoiceId) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(c.token());
        ResponseEntity<byte[]> r = exchange("/v1/billing/invoices/" + invoiceId + "/document?layout=thermal80", HttpMethod.GET, new HttpEntity<>(h), byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return S15BillingTestBase.pdfLines(r.getBody());
    }

    // ---- item index ----

    @Test
    void theIndexListsSellableItemsWithBarcodeAndAnswers304UntilAnItemChanges() {
        Clinic c = clinic("hybrid");
        Map item = addItem(c, Map.of("name", "Crocin 650", "genericName", "Paracetamol", "gtin", "8901234567890", "price", 30.00)).getBody();
        Map rx = addItem(c, Map.of("name", "Azee 500", "isRx", true)).getBody();
        Map off = addItem(c, Map.of("name", "Switched off")).getBody();
        exchange("/v1/pharmacy/items/" + off.get("id") + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("active", false)), Map.class);
        addItem(c, Map.of("name", "Bought outside", "inHouse", false));
        assertThat(item.get("gtin")).isEqualTo("8901234567890");

        ResponseEntity<List> first = index(c.token(), null);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> rows = first.getBody();
        assertThat(rows).extracting(r -> r.get("name")).containsExactly("Azee 500", "Crocin 650");
        Map<String, Object> row = rows.get(1);
        assertThat(row).containsEntry("id", item.get("id")).containsEntry("code", item.get("code")).containsEntry("generic", "Paracetamol")
                .containsEntry("gtin", "8901234567890").containsEntry("class", "OTC").containsEntry("isRx", false).containsEntry("onHand", 50);
        assertThat(new BigDecimal(String.valueOf(row.get("price")))).isEqualByComparingTo("30.00");
        assertThat(rows.get(0)).containsEntry("isRx", true).containsEntry("id", rx.get("id"));

        String etag = first.getHeaders().getETag();
        assertThat(etag).isNotBlank();
        assertThat(index(c.token(), "W/" + etag).getStatusCode()).as("a weak copy of the tag still matches").isEqualTo(HttpStatus.NOT_MODIFIED);
        ResponseEntity<List> again = index(c.token(), etag);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(again.getBody()).isNull();
        assertThat(again.getHeaders().getETag()).isEqualTo(etag);

        // a sale changes the stock on hand: the old ETag no longer matches
        assertThat(sell(c.token(), List.of(line(item.get("code"), 2)), null).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<List> after = index(c.token(), etag);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(after.getHeaders().getETag()).isNotEqualTo(etag);
        assertThat(((List<Map<String, Object>>) after.getBody()).get(1)).containsEntry("onHand", 48);
    }

    @Test
    void theIndexNeedsTheDispenseGrantAndNeverShowsAnotherClinicsItems() {
        Clinic a = clinic("hybrid");
        Clinic b = clinic("hybrid");
        addItem(a, Map.of("name", "Only in A", "gtin", "12345678"));
        addItem(b, Map.of("name", "Only in B", "gtin", "12345678")); // the same barcode in another clinic is fine
        assertThat((List<Map<String, Object>>) index(a.token(), null).getBody()).extracting(r -> r.get("name")).containsExactly("Only in A");
        assertThat((List<Map<String, Object>>) index(b.token(), null).getBody()).extracting(r -> r.get("name")).containsExactly("Only in B");
        // pharmacy view + create without edit has no pharmacy:dispense
        String viewer = tokenWith(a, "Viewer", new ModuleGrant("pharmacy", true, true, false, false, false, false, false));
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(viewer);
        assertThat(exchange("/v1/pharmacy/items/index", HttpMethod.GET, new HttpEntity<>(h), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aBarcodeIsUniqueInTheClinicValidatedKeptOnAnEditThatOmitsItAndClearedByAnEmptyOne() {
        Clinic c = clinic("hybrid");
        Map first = addItem(c, Map.of("gtin", "8901234567890")).getBody();
        ResponseEntity<Map> dup = addItem(c, Map.of("gtin", "8901234567890"));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(dup.getBody().get("type"))).endsWith("/gtin-in-use");
        assertThat(addItem(c, Map.of("gtin", "ABC")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(su().queryForObject("SELECT count(*) FROM charge_catalogue WHERE tenant_id = ? AND stock_qty IS NOT NULL", Integer.class, c.t()))
                .as("the refused items were not created").isEqualTo(1);

        String url = "/v1/pharmacy/items/" + first.get("id");
        Map<String, Object> edit = new HashMap<>(Map.of("name", "Renamed", "price", 100.00, "taxRatePercent", 0));
        assertThat(exchange(url, HttpMethod.PATCH, authedJsonBody(c.token(), edit), Map.class).getBody().get("gtin")).isEqualTo("8901234567890");
        edit.put("gtin", "");
        assertThat(exchange(url, HttpMethod.PATCH, authedJsonBody(c.token(), edit), Map.class).getBody().get("gtin")).isNull();
        assertThat(addItem(c, Map.of("gtin", "8901234567890")).getStatusCode()).as("the freed barcode can be used again").isEqualTo(HttpStatus.CREATED);
    }

    // ---- payments with the sale ----

    @Test
    void cash200PlusUpi150OnA350BillRecordsTwoPaymentRowsBalanceZeroAndOneSaleEvent() {
        Clinic c = clinic("hybrid");
        Map item = addItem(c, Map.of("name", "Counter item PHL19", "price", 175.00)).getBody();
        ResponseEntity<Map> r = sell(c.token(), List.of(line(item.get("code"), 2)), List.of(pay("cash", 200), pay("upi", 150)));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        Map inv = r.getBody();
        assertThat(new BigDecimal(String.valueOf(inv.get("total")))).isEqualByComparingTo("350");
        assertThat(new BigDecimal(String.valueOf(inv.get("balanceDue")))).isEqualByComparingTo("0");
        assertThat(inv.get("status")).isEqualTo("paid");
        assertThat((List<Map<String, Object>>) inv.get("payments")).extracting(p -> p.get("method")).containsExactly("cash", "upi");
        List<Map<String, Object>> rows = su().queryForList("SELECT method, amount, receipt_number FROM invoice_payments WHERE invoice_id = ?::uuid ORDER BY recorded_at, id", inv.get("id"));
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(p -> ((BigDecimal) p.get("amount")).intValue()).containsExactly(200, 150);
        assertThat(rows).extracting(p -> p.get("receipt_number")).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(stockOf(item.get("id"))).isEqualTo(48);

        List<String> events = inTenantTx(c.t(), () -> jdbc.queryForList("SELECT payload::text FROM outbox_events WHERE tenant_id = ? " +
                "AND event_type = 'pharmacy.otc_sale.completed.v1'", String.class, c.t()));
        assertThat(events).singleElement().satisfies(e -> assertThat(e).contains(String.valueOf(inv.get("id"))).contains("\"lineCount\": 1")
                .contains("\"totalMinor\": 35000").contains("\"currency\": \"INR\"").doesNotContain("Counter item"));
    }

    @Test
    void paymentsThatDoNotAddUpToTheTotalRefuseTheWholeSale() {
        Clinic c = clinic("hybrid");
        Map item = addItem(c, Map.of("price", 350.00)).getBody();
        for (List<Map<String, Object>> tender : List.of(List.of(pay("cash", 200), pay("upi", 100)), List.of(pay("cash", 400)))) {
            ResponseEntity<Map> r = sell(c.token(), List.of(line(item.get("code"), 1)), tender);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(String.valueOf(r.getBody().get("type"))).endsWith("/payment-mismatch");
        }
        assertThat(sell(c.token(), List.of(line(item.get("code"), 1)), List.of(pay("cheque", 350))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invoices(c)).as("no invoice, no stock issue, no event").isZero();
        assertThat(stockOf(item.get("id"))).isEqualTo(50);
        assertThat(su().queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'pharmacy.otc_sale.completed.v1'",
                Integer.class, c.t())).isZero();
    }

    @Test
    void takingMoneyWithTheSaleNeedsBillingEditButASaleWithoutPaymentsStaysOpenToBillingCreate() {
        Clinic c = clinic("hybrid");
        Map item = addItem(c, Map.of("price", 50.00)).getBody();
        String creator = tokenWith(c, "Creator", new ModuleGrant("billing", true, true, false, false, false, false, false));
        ResponseEntity<Map> refused = sell(creator, List.of(line(item.get("code"), 1)), List.of(pay("cash", 50)));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(invoices(c)).isZero();
        ResponseEntity<Map> unpaid = sell(creator, List.of(line(item.get("code"), 1)), null);
        assertThat(unpaid.getStatusCode()).as("%s", unpaid.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(unpaid.getBody().get("status")).isNotEqualTo("paid");
        assertThat((List) unpaid.getBody().get("payments")).isEmpty();
    }

    @Test
    void aRepeatedIdempotencyKeyDoesNotSellOrChargeTwice() {
        Clinic c = clinic("hybrid");
        Map item = addItem(c, Map.of("price", 80.00)).getBody();
        Map<String, Object> body = Map.of("lineItems", List.of(line(item.get("code"), 1)), "payments", List.of(pay("cash", 80)));
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> one = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBodyWithHeader(c.token(), "Idempotency-Key", key, body), Map.class);
        ResponseEntity<Map> two = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBodyWithHeader(c.token(), "Idempotency-Key", key, body), Map.class);
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(two.getBody().get("id")).isEqualTo(one.getBody().get("id"));
        assertThat(invoices(c)).isEqualTo(1);
        assertThat(su().queryForObject("SELECT count(*) FROM invoice_payments WHERE tenant_id = ?", Integer.class, c.t())).isEqualTo(1);
        assertThat(stockOf(item.get("id"))).isEqualTo(49);
    }

    // ---- FEFO + 80 mm receipt ----

    @Test
    void theEightyMmReceiptOfAnInHouseSaleShowsClinicGstinLicencesHsnAndTheFefoBatchesWithExpiry() {
        Clinic c = clinic("in_house", t -> su().update("UPDATE tenants SET tax_id = ?, tax_id_type = 'GSTIN' WHERE id = ?", GSTIN, t));
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("licenceNumbers",
                Map.of("20B", "TEST-LIC-20B", "21B", "TEST-LIC-21B"))), Map.class).getStatusCode().is2xxSuccessful()).isTrue();
        Map item = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", "Fefo tablet", "isRx", false,
                "price", 10.00, "taxRatePercent", 0, "hsnCode", "3004")), Map.class).getBody();
        UUID id = UUID.fromString((String) item.get("id"));
        LocalDate today = clock.today(c.t());
        LocalDate early = today.plusDays(60), late = today.plusDays(200);
        UUID a = receive(c, id, "EARLY", early, 5);
        UUID b = receive(c, id, "LATE", late, 50);

        ResponseEntity<Map> r = sell(c.token(), List.of(line(item.get("code"), 8)), List.of(pay("cash", 80)));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(su().queryForObject("SELECT qty_on_hand FROM stock_batches WHERE id = ?", Integer.class, a)).as("first expiry goes first").isZero();
        assertThat(su().queryForObject("SELECT qty_on_hand FROM stock_batches WHERE id = ?", Integer.class, b)).isEqualTo(47);

        List<String> lines = pdf(c, r.getBody().get("id"));
        String name = su().queryForObject("SELECT name FROM tenants WHERE id = ?", String.class, c.t());
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MM/yyyy");
        assertThat(lines).allSatisfy(l -> assertThat(l.length()).as("80 mm roll: 48 columns").isLessThanOrEqualTo(48));
        assertThat(lines.get(0).strip()).isEqualTo(name);
        assertThat(lines).contains("GSTIN: " + GSTIN + "  State code: 29", "Drug licence 20B: TEST-LIC-20B", "Drug licence 21B: TEST-LIC-21B",
                "Fefo tablet [3004]", "    Batch EARLY Exp " + f.format(early) + " x5", "    Batch LATE Exp " + f.format(late) + " x3");
        assertThat(lines.indexOf("    Batch EARLY Exp " + f.format(early) + " x5")).isGreaterThan(lines.indexOf("Fefo tablet [3004]"));
        assertThat(String.join("\n", lines)).contains("cash").doesNotContain("VAT No");
    }

    @Test
    void aVatMarketReceiptCarriesTheVatNumberInsteadOfAGstinAndASingleBatchLineHasNoQuantity() {
        Clinic c = clinic("in_house", t -> su().execute("SET session_replication_role = replica; UPDATE tenants SET region_code = 'SA', " +
                "tax_id = '300000000000003', tax_id_type = 'VAT' WHERE id = '" + t + "'"));
        Map item = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", "Vat tablet", "isRx", false,
                "price", 10.00, "taxRatePercent", 15)), Map.class).getBody();
        LocalDate expiry = clock.today(c.t()).plusDays(300);
        receive(c, UUID.fromString((String) item.get("id")), "ONLY", expiry, 20);
        ResponseEntity<Map> r = sell(c.token(), List.of(line(item.get("code"), 2)), null);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        List<String> lines = pdf(c, r.getBody().get("id"));
        assertThat(lines).contains("VAT No: 300000000000003", "    Batch ONLY Exp " + DateTimeFormatter.ofPattern("MM/yyyy").format(expiry));
        assertThat(String.join("\n", lines)).doesNotContain("GSTIN");
    }

    @Test
    void aHybridClinicsReceiptHasNoBatchLineBecauseItKeepsNoBatchDetails() {
        Clinic c = clinic("hybrid");
        Map item = addItem(c, Map.of("name", "Plain item")).getBody();
        ResponseEntity<Map> r = sell(c.token(), List.of(line(item.get("code"), 1)), null);
        assertThat(String.join("\n", pdf(c, r.getBody().get("id")))).contains("Plain item").doesNotContain("Batch ");
    }

    private UUID receive(Clinic c, UUID item, String no, LocalDate expiry, int qty) {
        return inTenantTx(c.t(), () -> stock.receiveBatch(item, new BatchReceipt(no, expiry, new BigDecimal("12.00"), new BigDecimal("7.5000"),
                qty, "A1", null, null), c.staffId(), "GRN " + no).batchId());
    }
}

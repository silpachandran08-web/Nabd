package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** PHL-007: a supplier bill posts batches and receipt movements in one transaction; duplicate bills and expired batches are refused. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl007GoodsReceiptApiTest extends ApiTestBase {

    private static final AtomicInteger PHONE = new AtomicInteger(4100000);

    @Autowired
    private ClinicClock clock;

    private record Clinic(SeededTenant tenant, String token, UUID staffId) {
    }

    private Clinic hybridClinic(String tag) {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = ClinicianFixture.licensed(seedStaff(tenant, seedFullAccessRole(tenant.id()), tag + "@p7.test",
                "+9196" + String.format("%08d", PHONE.incrementAndGet()), false));
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "PHL-007 test")), Map.class);
        return new Clinic(tenant, token, staff.id());
    }

    private String addItem(Clinic c, String name, int qty) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", name, "isRx", false, "price", 100.00, "taxRatePercent", 12.00, "stockQty", qty)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private int stockOf(Clinic c, String itemId) {
        return inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT stock_qty FROM charge_catalogue WHERE id = ?::uuid", Integer.class, itemId));
    }

    private int count(Clinic c, String sql, Object... args) {
        return inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    private Map<String, Object> line(String itemId, String batch, LocalDate expiry, int qty, int free, double rate) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("itemId", itemId);
        m.put("batchNo", batch);
        m.put("expiryDate", expiry.toString());
        m.put("mrp", 120.00);
        m.put("rate", rate);
        m.put("qty", qty);
        m.put("freeQty", free);
        m.put("taxRate", 12.00);
        return m;
    }

    private ResponseEntity<Map> post(Clinic c, String supplier, String bill, List<Map<String, Object>> lines) {
        return exchange("/v1/pharmacy/receipts", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "supplierName", supplier, "billNumber", bill, "billDate", clock.today(c.tenant().id()).toString(), "lines", lines)), Map.class);
    }

    private LocalDate future(Clinic c) {
        return clock.today(c.tenant().id()).plusMonths(18);
    }

    @Test
    void fiveLineBillTopsUpFiveItemsAndWritesFiveReceiptMovementsInOneTransaction() {
        Clinic c = hybridClinic("five");
        List<String> ids = new ArrayList<>();
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            String id = addItem(c, "Receipt item " + i, i);
            ids.add(id);
            lines.add(line(id, "B-" + i, future(c), 10 * i, 0, 50.00));
        }
        ResponseEntity<Map> r = post(c, "Acme Distributors", "INV-1001", lines);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat((List) r.getBody().get("batches")).hasSize(5);
        assertThat(new BigDecimal(String.valueOf(r.getBody().get("subtotal")))).isEqualByComparingTo("7500.00"); // 50 x (10+20+30+40+50)
        for (int i = 0; i < 5; i++) {
            assertThat(stockOf(c, ids.get(i))).isEqualTo((i + 1) + 10 * (i + 1));
            assertThat(count(c, "SELECT count(*) FROM pharmacy_stock_movements WHERE item_id = ?::uuid AND kind = 'receipt'", ids.get(i))).isEqualTo(1);
        }
        assertThat(count(c, "SELECT count(*) FROM goods_receipts WHERE status = 'posted' AND line_count = 5")).isEqualTo(1);
        assertThat(count(c, "SELECT count(*) FROM goods_receipt_lines")).isEqualTo(5);
    }

    @Test
    void sameSupplierAndBillNumberTwiceIsAConflictButAnotherSupplierOrTenantIsFine() {
        Clinic c = hybridClinic("dup");
        String id = addItem(c, "Dup item", 0);
        assertThat(post(c, "Acme Distributors", "D-1", List.of(line(id, "B1", future(c), 5, 0, 10))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> again = post(c, "  ACME distributors ", "D-1", List.of(line(id, "B2", future(c), 5, 0, 10)));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(again.getBody().get("type"))).endsWith("/duplicate-supplier-bill");
        assertThat(stockOf(c, id)).isEqualTo(5); // the refused bill moved nothing
        assertThat(post(c, "Other Supplier", "D-1", List.of(line(id, "B3", future(c), 5, 0, 10))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Clinic other = hybridClinic("dup2");
        String id2 = addItem(other, "Dup item 2", 0);
        assertThat(post(other, "Acme Distributors", "D-1", List.of(line(id2, "B1", future(other), 1, 0, 10))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // tenant isolation: the other clinic cannot see this clinic's receipts
        assertThat(count(other, "SELECT count(*) FROM goods_receipts")).isEqualTo(1);
        assertThat(count(other, "SELECT count(*) FROM goods_receipt_lines")).isEqualTo(1);
    }

    @Test
    void freeQuantityLowersTheEffectivePurchaseRateAndAllUnitsLandOnTheShelf() {
        Clinic c = hybridClinic("free");
        String id = addItem(c, "Scheme item", 0);
        ResponseEntity<Map> r = post(c, "Acme", "F-1", List.of(line(id, "S1", future(c), 10, 2, 50.00)));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> b = (Map<?, ?>) ((List<?>) r.getBody().get("batches")).get(0);
        assertThat(new BigDecimal(String.valueOf(b.get("effectivePurchaseRate")))).isEqualByComparingTo("41.67");
        assertThat(stockOf(c, id)).isEqualTo(12);
        assertThat(count(c, "SELECT count(*) FROM goods_receipt_lines WHERE effective_rate = 41.67 AND free_qty = 2")).isEqualTo(1);
    }

    @Test
    void anExpiredLineRejectsTheWholeBillAndTodayIsStillAccepted() {
        Clinic c = hybridClinic("exp");
        String a = addItem(c, "Exp A", 1), b = addItem(c, "Exp B", 1);
        LocalDate today = clock.today(c.tenant().id());
        ResponseEntity<Map> r = post(c, "Acme", "E-1", List.of(line(a, "OK", future(c), 5, 0, 10), line(b, "OLD", today.minusDays(1), 5, 0, 10)));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(String.valueOf(r.getBody().get("type"))).endsWith("/batch-expired");
        assertThat(stockOf(c, a)).isEqualTo(1);
        assertThat(count(c, "SELECT count(*) FROM goods_receipts")).isZero();
        // boundary: expiring today is not "in the past"
        assertThat(post(c, "Acme", "E-2", List.of(line(a, "T", today, 5, 0, 10))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void anUnknownItemRollsTheWholeBillBack() {
        Clinic c = hybridClinic("unk");
        String a = addItem(c, "Unk A", 1);
        ResponseEntity<Map> r = post(c, "Acme", "U-1", List.of(line(a, "OK", future(c), 5, 0, 10), line(UUID.randomUUID().toString(), "X", future(c), 5, 0, 10)));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stockOf(c, a)).isEqualTo(1);
        assertThat(count(c, "SELECT count(*) FROM goods_receipts")).isZero();
        assertThat(count(c, "SELECT count(*) FROM goods_receipt_lines")).isZero();
    }

    @Test
    void validationPermissionAndModeAreEnforced() {
        Clinic c = hybridClinic("val");
        String a = addItem(c, "Val A", 0);
        assertThat(post(c, "Acme", "V-1", List.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(c, "Acme", "V-2", List.of(line(a, "Q0", future(c), 0, 0, 10))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(c, "", "V-3", List.of(line(a, "Q1", future(c), 1, 0, 10))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // a role without pharmacy:edit
        UUID viewOnly = seedRole(c.tenant().id(), "Viewer", false, new ModuleGrant("pharmacy", true, false, false, false, false, false, false));
        SeededStaff v = seedStaff(c.tenant(), viewOnly, "viewer-val@p7.test", "+9196" + String.format("%08d", PHONE.incrementAndGet()), false);
        Clinic viewer = new Clinic(c.tenant(), loginAndGetAccessToken(v), v.id());
        assertThat(post(viewer, "Acme", "V-4", List.of(line(a, "Q2", future(c), 1, 0, 10))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // an external-pharmacy clinic has no stock to receive into
        SeededTenant ext = seedTenant();
        SeededStaff es = seedStaff(ext, seedFullAccessRole(ext.id()), "ext-val@p7.test", "+9196" + String.format("%08d", PHONE.incrementAndGet()), false);
        Clinic external = new Clinic(ext, loginAndGetAccessToken(es), es.id());
        ResponseEntity<Map> r = post(external, "Acme", "V-5", List.of(line(a, "Q3", clock.today(ext.id()).plusYears(1), 1, 0, 10)));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(String.valueOf(r.getBody().get("type"))).endsWith("/pharmacy-mode-external");
    }

    @Test
    void aRetriedPostWithTheSameIdempotencyKeyPostsOnce() {
        Clinic c = hybridClinic("idem");
        String id = addItem(c, "Idem item", 0);
        Map<String, Object> body = Map.of("supplierName", "Acme", "billNumber", "I-1", "billDate", clock.today(c.tenant().id()).toString(),
                "lines", List.of(line(id, "B", future(c), 7, 0, 10)));
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> r1 = exchange("/v1/pharmacy/receipts", HttpMethod.POST, authedJsonBodyWithHeader(c.token(), "Idempotency-Key", key, body), Map.class);
        ResponseEntity<Map> r2 = exchange("/v1/pharmacy/receipts", HttpMethod.POST, authedJsonBodyWithHeader(c.token(), "Idempotency-Key", key, body), Map.class);
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r2.getBody().get("receiptId")).isEqualTo(r1.getBody().get("receiptId"));
        assertThat(stockOf(c, id)).isEqualTo(7);
    }

    @Test
    void effectiveRateRoundsHalfUp() {
        assertThat(GoodsReceiptService.effectiveRate(new BigDecimal("50"), 10, 2)).isEqualByComparingTo("41.67");
        assertThat(GoodsReceiptService.effectiveRate(new BigDecimal("50"), 10, 0)).isEqualByComparingTo("50.00");
        assertThat(GoodsReceiptService.effectiveRate(new BigDecimal("10.00"), 1, 2)).isEqualByComparingTo("3.33");
    }
}

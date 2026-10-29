package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.pharmacy.api.StockCommands;
import com.nabd.hms.pharmacy.api.StockCommands.BatchReceipt;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S18 independent QA for PHL-010 (real API, real Postgres): 300-batch session, variance with sales during the count, posting rules,
 * maker-checker edge cases, reason codes, insufficient stock, immutability, permissions, tenant isolation.
 * Case IDs refer to reports/s18-qa-testcases.md.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S18QaPhl010StockTakeApiTest extends ApiTestBase {

    private static final AtomicInteger PHONE = new AtomicInteger(7300000);
    private static final List<String> REASONS = List.of("damaged", "count_correction", "theft_loss", "expired", "other");

    @Autowired
    private StockCommands stock;

    private record Clinic(SeededTenant tenant, String token, UUID staffId) {
        UUID t() {
            return tenant.id();
        }
    }

    private String phone() {
        return "+9195" + String.format("%08d", PHONE.incrementAndGet());
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private Clinic clinic(String tag) {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), tag + UUID.randomUUID().toString().substring(0, 6) + "@q18.test", phone(), false);
        String token = loginAndGetAccessToken(staff);
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "in_house", "reason", "S18 QA")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        su().update("UPDATE staff SET name = 'QA Maker' WHERE id = ?", staff.id());
        return new Clinic(tenant, token, staff.id());
    }

    private SeededStaff colleague(Clinic c, String name, boolean approve) {
        UUID role = seedRole(c.t(), name + " " + UUID.randomUUID().toString().substring(0, 6), false,
                new ModuleGrant("pharmacy", true, true, true, false, approve, false, false));
        SeededStaff s = seedStaff(c.tenant(), role, UUID.randomUUID().toString().substring(0, 8) + "@q18.test", phone(), false);
        su().update("UPDATE staff SET name = ? WHERE id = ?", name, s.id());
        return s;
    }

    private String token(Clinic c, ModuleGrant... grants) {
        UUID role = seedRole(c.t(), "R" + UUID.randomUUID().toString().substring(0, 6), false, grants);
        return loginAndGetAccessToken(seedStaff(c.tenant(), role, UUID.randomUUID().toString().substring(0, 8) + "@q18.test", phone(), false));
    }

    private UUID item(Clinic c, String name, String cls) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", name, "isRx", !"OTC".equals(cls), "price", 50.00, "taxRatePercent", 12.00, "scheduleClass", cls)), Map.class);
        assertThat(r.getStatusCode()).as("item %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private UUID receive(Clinic c, UUID item, String no, int qty, String loc) {
        return inTenantTx(c.t(), () -> stock.receiveBatch(item, new BatchReceipt(no, LocalDate.now().plusDays(300), new BigDecimal("20.00"),
                new BigDecimal("10.0000"), qty, loc, null, null), c.staffId(), "GRN " + no).batchId());
    }

    private void sell(Clinic c, UUID item, int qty) {
        inTenantTx(c.t(), () -> stock.issue(item, qty, null, "sale"));
    }

    private int qty(UUID batch) {
        return su().queryForObject("SELECT qty_on_hand FROM stock_batches WHERE id = ?", Integer.class, batch);
    }

    private ResponseEntity<Map> call(String token, HttpMethod m, String path, Object body) {
        return exchange(path, m, authedJsonBody(token, body), Map.class);
    }

    private UUID start(Clinic c, Map<String, Object> body) {
        ResponseEntity<Map> r = call(c.token(), HttpMethod.POST, "/v1/pharmacy/stock-takes", body);
        assertThat(r.getStatusCode()).as("start %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("stockTakeId"));
    }

    private ResponseEntity<Map> counts(String token, UUID st, Map<UUID, Integer> counts) {
        List<Map<String, Object>> rows = new ArrayList<>();
        counts.forEach((b, n) -> rows.add(Map.of("batchId", b.toString(), "counted", n)));
        return call(token, HttpMethod.PUT, "/v1/pharmacy/stock-takes/" + st + "/counts", Map.of("counts", rows));
    }

    private void countOk(Clinic c, UUID st, Map<UUID, Integer> m) {
        ResponseEntity<Map> r = counts(c.token(), st, m);
        assertThat(r.getStatusCode()).as("counts %s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map> post(String token, UUID st, Map<String, Object> body) {
        return call(token, HttpMethod.POST, "/v1/pharmacy/stock-takes/" + st + "/post", body);
    }

    private static String slug(ResponseEntity<Map> r) {
        String type = String.valueOf(r.getBody() == null ? null : r.getBody().get("type"));
        return type.substring(type.lastIndexOf('/') + 1);
    }

    private int movements(UUID st) {
        return su().queryForObject("SELECT count(*) FROM pharmacy_stock_movements WHERE source_type = 'stock_take' AND source_id = ?", Integer.class, st);
    }

    private Map<String, Map> sheet(Clinic c, UUID st) {
        Map s = exchange("/v1/pharmacy/stock-takes/" + st, HttpMethod.GET, authed(c.token()), Map.class).getBody();
        Map<String, Map> out = new LinkedHashMap<>();
        ((List<Map>) s.get("lines")).forEach(l -> out.put((String) l.get("batchId"), l));
        return out;
    }

    private Map<String, Object> signer(SeededStaff s, String pin) {
        return Map.of("approverStaffId", s.id().toString(), "approverPin", pin);
    }

    // ---------------- P-API-01 ----------------

    @Test
    void pApi01_threeHundredBatchesOneMovementPerVariantBatchLockedAndPostedOnce() {
        Clinic c = clinic("big");
        List<UUID> batches = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            UUID it = item(c, "QA bulk " + i, "OTC");
            batches.add(receive(c, it, "Q" + i + "a", 12, "Rack " + (i % 9)));
            batches.add(receive(c, it, "Q" + i + "b", 8, "Rack " + (i % 9)));
        }
        UUID st = start(c, Map.of("scope", "all"));
        assertThat(sheet(c, st)).hasSize(300);
        Map<UUID, Integer> m = new LinkedHashMap<>();
        int variant = 0;
        for (int k = 0; k < 300; k++) {
            int base = k % 2 == 0 ? 12 : 8;
            int counted = k % 4 == 0 ? base - 1 : k % 9 == 0 ? base + 2 : base;
            if (counted != base) variant++;
            m.put(batches.get(k), counted);
        }
        countOk(c, st, m);
        ResponseEntity<Map> r = post(c.token(), st, Map.of());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat((List) r.getBody().get("adjustments")).hasSize(variant);
        assertThat(movements(st)).isEqualTo(variant);
        for (int k = 0; k < 300; k++) assertThat(qty(batches.get(k))).isEqualTo(m.get(batches.get(k)));
        // locked
        assertThat(slug(counts(c.token(), st, Map.of(batches.get(0), 1)))).isEqualTo("stock-take-closed");
        assertThat(call(c.token(), HttpMethod.POST, "/v1/pharmacy/stock-takes/" + st + "/cancel", Map.of()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<Map> again = post(c.token(), st, Map.of());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List) again.getBody().get("adjustments")).hasSize(variant);
        assertThat(movements(st)).isEqualTo(variant);
        assertThat(su().queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'pharmacy.stock_take.posted.v1'",
                Integer.class, c.t())).isEqualTo(1);
        assertThat(su().queryForObject("SELECT status FROM stock_takes WHERE id = ?", String.class, st)).isEqualTo("posted");
    }

    // ---------------- P-API-02 / 03 ----------------

    @Test
    void pApi02_salesAndReceiptsDuringTheCountAreNoVariance() {
        Clinic c = clinic("sales");
        UUID a = item(c, "QA sold", "OTC");
        UUID ba = receive(c, a, "A1", 50, "S1");
        UUID b = item(c, "QA untouched", "OTC");
        UUID bb = receive(c, b, "B1", 40, "S1");
        UUID st = start(c, Map.of("scope", "all"));
        sell(c, a, 5);
        UUID late = receive(c, b, "B2", 7, "S1"); // received after the snapshot: not on this sheet
        assertThat(sheet(c, st)).doesNotContainKey(late.toString());
        countOk(c, st, Map.of(ba, 45, bb, 40));
        sell(c, a, 3);
        Map<String, Map> s = sheet(c, st);
        assertThat(s.get(ba.toString())).containsEntry("expectedQty", 45).containsEntry("variance", 0);
        ResponseEntity<Map> r = post(c.token(), st, Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List) r.getBody().get("adjustments")).isEmpty();
        assertThat(qty(ba)).isEqualTo(42);
        assertThat(qty(bb)).isEqualTo(40);
        assertThat(qty(late)).isEqualTo(7);
    }

    @Test
    void pApi03_aShortfallThatSalesAfterTheCountMadeImpossibleIsRefusedCleanly() {
        Clinic c = clinic("short");
        UUID it = item(c, "QA short", "OTC");
        UUID bt = receive(c, it, "C1", 10, "S1");
        UUID st = start(c, Map.of("scope", "all"));
        countOk(c, st, Map.of(bt, 2)); // variance -8
        sell(c, it, 5); // the system lets 5 go after the count: the batch holds 5
        ResponseEntity<Map> r = post(c.token(), st, Map.of());
        assertThat(r.getStatusCode()).as("post %s", r.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(slug(r)).isEqualTo("insufficient-stock");
        assertThat(movements(st)).isZero();
        assertThat(qty(bt)).isEqualTo(5);
        assertThat(su().queryForObject("SELECT status FROM stock_takes WHERE id = ?", String.class, st)).isEqualTo("open");
    }

    // ---------------- P-API-04 / 05 ----------------

    @Test
    void pApi04_twoConcurrentPostsWriteOnce() throws Exception {
        Clinic c = clinic("race");
        UUID it = item(c, "QA race", "OTC");
        UUID b1 = receive(c, it, "R1", 10, null);
        UUID b2 = receive(c, it, "R2", 10, null);
        UUID st = start(c, Map.of("scope", "all"));
        countOk(c, st, Map.of(b1, 9, b2, 11));
        CompletableFuture<ResponseEntity<Map>> x = CompletableFuture.supplyAsync(() -> post(c.token(), st, Map.of()));
        CompletableFuture<ResponseEntity<Map>> y = CompletableFuture.supplyAsync(() -> post(c.token(), st, Map.of()));
        assertThat(List.of(x.get().getStatusCode(), y.get().getStatusCode())).containsOnly(HttpStatus.OK);
        assertThat(movements(st)).isEqualTo(2);
        assertThat(qty(b1)).isEqualTo(9);
        assertThat(qty(b2)).isEqualTo(11);
    }

    @Test
    void pApi05_aCancelledSessionCannotBePostedOrCounted() {
        Clinic c = clinic("cancel");
        UUID it = item(c, "QA cancel", "OTC");
        UUID b = receive(c, it, "X1", 5, null);
        UUID st = start(c, Map.of("scope", "all"));
        countOk(c, st, Map.of(b, 1));
        assertThat(call(c.token(), HttpMethod.POST, "/v1/pharmacy/stock-takes/" + st + "/cancel", Map.of()).getBody()).containsEntry("status", "cancelled");
        ResponseEntity<Map> p = post(c.token(), st, Map.of());
        assertThat(p.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(slug(p)).isEqualTo("stock-take-closed");
        assertThat(slug(counts(c.token(), st, Map.of(b, 2)))).isEqualTo("stock-take-closed");
        assertThat(call(c.token(), HttpMethod.POST, "/v1/pharmacy/stock-takes/" + st + "/cancel", Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(qty(b)).isEqualTo(5);
        assertThat(movements(st)).isZero();
    }

    // ---------------- P-API-06 / 07 / 08 maker-checker ----------------

    @Test
    void pApi06_controlledVarianceSecondSignerEdgeCases() {
        Clinic c = clinic("mc");
        UUID nd = item(c, "QA NDPS", "IN_NDPS");
        UUID bn = receive(c, nd, "N1", 20, "Safe");
        SeededStaff checker = colleague(c, "QA Checker", true);
        SeededStaff junior = colleague(c, "QA Junior", false);
        SeededStaff suspended = colleague(c, "QA Suspended", true);
        su().update("UPDATE staff SET status = 'suspended' WHERE id = ?", suspended.id());
        Clinic other = clinic("mc2");
        SeededStaff foreign = colleague(other, "QA Foreign", true);
        UUID st = start(c, Map.of("scope", "all"));
        countOk(c, st, Map.of(bn, 18));

        assertThat(slug(post(c.token(), st, Map.of()))).as("no approver").isEqualTo("approval-required");
        assertThat(slug(post(c.token(), st, Map.of("approverStaffId", c.staffId().toString(), "approverPin", STAFF_PIN)))).as("maker").isEqualTo("approval-required");
        assertThat(slug(post(c.token(), st, signer(junior, STAFF_PIN)))).as("no adjust_approve").isEqualTo("approval-required");
        ResponseEntity<Map> fr = post(c.token(), st, signer(foreign, STAFF_PIN));
        assertThat(fr.getStatusCode()).as("approver of another clinic %s", fr.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<Map> sr = post(c.token(), st, signer(suspended, STAFF_PIN));
        assertThat(sr.getStatusCode()).as("suspended approver %s", sr.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(slug(post(c.token(), st, Map.of("approverStaffId", UUID.randomUUID().toString(), "approverPin", STAFF_PIN)))).isEqualTo("approval-required");
        assertThat(slug(post(c.token(), st, signer(checker, "1111")))).isEqualTo("approver-pin-invalid");
        assertThat(movements(st)).isZero();
        assertThat(qty(bn)).isEqualTo(20);
        ResponseEntity<Map> ok = post(c.token(), st, signer(checker, STAFF_PIN));
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("postedByName", "QA Maker").containsEntry("approvedByName", "QA Checker");
        assertThat(su().queryForMap("SELECT actor_id, approved_by FROM pharmacy_stock_movements WHERE source_id = ?", st))
                .containsEntry("actor_id", c.staffId()).containsEntry("approved_by", checker.id());
        // the approver list never offers the maker, a junior, a suspended colleague or another clinic's staff
        List<Map> list = exchange("/v1/pharmacy/adjustment-approvers", HttpMethod.GET, authed(c.token()), List.class).getBody();
        assertThat(list).extracting(x -> x.get("name")).contains("QA Checker").doesNotContain("QA Maker", "QA Junior", "QA Suspended", "QA Foreign");
    }

    @Test
    void pApi07_positiveControlledVarianceNeedsTheSignerAndZeroDoesNot() {
        Clinic c = clinic("h1");
        UUID h1 = item(c, "QA H1", "IN_H1");
        UUID bh = receive(c, h1, "H1", 10, null);
        SeededStaff checker = colleague(c, "QA Checker", true);
        UUID st = start(c, Map.of("scope", "all"));
        countOk(c, st, Map.of(bh, 10));
        assertThat(post(c.token(), st, Map.of()).getStatusCode()).as("zero variance on a controlled batch").isEqualTo(HttpStatus.OK);
        UUID st2 = start(c, Map.of("scope", "all"));
        countOk(c, st2, Map.of(bh, 13));
        assertThat(slug(post(c.token(), st2, Map.of()))).as("found extra controlled stock").isEqualTo("approval-required");
        assertThat(post(c.token(), st2, signer(checker, STAFF_PIN)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(qty(bh)).isEqualTo(13);
    }

    @Test
    void pApi08_approverPinLockout() {
        Clinic c = clinic("lock");
        UUID nd = item(c, "QA NDPS lock", "IN_NDPS");
        UUID b = receive(c, nd, "L1", 10, null);
        SeededStaff checker = colleague(c, "QA Checker", true);
        for (int i = 0; i < 5; i++) {
            ResponseEntity<Map> r = call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + b + "/adjustments",
                    Map.of("delta", -1, "reasonCode", "damaged", "approverStaffId", checker.id().toString(), "approverPin", "000" + i));
            assertThat(slug(r)).isEqualTo("approver-pin-invalid");
        }
        ResponseEntity<Map> locked = call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + b + "/adjustments",
                Map.of("delta", -1, "reasonCode", "damaged", "approverStaffId", checker.id().toString(), "approverPin", STAFF_PIN));
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(qty(b)).isEqualTo(10);
    }

    // ---------------- P-API-09 / 10 reasons ----------------

    @Test
    void pApi09_batchAdjustmentReasonCodes() {
        Clinic c = clinic("rsn");
        UUID it = item(c, "QA reason", "OTC");
        UUID b = receive(c, it, "R1", 20, null);
        String path = "/v1/pharmacy/batches/" + b + "/adjustments";
        assertThat(call(c.token(), HttpMethod.POST, path, Map.of("delta", -1, "reasonCode", "lost")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(c.token(), HttpMethod.POST, path, Map.of("delta", -1)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(c.token(), HttpMethod.POST, path, Map.of("delta", -1, "reasonCode", "other", "note", "  ")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        for (String code : REASONS) {
            Map<String, Object> body = new LinkedHashMap<>(Map.of("delta", -1, "reasonCode", code));
            if ("other".equals(code)) body.put("note", "QA sample");
            assertThat(call(c.token(), HttpMethod.POST, path, body).getStatusCode()).as(code).isEqualTo(HttpStatus.OK);
        }
        assertThat(qty(b)).isEqualTo(15);
    }

    @Test
    void pApi10_everyAdjustmentMovementCarriesACodedReason() {
        Clinic c = clinic("legacy");
        UUID it = item(c, "QA legacy", "OTC");
        receive(c, it, "G1", 10, null);
        // the Pharmacy panel's +/- for a non-controlled item still calls the item-level endpoint with a free-text reason
        ResponseEntity<Map> r = call(c.token(), HttpMethod.POST, "/v1/pharmacy/items/" + it + "/stock-adjustments", Map.of("delta", -2, "reason", "broke two"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> codes = su().queryForList("SELECT coalesce(reason_code, '<null>') FROM pharmacy_stock_movements WHERE item_id = ? AND kind = 'adjustment'",
                String.class, it);
        assertThat(codes).as("AC4: adjustment reason codes come from the fixed list").isNotEmpty().allMatch(REASONS::contains);
    }

    // ---------------- P-API-11 / 12 ----------------

    @Test
    void pApi11_insufficientStockOnANegativeAdjustment() {
        Clinic c = clinic("neg");
        UUID it = item(c, "QA neg", "OTC");
        UUID b = receive(c, it, "N1", 3, null);
        ResponseEntity<Map> r = call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + b + "/adjustments", Map.of("delta", -4, "reasonCode", "damaged"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(slug(r)).isEqualTo("insufficient-stock");
        assertThat(call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + b + "/adjustments", Map.of("setTo", -1, "reasonCode", "damaged")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + b + "/adjustments", Map.of("setTo", 3, "reasonCode", "damaged")).getStatusCode())
                .as("no change").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(qty(b)).isEqualTo(3);
        assertThat(call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + UUID.randomUUID() + "/adjustments", Map.of("delta", 1, "reasonCode", "damaged"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void pApi12_adjustmentsAndPostedSessionsCannotBeDeleted() {
        Clinic c = clinic("imm");
        UUID it = item(c, "QA imm", "OTC");
        UUID b = receive(c, it, "I1", 6, null);
        assertThat(call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + b + "/adjustments", Map.of("delta", -1, "reasonCode", "damaged")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        UUID st = start(c, Map.of("scope", "all"));
        countOk(c, st, Map.of(b, 4));
        assertThat(post(c.token(), st, Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        int before = su().queryForObject("SELECT count(*) FROM pharmacy_stock_movements WHERE batch_id = ? AND kind = 'adjustment'", Integer.class, b);
        assertThat(before).isEqualTo(2);
        assertThat(exchange("/v1/pharmacy/stock-takes/" + st, HttpMethod.DELETE, authed(c.token()), Map.class).getStatusCode().value()).isIn(403, 404, 405);
        assertThat(exchange("/v1/pharmacy/batches/" + b + "/adjustments", HttpMethod.DELETE, authed(c.token()), Map.class).getStatusCode().value()).isIn(403, 404, 405);
        assertThatThrownBy(() -> inTenantTx(c.t(), () -> jdbc.update("DELETE FROM pharmacy_stock_movements WHERE batch_id = ?", b))).isNotNull();
        assertThatThrownBy(() -> inTenantTx(c.t(), () -> jdbc.update("UPDATE pharmacy_stock_movements SET delta = 0 WHERE batch_id = ?", b))).isNotNull();
        assertThatThrownBy(() -> inTenantTx(c.t(), () -> jdbc.update("DELETE FROM stock_takes WHERE id = ?", st))).isNotNull();
        assertThat(su().queryForObject("SELECT count(*) FROM pharmacy_stock_movements WHERE batch_id = ? AND kind = 'adjustment'", Integer.class, b)).isEqualTo(2);
    }

    // ---------------- P-API-13 / 14 / 15 ----------------

    @Test
    void pApi13_permissions() {
        Clinic c = clinic("perm");
        UUID it = item(c, "QA perm", "OTC");
        UUID b = receive(c, it, "P1", 5, null);
        UUID st = start(c, Map.of("scope", "all"));
        String viewer = token(c, new ModuleGrant("pharmacy", true, false, false, false, false, false, false));
        String none = token(c, fullGrant("queue"));
        assertThat(exchange("/v1/pharmacy/stock-takes", HttpMethod.GET, authed(viewer), List.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/pharmacy/stock-takes", HttpMethod.GET, authed(none), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/pharmacy/stock-takes/" + st, HttpMethod.GET, authed(none), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        for (String t : List.of(viewer, none)) {
            assertThat(call(t, HttpMethod.POST, "/v1/pharmacy/stock-takes", Map.of("scope", "all")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(counts(t, st, Map.of(b, 1)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(post(t, st, Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(call(t, HttpMethod.POST, "/v1/pharmacy/stock-takes/" + st + "/cancel", Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(call(t, HttpMethod.POST, "/v1/pharmacy/batches/" + b + "/adjustments", Map.of("delta", 1, "reasonCode", "damaged")).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exchange("/v1/pharmacy/adjustment-approvers", HttpMethod.GET, authed(t), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(qty(b)).isEqualTo(5);
    }

    @Test
    void pApi14_anotherClinicCannotTouchTheSessionOrBatches() {
        Clinic a = clinic("ta");
        UUID ia = item(a, "QA A", "OTC");
        UUID ba = receive(a, ia, "A1", 9, null);
        UUID sa = start(a, Map.of("scope", "all"));
        Clinic b = clinic("tb");
        UUID ib = item(b, "QA B", "OTC");
        receive(b, ib, "B1", 4, null);
        UUID sb = start(b, Map.of("scope", "all"));
        assertThat(exchange("/v1/pharmacy/stock-takes/" + sa, HttpMethod.GET, authed(b.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(counts(b.token(), sa, Map.of(ba, 1)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(b.token(), sa, Map.of()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(b.token(), HttpMethod.POST, "/v1/pharmacy/stock-takes/" + sa + "/cancel", Map.of()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(b.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + ba + "/adjustments", Map.of("delta", -1, "reasonCode", "damaged")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> cross = counts(b.token(), sb, Map.of(ba, 1));
        assertThat(cross.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(slug(cross)).isEqualTo("batch-not-in-stock-take");
        assertThat(qty(ba)).isEqualTo(9);
        assertThat(su().queryForObject("SELECT status FROM stock_takes WHERE id = ?", String.class, sa)).isEqualTo("open");
    }

    @Test
    void pApi15_countValidationAndRecount() {
        Clinic c = clinic("val");
        UUID it = item(c, "QA val", "OTC");
        UUID b = receive(c, it, "V1", 10, null);
        UUID st = start(c, Map.of("scope", "all"));
        assertThat(counts(c.token(), st, Map.of(b, -1)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(c.token(), HttpMethod.PUT, "/v1/pharmacy/stock-takes/" + st + "/counts", Map.of("counts", List.of(
                Map.of("batchId", b.toString(), "counted", 3), Map.of("batchId", b.toString(), "counted", 4)))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        countOk(c, st, Map.of(b, 7));
        countOk(c, st, Map.of(b, 9));
        assertThat(sheet(c, st).get(b.toString())).containsEntry("countedQty", 9).containsEntry("variance", -1);
        ResponseEntity<Map> p = post(c.token(), st, Map.of());
        assertThat((List<Map>) p.getBody().get("adjustments")).singleElement().satisfies(x -> assertThat(x).containsEntry("delta", -1));
        assertThat(qty(b)).isEqualTo(9);
    }
}

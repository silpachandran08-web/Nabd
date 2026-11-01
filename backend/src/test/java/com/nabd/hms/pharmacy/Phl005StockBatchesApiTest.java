package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PHL-005 + PHL-006: stock by batch, FEFO pick, expired block, override audit, batch correction, tenant isolation, scale. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl005StockBatchesApiTest extends ApiTestBase {

    private static final AtomicInteger PHONE = new AtomicInteger(7000000);

    @Autowired
    private StockCommands stock;
    @Autowired
    private ClinicClock clock;

    private record Clinic(SeededTenant tenant, String token, UUID staffId) {
        UUID t() {
            return tenant.id();
        }
    }

    private Clinic clinic(String tag, String mode) {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = com.nabd.hms.support.ClinicianFixture.licensed(seedStaff(tenant, seedFullAccessRole(tenant.id()),
                tag + "@p5.test", "+9196" + String.format("%08d", PHONE.incrementAndGet()), false));
        String token = loginAndGetAccessToken(staff);
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", mode, "reason", "PHL-005 test")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Clinic(tenant, token, staff.id());
    }

    private UUID item(Clinic c, String name) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", name, "isRx", false, "price", 100.00, "taxRatePercent", 12.00)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private LocalDate today(Clinic c) {
        return clock.today(c.t());
    }

    private UUID receive(Clinic c, UUID item, String no, LocalDate expiry, int qty) {
        return inTenantTx(c.t(), () -> stock.receiveBatch(item, new BatchReceipt(no, expiry, new BigDecimal("12.00"), new BigDecimal("7.5000"),
                qty, "A1", null, null), c.staffId(), "GRN " + no).batchId());
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private int batchQty(UUID batch) {
        return su().queryForObject("SELECT qty_on_hand FROM stock_batches WHERE id = ?", Integer.class, batch);
    }

    private int cache(UUID item) {
        return su().queryForObject("SELECT stock_qty FROM charge_catalogue WHERE id = ?", Integer.class, item);
    }

    private static String slug(Runnable r) {
        try {
            r.run();
            return "ok";
        } catch (ApiException e) {
            return e.typeSlug();
        }
    }

    // ---------------- PHL-005 ----------------

    @Test
    void inHouseReceiptNeedsBatchNoExpiryMrpRateAndQty() {
        Clinic c = clinic("recv", "in_house");
        UUID it = item(c, "Recv item");
        LocalDate far = today(c).plusDays(400);
        BigDecimal m = new BigDecimal("10.00");
        BigDecimal r = new BigDecimal("6.0000");
        for (BatchReceipt bad : List.of(new BatchReceipt(null, far, m, r, 5, null, null, null), new BatchReceipt(" ", far, m, r, 5, null, null, null),
                new BatchReceipt("L1", null, m, r, 5, null, null, null), new BatchReceipt("L1", far, null, r, 5, null, null, null),
                new BatchReceipt("L1", far, m, null, 5, null, null, null))) {
            assertThat(slug(() -> inTenantTx(c.t(), () -> stock.receiveBatch(it, bad, c.staffId(), "x")))).isEqualTo("batch-details-required");
        }
        assertThat(slug(() -> inTenantTx(c.t(), () -> stock.receiveBatch(it, new BatchReceipt("L1", far, m, r, 0, null, null, null), c.staffId(), "x"))))
                .isEqualTo("validation-failed");
        assertThat(slug(() -> inTenantTx(c.t(), () -> stock.receiveBatch(it, new BatchReceipt("L1", today(c).minusDays(1), m, r, 5, null, null, null), c.staffId(), "x"))))
                .isEqualTo("batch-expired");
        assertThat(slug(() -> inTenantTx(c.t(), () -> stock.receive(it, 5, c.staffId(), "no batch details")))).isEqualTo("batch-details-required");
        UUID b = receive(c, it, "L1", far, 5);
        assertThat(receive(c, it, "L1", far, 3)).as("same number and expiry tops the batch up").isEqualTo(b);
        assertThat(batchQty(b)).isEqualTo(8);
        assertThat(cache(it)).isEqualTo(8);
    }

    @Test
    void itemListShowsTheSumAndTheDrawerListsOnlyNonEmptyBatchesByExpiry() {
        Clinic c = clinic("list", "in_house");
        UUID it = item(c, "Zeta mix");
        LocalDate d = today(c);
        UUID late = receive(c, it, "LATE", d.plusDays(300), 12);
        UUID early = receive(c, it, "EARLY", d.plusDays(100), 30);
        UUID gone = receive(c, it, "GONE", d.plusDays(50), 5);
        inTenantTx(c.t(), () -> stock.adjustBatch(it, gone, -5, c.staffId(), "count_correction", "count"));

        Map page = exchange("/v1/pharmacy/stock?q=zeta", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        List<Map> items = (List<Map>) page.get("items");
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i).containsEntry("onHand", 42).containsEntry("batchCount", 2);
            assertThat(i.get("nearestExpiry")).isEqualTo(d.plusDays(100).toString());
            assertThat(((Number) i.get("value")).doubleValue()).isEqualTo(42 * 7.5);
        });
        List<Map> drawer = exchange("/v1/pharmacy/items/" + it + "/batches", HttpMethod.GET, authed(c.token()), List.class).getBody();
        assertThat(drawer).extracting(x -> x.get("batchId")).containsExactly(early.toString(), late.toString());
        assertThat(drawer.get(0)).containsEntry("batchNo", "EARLY").containsEntry("qtyOnHand", 30).containsEntry("status", "active")
                .containsEntry("location", "A1");
        assertThat(exchange("/v1/pharmacy/items/" + it + "/batches?includeEmpty=true", HttpMethod.GET, authed(c.token()), List.class).getBody()).hasSize(3);
        assertThat(exchange("/v1/pharmacy/items/" + UUID.randomUUID() + "/batches", HttpMethod.GET, authed(c.token()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // expiringWithinDays keeps only items with a batch that expires inside the window
        assertThat((List) exchange("/v1/pharmacy/stock?q=zeta&expiringWithinDays=60", HttpMethod.GET, authed(c.token()), Map.class).getBody().get("items")).isEmpty();
        assertThat((List) exchange("/v1/pharmacy/stock?q=zeta&expiringWithinDays=120", HttpMethod.GET, authed(c.token()), Map.class).getBody().get("items")).hasSize(1);
    }

    @Test
    void everyMovementCarriesItsBatchAndTheBatchsOwnBeforeAndAfter() {
        Clinic c = clinic("mov", "in_house");
        UUID it = item(c, "Mov item");
        UUID a = receive(c, it, "A", today(c).plusDays(60), 5);
        UUID b = receive(c, it, "B", today(c).plusDays(200), 50);
        inTenantTx(c.t(), () -> stock.issue(it, 8, null, "sale"));
        List<Map<String, Object>> rows = su().queryForList("SELECT batch_id, kind, delta, qty_before, qty_after FROM pharmacy_stock_movements " +
                "WHERE item_id = ? ORDER BY seq", it);
        assertThat(rows).hasSize(4);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("batch_id")).isNotNull();
            assertThat((Integer) r.get("qty_after")).isGreaterThanOrEqualTo(0);
            assertThat((Integer) r.get("qty_after")).isEqualTo((Integer) r.get("qty_before") + (Integer) r.get("delta"));
        });
        assertThat(rows.get(2)).containsEntry("kind", "dispense").containsEntry("delta", -5).containsEntry("qty_before", 5).containsEntry("qty_after", 0);
        assertThat(rows.get(3)).containsEntry("kind", "dispense").containsEntry("delta", -3).containsEntry("qty_before", 50).containsEntry("qty_after", 47);
        assertThat(rows.get(2).get("batch_id").toString()).isEqualTo(a.toString());
        assertThat(rows.get(3).get("batch_id").toString()).isEqualTo(b.toString());
        List<Map> timeline = exchange("/v1/pharmacy/batches/" + b + "/movements", HttpMethod.GET, authed(c.token()), List.class).getBody();
        assertThat(timeline).extracting(x -> x.get("kind")).containsExactly("dispense", "receipt");
        // the database refuses a negative batch
        assertThatThrownBy(() -> su().update("UPDATE stock_batches SET qty_on_hand = -1 WHERE id = ?", a)).isInstanceOf(Exception.class);
    }

    @Test
    void backfillGivesEveryStockedItemOneOpeningBatchEqualToItsStockAndIsIdempotent() throws Exception {
        Clinic c = clinic("mig", "hybrid");
        for (int i = 0; i < 3; i++) {
            su().update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, stock_qty, active) VALUES (?,?,?,?,?,?,true)",
                    c.t(), "OLD-" + i, "Old stock " + i, "Pharmacy", 10, i * 7);
        }
        Path f = Path.of("../db/migrations/V2891__stock_batches_backfill.sql");
        if (!Files.exists(f)) {
            f = Path.of("db/migrations/V2891__stock_batches_backfill.sql");
        }
        String sql = Files.readString(f);
        su().execute(sql);
        su().execute(sql); // second run: nothing to add, the sum check still passes
        List<Map<String, Object>> rows = su().queryForList("SELECT c.stock_qty, count(b.id) AS n, sum(b.qty_on_hand) AS s, max(b.batch_no) AS no, " +
                "bool_and(b.expiry_date IS NULL) AS unk FROM charge_catalogue c LEFT JOIN stock_batches b ON b.item_id = c.id WHERE c.tenant_id = ? " +
                "AND c.code LIKE 'OLD-%' GROUP BY c.id, c.stock_qty ORDER BY c.stock_qty", c.t());
        assertThat(rows).hasSize(3).allSatisfy(r -> {
            assertThat(r.get("n")).isEqualTo(1L);
            assertThat(((Number) r.get("s")).intValue()).isEqualTo(r.get("stock_qty"));
            assertThat(r).containsEntry("no", "OPENING").containsEntry("unk", true);
        });
        assertThat(su().queryForObject("SELECT count(*) FROM pharmacy_stock_movements m JOIN charge_catalogue c ON c.id = m.item_id " +
                "WHERE c.tenant_id = ? AND c.code LIKE 'OLD-%' AND m.kind = 'opening'", Integer.class, c.t())).isEqualTo(2); // the 0-unit item has no movement
        // and the pre-existing stock sells through the commands (fixtures that set stock_qty directly keep working)
        UUID it = su().queryForObject("SELECT id FROM charge_catalogue WHERE tenant_id = ? AND code = 'OLD-2'", UUID.class, c.t());
        inTenantTx(c.t(), () -> stock.issue(it, 4, null, "sale"));
        assertThat(cache(it)).isEqualTo(10);
    }

    @Test
    void stockSetDirectlyWithoutABatchBecomesTheOpeningBatchOnFirstUse() {
        Clinic c = clinic("heal", "hybrid");
        UUID id = UUID.randomUUID();
        su().update("INSERT INTO charge_catalogue (id, tenant_id, code, name, category, base_amount, stock_qty, active) VALUES (?,?,?,?,?,?,?,true)",
                id, c.t(), "FIX-1", "Fixture", "Pharmacy", 10, 9);
        inTenantTx(c.t(), () -> stock.issue(id, 2, null, "sale"));
        assertThat(cache(id)).isEqualTo(7);
        assertThat(su().queryForObject("SELECT qty_on_hand FROM stock_batches WHERE item_id = ? AND batch_no = 'OPENING'", Integer.class, id)).isEqualTo(7);
    }

    @Test
    void parallelIssuesNeverTakeABatchBelowZero() throws Exception {
        Clinic c = clinic("race", "in_house");
        UUID it = item(c, "Race item");
        UUID b = receive(c, it, "R1", today(c).plusDays(90), 20);
        ExecutorService pool = Executors.newFixedThreadPool(15);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return slug(() -> inTenantTx(c.t(), () -> stock.issue(it, 1, null, "sale")));
            }));
        }
        go.countDown();
        List<String> out = new ArrayList<>();
        for (Future<String> f : fs) {
            out.add(f.get());
        }
        pool.shutdown();
        assertThat(Collections.frequency(out, "ok")).isEqualTo(20);
        assertThat(Collections.frequency(out, "insufficient-stock")).isEqualTo(10);
        assertThat(batchQty(b)).isZero();
        assertThat(cache(it)).isZero();
    }

    @Test
    void hybridUsesOneImplicitBatchAndShowsNoBatchFields() {
        Clinic c = clinic("hyb", "hybrid");
        UUID it = item(c, "Hyb item");
        inTenantTx(c.t(), () -> stock.receive(it, 10, c.staffId(), "grn"));
        inTenantTx(c.t(), () -> stock.receiveBatch(it, new BatchReceipt("IGNORED", today(c).plusDays(9), null, null, 5, "X", null, null), c.staffId(), "grn"));
        List<Map> bs = exchange("/v1/pharmacy/items/" + it + "/batches", HttpMethod.GET, authed(c.token()), List.class).getBody();
        assertThat(bs).singleElement().satisfies(b -> {
            assertThat(b).containsEntry("qtyOnHand", 15);
            assertThat(b.get("batchNo")).isNull();
            assertThat(b.get("expiryDate")).isNull();
            assertThat(b.get("mrp")).isNull();
            assertThat(b.get("purchaseRate")).isNull();
            assertThat(b.get("location")).isNull();
        });
        Map row = ((List<Map>) exchange("/v1/pharmacy/stock", HttpMethod.GET, authed(c.token()), Map.class).getBody().get("items")).get(0);
        assertThat(row).containsEntry("onHand", 15).containsEntry("batchCount", 0);
        assertThat(row.get("nearestExpiry")).isNull();
        ResponseEntity<Map> patch = exchange("/v1/pharmacy/batches/" + bs.get(0).get("batchId"), HttpMethod.PATCH,
                authedJsonBody(c.token(), Map.of("batchNo", "X", "reason", "try")), Map.class);
        assertThat(patch.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(patch.getBody().toString()).contains("feature-requires-inhouse");
        // the FEFO preview works in Hybrid on the implicit batch
        Map pick = exchange("/v1/pharmacy/picks", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lines", List.of(Map.of("itemId", it.toString(), "qty", 4)))), Map.class).getBody();
        assertThat(((List<Map>) ((List<Map>) pick.get("lines")).get(0).get("picks"))).singleElement().satisfies(p -> assertThat(p).containsEntry("qty", 4));
    }

    // ---------------- PHL-006 ----------------

    @Test
    void eightUnitsComeFiveFromTheEarlierBatchAndThreeFromTheNextAndThePreviewChangesNothing() {
        Clinic c = clinic("fefo", "in_house");
        UUID it = item(c, "Fefo item");
        UUID a = receive(c, it, "A", today(c).plusDays(60), 5);
        UUID b = receive(c, it, "B", today(c).plusDays(200), 50);
        Map body = exchange("/v1/pharmacy/picks", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lines", List.of(Map.of("itemId", it.toString(), "qty", 8)))), Map.class).getBody();
        List<Map> picks = (List<Map>) ((List<Map>) body.get("lines")).get(0).get("picks");
        assertThat(picks).extracting(p -> p.get("batchId")).containsExactly(a.toString(), b.toString());
        assertThat(picks).extracting(p -> p.get("qty")).containsExactly(5, 3);
        assertThat(cache(it)).isEqualTo(55);
        assertThat(batchQty(a)).isEqualTo(5);

        inTenantTx(c.t(), () -> stock.issue(it, 8, null, "sale"));
        assertThat(batchQty(a)).isZero();
        assertThat(batchQty(b)).isEqualTo(47);
        assertThat(cache(it)).isEqualTo(47);
        assertThat(su().queryForObject("SELECT count(*) FROM pharmacy_stock_movements WHERE item_id = ? AND kind = 'dispense'", Integer.class, it)).isEqualTo(2);

        // more than there is: 409 on the preview, 400 on the command; nothing moves
        ResponseEntity<Map> r = exchange("/v1/pharmacy/picks", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lines", List.of(Map.of("itemId", it.toString(), "qty", 999)))), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(slug(() -> inTenantTx(c.t(), () -> stock.issue(it, 999, null, "sale")))).isEqualTo("insufficient-stock");
        assertThat(cache(it)).isEqualTo(47);
        // validation: empty lines, qty 0
        assertThat(exchange("/v1/pharmacy/picks", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lines", List.of())), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/pharmacy/picks", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lines", List.of(Map.of("itemId", it.toString(), "qty", 0)))), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anExpiredBatchIsNeverSoldAndChoosingItIsRefusedWithNothingBilled() {
        Clinic c = clinic("exp", "in_house");
        UUID it = item(c, "Exp item");
        UUID old = receive(c, it, "OLD", today(c).plusDays(5), 10);
        UUID fresh = receive(c, it, "FRESH", today(c).plusDays(300), 10);
        su().update("UPDATE stock_batches SET expiry_date = ? WHERE id = ?", java.sql.Date.valueOf(today(c).minusDays(1)), old);
        int moves = su().queryForObject("SELECT count(*) FROM pharmacy_stock_movements WHERE item_id = ?", Integer.class, it);

        assertThat(slug(() -> inTenantTx(c.t(), () -> stock.issueFromBatch(it, old, 1, null, c.staffId(), "customer asked")))).isEqualTo("batch-expired");
        assertThat(batchQty(old)).isEqualTo(10);
        assertThat(su().queryForObject("SELECT count(*) FROM pharmacy_stock_movements WHERE item_id = ?", Integer.class, it)).isEqualTo(moves);

        inTenantTx(c.t(), () -> stock.issue(it, 4, null, "sale")); // the default pick skips the expired batch
        assertThat(batchQty(old)).isEqualTo(10);
        assertThat(batchQty(fresh)).isEqualTo(6);
        assertThat(slug(() -> inTenantTx(c.t(), () -> stock.issue(it, 7, null, "sale")))).as("20 on the shelf, 6 sellable").isEqualTo("batch-expired");
        assertThat(cache(it)).isEqualTo(16);
        ResponseEntity<Map> preview = exchange("/v1/pharmacy/picks", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lines", List.of(Map.of("itemId", it.toString(), "qty", 7)))), Map.class);
        assertThat(preview.getStatusCode().value()).isEqualTo(422);
        assertThat(preview.getBody().toString()).contains("batch-expired");
    }

    @Test
    void aBatchThatExpiresMidCourseIsFlaggedAndSkippedWhenAnotherBatchExists() {
        Clinic c = clinic("mid", "in_house");
        UUID it = item(c, "Course item");
        UUID soon = receive(c, it, "SOON", today(c).plusDays(20), 10);
        UUID later = receive(c, it, "LATER", today(c).plusDays(200), 50);
        java.util.function.BiFunction<Integer, Integer, List<Map>> preview = (qty, days) -> {
            Map body = exchange("/v1/pharmacy/picks", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lines",
                    List.of(Map.of("itemId", it.toString(), "qty", qty, "courseDays", days)))), Map.class).getBody();
            return (List<Map>) ((List<Map>) body.get("lines")).get(0).get("picks");
        };
        assertThat(preview.apply(5, 30)).singleElement().satisfies(p -> {
            assertThat(p).containsEntry("batchId", later.toString());
            assertThat(p.get("warning")).isNull();
        });
        List<Map> forced = preview.apply(55, 30);
        assertThat(forced).extracting(p -> p.get("batchId")).containsExactly(soon.toString(), later.toString());
        assertThat(forced.get(0)).containsEntry("warning", "expires-mid-course");
        assertThat(preview.apply(5, 20)).as("expiring exactly when the course ends is fine").singleElement().satisfies(p -> assertThat(p).containsEntry("batchId", soon.toString()));
    }

    @Test
    void overridingFefoNeedsAReasonThatIsStoredOnTheMovementAndAudited() {
        Clinic c = clinic("ovr", "in_house");
        UUID it = item(c, "Ovr item");
        receive(c, it, "A", today(c).plusDays(60), 5);
        UUID b = receive(c, it, "B", today(c).plusDays(200), 50);
        for (String blank : new String[]{null, "", "   "}) {
            assertThat(slug(() -> inTenantTx(c.t(), () -> stock.issueFromBatch(it, b, 2, null, c.staffId(), blank)))).isEqualTo("override-reason-required");
        }
        assertThat(batchQty(b)).isEqualTo(50);
        assertThat(slug(() -> inTenantTx(c.t(), () -> stock.issueFromBatch(it, b, 51, null, c.staffId(), "longer pack")))).isEqualTo("insufficient-stock");
        assertThat(slug(() -> inTenantTx(c.t(), () -> stock.issueFromBatch(it, UUID.randomUUID(), 1, null, c.staffId(), "longer pack")))).isEqualTo("not-found");

        inTenantTx(c.t(), () -> stock.issueFromBatch(it, b, 2, null, c.staffId(), "Patient wants a longer-dated pack"));
        assertThat(batchQty(b)).isEqualTo(48);
        assertThat(su().queryForObject("SELECT fefo_override_reason FROM pharmacy_stock_movements WHERE batch_id = ? AND kind = 'dispense'", String.class, b))
                .isEqualTo("Patient wants a longer-dated pack");
        Map<String, Object> audit = su().queryForMap("SELECT after::text AS after FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.fefo_overridden'", c.t());
        assertThat(audit.get("after").toString()).contains("Patient wants a longer-dated pack").contains(b.toString());
    }

    // ---------------- PATCH, isolation, events ----------------

    @Test
    void correctingABatchIsAuditedAcceptsAMonthAndRefusesWhatWouldBreakHistory() {
        Clinic c = clinic("patch", "in_house");
        UUID it = item(c, "Patch item");
        UUID a = receive(c, it, "A", today(c).plusDays(400), 10);
        UUID other = receive(c, it, "OTHER", today(c).plusDays(400), 10);
        String url = "/v1/pharmacy/batches/" + a;
        ResponseEntity<Map> ok = exchange(url, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("expiryDate", "2030-02", "location", "Shelf 9", "reason", "label misread")), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("expiryDate", "2030-02-28").containsEntry("location", "Shelf 9").containsEntry("batchNo", "A").containsEntry("qtyOnHand", 10);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.batch_corrected' AND entity_id = ?", Integer.class, c.t(), a)).isEqualTo(1);
        assertThat(exchange(url, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("location", "x")), Map.class).getStatusCode()).as("reason mandatory").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(url, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("expiryDate", "soon", "reason", "typo")), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(url, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("qtyOnHand", 99, "reason", "cheat")), Map.class).getBody().get("qtyOnHand")).as("quantity is not editable").isEqualTo(10);
        assertThat(exchange("/v1/pharmacy/batches/" + UUID.randomUUID(), HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("reason", "none")), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // same number and expiry as the other batch
        assertThat(exchange("/v1/pharmacy/batches/" + other, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("batchNo", "A", "expiryDate", "2030-02-28", "reason", "dup")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // stock was dispensed from it today, so it cannot be given an earlier expiry
        inTenantTx(c.t(), () -> stock.issueFromBatch(it, a, 1, null, c.staffId(), "customer choice"));
        ResponseEntity<Map> late = exchange("/v1/pharmacy/batches/" + a, HttpMethod.PATCH,
                authedJsonBody(c.token(), Map.of("expiryDate", today(c).minusDays(1).toString(), "reason", "wrong date")), Map.class);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getBody().toString()).contains("batch-has-movements-after-date");
    }

    @Test
    void aStaffMemberWithoutEditCannotCorrectABatchAndAnotherClinicSeesNothing() {
        Clinic c = clinic("iso", "in_house");
        UUID it = item(c, "Iso item");
        UUID b = receive(c, it, "ISO", today(c).plusDays(100), 3);
        UUID viewRole = seedRole(c.t(), "Pharmacy viewer " + UUID.randomUUID().toString().substring(0, 5), false,
                new ModuleGrant("pharmacy", true, false, false, false, false, false, false));
        String viewer = loginAndGetAccessToken(seedStaff(c.tenant(), viewRole, "view@p5.test", "+9196" + String.format("%08d", PHONE.incrementAndGet()), false));
        assertThat(exchange("/v1/pharmacy/batches/" + b, HttpMethod.PATCH, authedJsonBody(viewer, Map.of("location", "x", "reason", "nope")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/pharmacy/items/" + it + "/batches", HttpMethod.GET, authed(viewer), List.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        Clinic other = clinic("iso2", "in_house");
        assertThat(exchange("/v1/pharmacy/items/" + it + "/batches", HttpMethod.GET, authed(other.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/pharmacy/batches/" + b, HttpMethod.PATCH, authedJsonBody(other.token(), Map.of("location", "x", "reason", "nope")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/pharmacy/batches/" + b + "/movements", HttpMethod.GET, authed(other.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inTenantTx(other.t(), () -> jdbc.queryForObject("SELECT count(*) FROM stock_batches WHERE item_id = ?", Integer.class, it))).isZero();
        assertThat(inTenantTx(c.t(), () -> jdbc.queryForObject("SELECT count(*) FROM stock_batches WHERE item_id = ?", Integer.class, it))).isEqualTo(1);
    }

    @Test
    void everyBatchMovementPublishesOneEventWithIdsAndCountsOnly() {
        Clinic c = clinic("evt", "in_house");
        UUID it = item(c, "Evt item");
        UUID a = receive(c, it, "A", today(c).plusDays(60), 5);
        receive(c, it, "B", today(c).plusDays(200), 50);
        inTenantTx(c.t(), () -> stock.issue(it, 8, null, "sale"));
        List<Map<String, Object>> evs = inTenantTx(c.t(), () -> jdbc.queryForList(
                "SELECT payload::text AS p FROM outbox_events WHERE tenant_id = ? AND event_type = 'pharmacy.stock.batch_changed.v1' ORDER BY id", c.t()));
        assertThat(evs).hasSize(4);
        Set<String> keys = new HashSet<>(Set.of("tenantId", "itemId", "batchId", "delta", "kind", "onHand"));
        for (Map<String, Object> e : evs) {
            String p = e.get("p").toString();
            keys.forEach(k -> assertThat(p).contains("\"" + k + "\""));
            assertThat(p).doesNotContain("Evt item");
        }
        assertThat(evs.get(2).get("p").toString()).contains("\"delta\": -5").contains("\"onHand\": 50").contains(a.toString());
        assertThat(evs.get(3).get("p").toString()).contains("\"delta\": -3").contains("\"onHand\": 47");
    }

    // ---------------- scale ----------------

    @Test
    void stockScreenAt5000ItemsAnd20000BatchesIsUnder400msP95AndTheFefoPickUsesThePartialIndex() {
        Clinic c = clinic("perf", "in_house");
        su().update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, tax_rate_percent, stock_qty, active) " +
                "SELECT ?, 'PF-' || g, 'Perf item ' || lpad(g::text, 5, '0'), 'Pharmacy', 10, 0, 40, true FROM generate_series(1, 5000) g", c.t());
        su().update("INSERT INTO stock_batches (tenant_id, item_id, batch_no, expiry_date, qty_on_hand, purchase_rate) " +
                "SELECT c.tenant_id, c.id, 'B' || n, current_date + n * 30, 10, 5 FROM charge_catalogue c, generate_series(1, 4) n WHERE c.tenant_id = ? AND c.code LIKE 'PF-%'", c.t());
        su().execute("ANALYZE stock_batches");
        su().execute("ANALYZE charge_catalogue");

        List<Long> ms = new ArrayList<>();
        Map first = null;
        for (int i = 0; i < 21; i++) {
            long t0 = System.nanoTime();
            ResponseEntity<Map> r = exchange("/v1/pharmacy/stock?limit=50", HttpMethod.GET, authed(c.token()), Map.class);
            ms.add((System.nanoTime() - t0) / 1_000_000);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            first = r.getBody();
        }
        Collections.sort(ms);
        assertThat(ms.get(19)).as("p95 ms %s", ms).isLessThan(400);
        List<Map> p1 = (List<Map>) first.get("items");
        assertThat(p1).hasSize(50);
        assertThat(p1.get(0)).containsEntry("onHand", 40).containsEntry("batchCount", 4);
        Map second = exchange("/v1/pharmacy/stock?limit=50&cursor=" + first.get("nextCursor"), HttpMethod.GET, authed(c.token()), Map.class).getBody();
        List<Map> p2 = (List<Map>) second.get("items");
        assertThat(p2.get(0).get("name")).isEqualTo("Perf item 00051");
        assertThat(exchange("/v1/pharmacy/stock?cursor=!!!", HttpMethod.GET, authed(c.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        UUID anyItem = UUID.fromString((String) p1.get(0).get("itemId"));
        String plan = inTenantTx(c.t(), () -> {
            jdbc.execute("SET LOCAL jit = off");
            return String.join("\n", jdbc.queryForList("EXPLAIN SELECT id, batch_no FROM stock_batches WHERE tenant_id = '" + c.t() + "' AND item_id = '" + anyItem
                    + "' AND qty_on_hand > 0 AND status = 'active' ORDER BY expiry_date NULLS LAST, id", String.class));
        });
        assertThat(plan).contains("idx_stock_batches_fefo");

        // a 10-line prescription: server-side FEFO pick under 50 ms p95
        List<UUID> ten = p1.subList(0, 10).stream().map(x -> UUID.fromString((String) x.get("itemId"))).toList();
        List<Long> pickMs = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            long t0 = System.nanoTime();
            inTenantTx(c.t(), () -> ten.forEach(id -> stock.pick(id, 3, 10)));
            pickMs.add((System.nanoTime() - t0) / 1_000_000);
        }
        Collections.sort(pickMs);
        assertThat(pickMs.get(19)).as("pick p95 ms %s", pickMs).isLessThan(50);
    }
}

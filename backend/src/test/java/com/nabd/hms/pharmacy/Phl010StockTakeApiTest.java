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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PHL-010: stock take by batch (snapshot, counts, variance against snapshot + movements, post), reason codes, maker-checker. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl010StockTakeApiTest extends ApiTestBase {

    private static final AtomicInteger PHONE = new AtomicInteger(8100000);

    @Autowired
    private StockCommands stock;

    private record Clinic(SeededTenant tenant, String token, UUID staffId) {
        UUID t() {
            return tenant.id();
        }
    }

    private String phone() {
        return "+9196" + String.format("%08d", PHONE.incrementAndGet());
    }

    private Clinic clinic(String tag) {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), tag + "@p10.test", phone(), false);
        String token = loginAndGetAccessToken(staff);
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "in_house", "reason", "PHL-010 test")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        su().update("UPDATE staff SET name = 'Maker Pharmacist' WHERE id = ?", staff.id());
        return new Clinic(tenant, token, staff.id());
    }

    /** A second pharmacist: a role with pharmacy approve (so pharmacy:adjust_approve), or without it. */
    private SeededStaff colleague(Clinic c, String name, boolean approver) {
        UUID role = seedRole(c.t(), name + " role " + UUID.randomUUID().toString().substring(0, 6), false,
                new ModuleGrant("pharmacy", true, true, true, false, approver, false, false));
        SeededStaff s = seedStaff(c.tenant(), role, UUID.randomUUID().toString().substring(0, 8) + "@p10.test", phone(), false);
        su().update("UPDATE staff SET name = ? WHERE id = ?", name, s.id());
        return s;
    }

    private UUID item(Clinic c, String name, String scheduleClass) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", name, "isRx", !"OTC".equals(scheduleClass), "price", 100.00, "taxRatePercent", 12.00, "scheduleClass", scheduleClass)), Map.class);
        assertThat(r.getStatusCode()).as("item %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private UUID receive(Clinic c, UUID item, String no, int qty, String location) {
        return inTenantTx(c.t(), () -> stock.receiveBatch(item, new BatchReceipt(no, LocalDate.now().plusDays(400), new BigDecimal("12.00"),
                new BigDecimal("7.5000"), qty, location, null, null), c.staffId(), "GRN " + no).batchId());
    }

    private void sell(Clinic c, UUID item, int qty) {
        inTenantTx(c.t(), () -> stock.issue(item, qty, null, "sale"));
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

    private ResponseEntity<Map> call(String token, HttpMethod m, String path, Object body) {
        return exchange(path, m, authedJsonBody(token, body), Map.class);
    }

    private UUID start(Clinic c, Map<String, Object> body) {
        ResponseEntity<Map> r = call(c.token(), HttpMethod.POST, "/v1/pharmacy/stock-takes", body);
        assertThat(r.getStatusCode()).as("start %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("stockTakeId"));
    }

    private void count(Clinic c, UUID st, Map<UUID, Integer> counts) {
        List<Map<String, Object>> rows = new ArrayList<>();
        counts.forEach((b, n) -> rows.add(Map.of("batchId", b.toString(), "counted", n)));
        ResponseEntity<Map> r = call(c.token(), HttpMethod.PUT, "/v1/pharmacy/stock-takes/" + st + "/counts", Map.of("counts", rows));
        assertThat(r.getStatusCode()).as("counts %s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map> post(Clinic c, UUID st, Map<String, Object> body) {
        return call(c.token(), HttpMethod.POST, "/v1/pharmacy/stock-takes/" + st + "/post", body);
    }

    private static String slug(ResponseEntity<Map> r) {
        String type = String.valueOf(r.getBody().get("type"));
        return type.substring(type.lastIndexOf('/') + 1);
    }

    private Map<String, Map> lines(Clinic c, UUID st) {
        Map sheet = exchange("/v1/pharmacy/stock-takes/" + st, HttpMethod.GET, authed(c.token()), Map.class).getBody();
        Map<String, Map> out = new LinkedHashMap<>();
        ((List<Map>) sheet.get("lines")).forEach(l -> out.put((String) l.get("batchId"), l));
        return out;
    }

    private int movements(UUID st) {
        return su().queryForObject("SELECT count(*) FROM pharmacy_stock_movements WHERE source_type = 'stock_take' AND source_id = ?", Integer.class, st);
    }

    // ---------------- AC1: 300 batches, one movement per variant batch, session locked ----------------

    @Test
    void aSessionOver300BatchesPostsOneMovementPerVariantBatchAndLocks() {
        Clinic c = clinic("big");
        List<UUID> batches = new ArrayList<>();
        Map<UUID, UUID> itemOf = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            UUID it = item(c, "Bulk item " + i, "OTC");
            for (int j = 0; j < 3; j++) {
                UUID b = receive(c, it, "B" + i + "-" + j, 20, "Shelf " + (i % 7));
                batches.add(b);
                itemOf.put(b, it);
            }
        }
        UUID st = start(c, Map.of("scope", "all"));
        Map<String, Map> sheet = lines(c, st);
        assertThat(sheet).hasSize(300);
        assertThat(sheet.values()).allSatisfy(l -> assertThat(l).containsEntry("snapshotQty", 20).containsEntry("expectedQty", 20));
        // grouped by location: the sheet is ordered by shelf
        List<String> locs = sheet.values().stream().map(l -> (String) l.get("location")).toList();
        assertThat(locs).isSortedAccordingTo(String::compareTo);

        Map<UUID, Integer> counts = new LinkedHashMap<>();
        int variant = 0;
        int net = 0;
        for (int k = 0; k < 300; k++) {
            int counted = k % 5 == 0 ? 18 : k % 7 == 0 ? 23 : 20;
            if (counted != 20) {
                variant++;
                net += counted - 20;
            }
            counts.put(batches.get(k), counted);
        }
        count(c, st, counts);
        assertThat(lines(c, st).get(batches.get(0).toString())).containsEntry("variance", -2).containsEntry("countedQty", 18);

        long t0 = System.nanoTime();
        ResponseEntity<Map> r = post(c, st, Map.of());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.getStatusCode()).as("post %s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat((List) r.getBody().get("adjustments")).hasSize(variant);
        assertThat(movements(st)).isEqualTo(variant);
        assertThat(((Number) r.getBody().get("value")).doubleValue()).isEqualTo(net * 7.5);
        for (int k = 0; k < 300; k++) {
            assertThat(batchQty(batches.get(k))).isEqualTo(counts.get(batches.get(k)));
        }
        for (UUID it : new java.util.HashSet<>(itemOf.values())) {
            int sum = batches.stream().filter(b -> itemOf.get(b).equals(it)).mapToInt(counts::get).sum();
            assertThat(cache(it)).isEqualTo(sum);
        }
        assertThat(ms).as("set-based post of 300 lines").isLessThan(15_000);

        // locked: no more counts, a second post writes nothing and answers the same adjustments
        ResponseEntity<Map> late = call(c.token(), HttpMethod.PUT, "/v1/pharmacy/stock-takes/" + st + "/counts",
                Map.of("counts", List.of(Map.of("batchId", batches.get(1).toString(), "counted", 1))));
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(slug(late)).isEqualTo("stock-take-closed");
        ResponseEntity<Map> again = post(c, st, Map.of());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List) again.getBody().get("adjustments")).hasSize(variant);
        assertThat(again.getBody().get("value")).isEqualTo(r.getBody().get("value"));
        assertThat(movements(st)).isEqualTo(variant);
        assertThat(call(c.token(), HttpMethod.POST, "/v1/pharmacy/stock-takes/" + st + "/cancel", Map.of()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // one batch_changed per adjustment and one posted event with counts only
        List<String> posted = inTenantTx(c.t(), () -> jdbc.queryForList("SELECT payload::text FROM outbox_events WHERE tenant_id = ? " +
                "AND event_type = 'pharmacy.stock_take.posted.v1'", String.class, c.t()));
        int variants = variant;
        assertThat(posted).singleElement().satisfies(p -> assertThat(p).contains("\"variances\": " + variants).contains(st.toString())
                .doesNotContain("Bulk item"));
        assertThat(inTenantTx(c.t(), () -> jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = " +
                "'pharmacy.stock.batch_changed.v1' AND payload->>'kind' = 'adjustment'", Integer.class, c.t()))).isEqualTo(variant);
        // the session and its lines are final at the database level
        assertThatThrownBy(() -> su().update("UPDATE stock_takes SET status = 'open' WHERE id = ?", st)).hasMessageContaining("cannot change");
        assertThatThrownBy(() -> su().update("DELETE FROM stock_take_counts WHERE stock_take_id = ?", st)).hasMessageContaining("never deleted");
    }

    // ---------------- AC3: sales during the count are no variance ----------------

    @Test
    void salesDuringTheCountAreMeasuredAgainstSnapshotPlusMovementsAndCauseNoFalseVariance() throws Exception {
        Clinic c = clinic("sales");
        UUID a = item(c, "Sold before count", "OTC");
        UUID ba = receive(c, a, "A1", 50, "S1");
        UUID b = item(c, "Short on shelf", "OTC");
        UUID bb = receive(c, b, "B1", 40, "S1");
        UUID x = item(c, "Sold while posting", "OTC");
        UUID bx = receive(c, x, "X1", 100, "S2");
        UUID st = start(c, Map.of("scope", "all"));

        sell(c, a, 5); // after the snapshot, before the count: the shelf shows 45
        count(c, st, Map.of(ba, 45, bb, 38, bx, 100));
        sell(c, a, 3); // after the count: not a variance either
        Map<String, Map> sheet = lines(c, st);
        assertThat(sheet.get(ba.toString())).containsEntry("snapshotQty", 50).containsEntry("movementsSinceSnapshot", -5)
                .containsEntry("expectedQty", 45).containsEntry("variance", 0);
        assertThat(sheet.get(bb.toString())).containsEntry("variance", -2);

        // ten single-unit sales of X run while the session posts: X was counted before them, so it never moves
        ExecutorService pool = Executors.newFixedThreadPool(11);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                sell(c, x, 1);
                return null;
            }));
        }
        Future<ResponseEntity<Map>> posting = pool.submit(() -> {
            go.await();
            return post(c, st, Map.of());
        });
        go.countDown();
        for (Future<?> f : fs) {
            f.get();
        }
        ResponseEntity<Map> r = posting.get();
        pool.shutdown();
        assertThat(r.getStatusCode()).as("post %s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map>) r.getBody().get("adjustments")).singleElement().satisfies(m -> assertThat(m)
                .containsEntry("batchId", bb.toString()).containsEntry("delta", -2).containsEntry("reasonCode", "count_correction"));
        assertThat(batchQty(ba)).isEqualTo(42);
        assertThat(batchQty(bb)).isEqualTo(38);
        assertThat(batchQty(bx)).isEqualTo(90);
        assertThat(cache(x)).isEqualTo(90);
    }

    // ---------------- AC2: maker-checker for controlled classes ----------------

    @Test
    void aControlledVarianceNeedsAnotherUserWithAdjustApproveConfirmingByPin() {
        Clinic c = clinic("mc");
        UUID ndps = item(c, "Controlled one", "IN_NDPS");
        UUID bn = receive(c, ndps, "N1", 30, "Safe");
        UUID h1 = item(c, "H1 one", "IN_H1");
        UUID bh = receive(c, h1, "H1", 10, "Safe");
        SeededStaff approver = colleague(c, "Second Pharmacist", true);
        SeededStaff noRight = colleague(c, "Junior Pharmacist", false);
        UUID st = start(c, Map.of("scope", "location", "location", "Safe"));
        assertThat(lines(c, st).values()).allSatisfy(l -> assertThat(l).containsEntry("controlled", true));
        count(c, st, Map.of(bn, 28, bh, 10));

        assertThat(slug(post(c, st, Map.of()))).isEqualTo("approval-required");
        assertThat(slug(post(c, st, Map.of("approverStaffId", c.staffId().toString(), "approverPin", STAFF_PIN)))).as("maker cannot approve")
                .isEqualTo("approval-required");
        assertThat(slug(post(c, st, Map.of("approverStaffId", noRight.id().toString(), "approverPin", STAFF_PIN)))).as("no adjust_approve")
                .isEqualTo("approval-required");
        ResponseEntity<Map> wrong = post(c, st, Map.of("approverStaffId", approver.id().toString(), "approverPin", "9999"));
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(slug(wrong)).isEqualTo("approver-pin-invalid");
        assertThat(su().queryForObject("SELECT count(*) FROM login_attempts WHERE staff_id = ? AND factor = 'step_up' AND NOT succeeded",
                Integer.class, approver.id())).as("the failed PIN is counted although the post rolled back").isEqualTo(1);
        assertThat(movements(st)).isZero();
        assertThat(batchQty(bn)).isEqualTo(30);

        // the approver list offers the holder, never the maker or a colleague without the right
        List<Map> list = exchange("/v1/pharmacy/adjustment-approvers", HttpMethod.GET, authed(c.token()), List.class).getBody();
        assertThat(list).extracting(m -> m.get("name")).contains("Second Pharmacist").doesNotContain("Junior Pharmacist", "Maker Pharmacist");

        ResponseEntity<Map> ok = post(c, st, Map.of("approverStaffId", approver.id().toString(), "approverPin", STAFF_PIN));
        assertThat(ok.getStatusCode()).as("post %s", ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("postedByName", "Maker Pharmacist").containsEntry("approvedByName", "Second Pharmacist");
        Map<String, Object> mv = su().queryForMap("SELECT actor_id, approved_by, reason_code, delta FROM pharmacy_stock_movements WHERE source_id = ?", st);
        assertThat(mv).containsEntry("actor_id", c.staffId()).containsEntry("approved_by", approver.id()).containsEntry("delta", -2);
        assertThat(su().queryForObject("SELECT approved_by FROM stock_takes WHERE id = ?", UUID.class, st)).isEqualTo(approver.id());
        Map summary = (Map) exchange("/v1/pharmacy/stock-takes/" + st, HttpMethod.GET, authed(c.token()), Map.class).getBody().get("summary");
        assertThat(summary).containsEntry("status", "posted").containsEntry("approvedByName", "Second Pharmacist");
        assertThatThrownBy(() -> su().update("DELETE FROM pharmacy_stock_movements WHERE source_id = ?", st)).isNotNull();
        assertThatThrownBy(() -> su().update("DELETE FROM stock_takes WHERE id = ?", st)).hasMessageContaining("never deleted");
    }

    @Test
    void aNonControlledVarianceNeedsNoApproverAndTheItemLevelEndpointRefusesAControlledItem() {
        Clinic c = clinic("plain");
        UUID otc = item(c, "Plain one", "OTC");
        UUID bo = receive(c, otc, "P1", 12, null);
        UUID ndps = item(c, "Controlled two", "IN_NDPS");
        receive(c, ndps, "N2", 5, null);
        UUID st = start(c, Map.of("scope", "class", "scheduleClass", "OTC"));
        assertThat(lines(c, st)).containsOnlyKeys(bo.toString());
        count(c, st, Map.of(bo, 11));
        ResponseEntity<Map> r = post(c, st, Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("approvedByName")).isNull();

        ResponseEntity<Map> legacy = call(c.token(), HttpMethod.POST, "/v1/pharmacy/items/" + ndps + "/stock-adjustments", Map.of("delta", -1, "reason", "x"));
        assertThat(legacy.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(slug(legacy)).isEqualTo("approval-required");
        assertThat(call(c.token(), HttpMethod.POST, "/v1/pharmacy/items/" + otc + "/stock-adjustments", Map.of("delta", 1, "reason", "found one"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aSingleBatchAdjustmentTakesDeltaOrSetToAndTheSecondSignerForAControlledBatch() {
        Clinic c = clinic("single");
        UUID otc = item(c, "Single plain", "OTC");
        UUID bo = receive(c, otc, "S1", 10, null);
        UUID ndps = item(c, "Single controlled", "IN_NDPS");
        UUID bn = receive(c, ndps, "S2", 10, null);
        SeededStaff approver = colleague(c, "Checker Pharmacist", true);

        ResponseEntity<Map> d = call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + bo + "/adjustments", Map.of("delta", -2, "reasonCode", "damaged"));
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(d.getBody()).containsEntry("qtyBefore", 10).containsEntry("qtyAfter", 8).containsEntry("makerName", "Maker Pharmacist");
        ResponseEntity<Map> s = call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + bo + "/adjustments", Map.of("setTo", 9, "reasonCode", "count_correction"));
        assertThat(s.getBody()).containsEntry("delta", 1).containsEntry("qtyAfter", 9);
        assertThat(cache(otc)).isEqualTo(9);
        ResponseEntity<Map> neg = call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + bo + "/adjustments", Map.of("delta", -10, "reasonCode", "damaged"));
        assertThat(neg.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(slug(neg)).isEqualTo("insufficient-stock");
        assertThat(slug(call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + bo + "/adjustments", Map.of("delta", 1, "setTo", 3, "reasonCode", "damaged"))))
                .isEqualTo("validation-failed");

        assertThat(slug(call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + bn + "/adjustments", Map.of("delta", -1, "reasonCode", "theft_loss"))))
                .isEqualTo("approval-required");
        ResponseEntity<Map> ok = call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + bn + "/adjustments", Map.of("delta", -1, "reasonCode", "theft_loss",
                "note", "Strip missing at handover", "approverStaffId", approver.id().toString(), "approverPin", STAFF_PIN));
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("approverName", "Checker Pharmacist").containsEntry("qtyAfter", 9);
        Map<String, Object> mv = su().queryForMap("SELECT reason, reason_code, actor_id, approved_by FROM pharmacy_stock_movements WHERE batch_id = ? " +
                "AND kind = 'adjustment'", bn);
        assertThat(mv).containsEntry("reason_code", "theft_loss").containsEntry("reason", "Theft/loss: Strip missing at handover")
                .containsEntry("actor_id", c.staffId()).containsEntry("approved_by", approver.id());
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.stock_adjusted' AND entity_id = ?",
                Integer.class, c.t(), bn)).isEqualTo(1);
    }

    // ---------------- AC4: reason codes are a fixed list ----------------

    @Test
    void reasonCodesComeFromTheFixedListAndOtherNeedsANote() {
        Clinic c = clinic("reason");
        UUID it = item(c, "Reason item", "OTC");
        UUID b = receive(c, it, "R1", 10, null);
        for (String code : List.of("lost", "COUNT_CORRECTION", "")) {
            ResponseEntity<Map> r = call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + b + "/adjustments", Map.of("delta", -1, "reasonCode", code));
            assertThat(r.getStatusCode()).as(code).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(slug(call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + b + "/adjustments", Map.of("delta", -1, "reasonCode", "other"))))
                .isEqualTo("validation-failed");
        for (String code : List.of("damaged", "count_correction", "theft_loss", "expired")) {
            assertThat(call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + b + "/adjustments", Map.of("delta", -1, "reasonCode", code))
                    .getStatusCode()).as(code).isEqualTo(HttpStatus.OK);
        }
        assertThat(call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + b + "/adjustments", Map.of("delta", -1, "reasonCode", "other", "note", "Sample to inspector"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(su().queryForList("SELECT reason_code FROM pharmacy_stock_movements WHERE batch_id = ? AND kind = 'adjustment' ORDER BY seq", String.class, b))
                .containsExactly("damaged", "count_correction", "theft_loss", "expired", "other");
        assertThatThrownBy(() -> su().update("INSERT INTO pharmacy_stock_movements (tenant_id, item_id, batch_id, kind, delta, qty_before, qty_after, reason, " +
                "reason_code) VALUES (?,?,?,'adjustment',1,5,6,'x','lost')", c.t(), it, b)).hasMessageContaining("reason_code");

        // in a count: an unknown code is refused, 'other' needs a note, the chosen code lands on the movement
        UUID st = start(c, Map.of("scope", "all"));
        assertThat(call(c.token(), HttpMethod.PUT, "/v1/pharmacy/stock-takes/" + st + "/counts", Map.of("counts",
                List.of(Map.of("batchId", b.toString(), "counted", 3, "reasonCode", "lost")))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(slug(call(c.token(), HttpMethod.PUT, "/v1/pharmacy/stock-takes/" + st + "/counts", Map.of("counts",
                List.of(Map.of("batchId", b.toString(), "counted", 3, "reasonCode", "other")))))).isEqualTo("validation-failed");
        assertThat(call(c.token(), HttpMethod.PUT, "/v1/pharmacy/stock-takes/" + st + "/counts", Map.of("counts",
                List.of(Map.of("batchId", b.toString(), "counted", 3, "reasonCode", "expired")))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(c, st, Map.of()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(su().queryForMap("SELECT reason_code, reason FROM pharmacy_stock_movements WHERE source_id = ?", st))
                .containsEntry("reason_code", "expired").containsEntry("reason", "Stock take: Expired");
    }

    // ---------------- session rules, permissions, tenant isolation ----------------

    @Test
    void startIsIdempotentPerScopeOneSessionIsOpenAtATimeAndOnlyItsBatchesCanBeCounted() {
        Clinic c = clinic("rules");
        UUID it = item(c, "Rule item", "OTC");
        UUID b = receive(c, it, "R1", 4, "Front");
        UUID st = start(c, Map.of("scope", "location", "location", "Front"));
        assertThat(start(c, Map.of("scope", "location", "location", "Front"))).isEqualTo(st);
        ResponseEntity<Map> other = call(c.token(), HttpMethod.POST, "/v1/pharmacy/stock-takes", Map.of("scope", "all"));
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(slug(other)).isEqualTo("stock-take-open");
        assertThat(slug(call(c.token(), HttpMethod.PUT, "/v1/pharmacy/stock-takes/" + st + "/counts", Map.of("counts",
                List.of(Map.of("batchId", UUID.randomUUID().toString(), "counted", 1)))))).isEqualTo("batch-not-in-stock-take");
        assertThat(call(c.token(), HttpMethod.POST, "/v1/pharmacy/stock-takes/" + st + "/cancel", Map.of()).getBody()).containsEntry("status", "cancelled");
        assertThat(slug(call(c.token(), HttpMethod.POST, "/v1/pharmacy/stock-takes", Map.of("scope", "location", "location", "Nowhere"))))
                .isEqualTo("nothing-to-count");
        assertThat(slug(call(c.token(), HttpMethod.POST, "/v1/pharmacy/stock-takes", Map.of("scope", "location")))).isEqualTo("validation-failed");
        assertThat(slug(call(c.token(), HttpMethod.POST, "/v1/pharmacy/stock-takes", Map.of("scope", "class", "scheduleClass", "SA_CONTROLLED"))))
                .isEqualTo("schedule-not-valid-for-market");
        assertThat((List) exchange("/v1/pharmacy/stock-takes", HttpMethod.GET, authed(c.token()), List.class).getBody()).hasSize(1);

        // a pharmacy:view holder reads but cannot start, count, post or adjust
        UUID viewRole = seedRole(c.t(), "Viewer " + UUID.randomUUID().toString().substring(0, 5), false,
                new ModuleGrant("pharmacy", true, false, false, false, false, false, false));
        String viewer = loginAndGetAccessToken(seedStaff(c.tenant(), viewRole, "viewer" + PHONE.get() + "@p10.test", phone(), false));
        UUID st2 = start(c, Map.of("scope", "all"));
        assertThat(exchange("/v1/pharmacy/stock-takes/" + st2, HttpMethod.GET, authed(viewer), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call(viewer, HttpMethod.POST, "/v1/pharmacy/stock-takes", Map.of("scope", "all")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(viewer, HttpMethod.PUT, "/v1/pharmacy/stock-takes/" + st2 + "/counts", Map.of("counts",
                List.of(Map.of("batchId", b.toString(), "counted", 1)))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(viewer, HttpMethod.POST, "/v1/pharmacy/stock-takes/" + st2 + "/post", Map.of()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(viewer, HttpMethod.POST, "/v1/pharmacy/batches/" + b + "/adjustments", Map.of("delta", 1, "reasonCode", "damaged"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // another clinic sees and changes nothing
        Clinic two = clinic("rules2");
        assertThat(exchange("/v1/pharmacy/stock-takes/" + st2, HttpMethod.GET, authed(two.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(two.token(), HttpMethod.POST, "/v1/pharmacy/stock-takes/" + st2 + "/post", Map.of()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(two.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + b + "/adjustments", Map.of("delta", 1, "reasonCode", "damaged"))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((List) exchange("/v1/pharmacy/stock-takes", HttpMethod.GET, authed(two.token()), List.class).getBody()).isEmpty();
        assertThat(batchQty(b)).isEqualTo(4);
    }
}

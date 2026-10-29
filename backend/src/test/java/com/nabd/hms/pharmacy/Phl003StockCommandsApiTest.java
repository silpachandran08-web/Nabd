package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.pharmacy.api.StockCommands;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PHL-003: StockCommands is the single stock writer; checkout rolls back as a whole; ADR-07 RLS on the pharmacy tables. */
class Phl003StockCommandsApiTest extends ApiTestBase {

    private static final AtomicInteger PHONE = new AtomicInteger(3000000);

    @Autowired
    private StockCommands stock;

    private record Clinic(SeededTenant tenant, String token, UUID staffId) {
    }

    private Clinic hybridClinic(String tag) {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = com.nabd.hms.support.ClinicianFixture.licensed(
                seedStaff(tenant, roleId, tag + "@p3.test", "+9196" + String.format("%08d", PHONE.incrementAndGet()), false));
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "PHL-003 test")), Map.class);
        return new Clinic(tenant, token, staff.id());
    }

    private Map<?, ?> addItem(Clinic c, String name, int qty) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", name, "isRx", false, "price", 100.00, "taxRatePercent", 12.00, "stockQty", qty)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private int stockOf(Clinic c, UUID itemId) {
        return inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT stock_qty FROM charge_catalogue WHERE id = ?", Integer.class, itemId));
    }

    /** Runs n tasks at once, each in its own tenant transaction; returns how many succeeded and the slugs of the refusals. */
    private Map<String, Integer> race(Clinic c, int n, java.util.function.Consumer<Integer> op) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            Callable<String> task = () -> {
                go.await();
                try {
                    inTenantTx(c.tenant().id(), () -> op.accept(k));
                    return "ok";
                } catch (ApiException e) {
                    return e.typeSlug();
                }
            };
            fs.add(pool.submit(task));
        }
        go.countDown();
        Map<String, Integer> out = new java.util.HashMap<>();
        for (Future<String> f : fs) {
            out.merge(f.get(), 1, Integer::sum);
        }
        pool.shutdown();
        return out;
    }

    @Test
    void fiftyConcurrentIssuesOfOneUnitOnTwentyUnitsSucceedExactlyTwentyTimes() throws Exception {
        Clinic c = hybridClinic("issue");
        UUID item = UUID.fromString((String) addItem(c, "Race item", 20).get("id"));
        Map<String, Integer> r = race(c, 50, k -> stock.issue(item, 1, null, "race"));
        assertThat(r).containsEntry("ok", 20).containsEntry("insufficient-stock", 30);
        assertThat(stockOf(c, item)).isZero();
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM pharmacy_stock_movements WHERE item_id = ? AND kind = 'dispense'", Integer.class, item))).isEqualTo(20);
    }

    @Test
    void concurrentAdjustReserveReceiveAndReturnKeepTheBalanceCorrect() throws Exception {
        Clinic c = hybridClinic("ops");
        UUID item = UUID.fromString((String) addItem(c, "Ops item", 20).get("id"));
        // adjust -1 x 50 on 20 units
        assertThat(race(c, 50, k -> stock.adjust(item, -1, c.staffId(), "count_correction", "count"))).containsEntry("ok", 20).containsEntry("insufficient-stock", 30);
        assertThat(stockOf(c, item)).isZero();
        // receive +1 x 50 and returnIn +1 x 50: nothing is lost
        assertThat(race(c, 50, k -> stock.receive(item, 1, c.staffId(), "grn"))).containsEntry("ok", 50);
        assertThat(race(c, 50, k -> stock.returnIn(item, 1, null, c.staffId(), "return"))).containsEntry("ok", 50);
        assertThat(stockOf(c, item)).isEqualTo(100);
        // reserve changes nothing and refuses what is not there
        assertThat(race(c, 30, k -> stock.reserve(item, 100))).containsEntry("ok", 30);
        assertThat(stockOf(c, item)).isEqualTo(100);
        assertThatThrownBy(() -> inTenantTx(c.tenant().id(), () -> stock.reserve(item, 101))).isInstanceOf(ApiException.class);
        // the chain of movements adds up
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT COALESCE(sum(delta), 0) FROM pharmacy_stock_movements WHERE item_id = ?", Integer.class, item))).isEqualTo(100);
    }

    @Test
    void issueP95StaysUnderFifteenMilliseconds() {
        Clinic c = hybridClinic("perf");
        UUID item = UUID.fromString((String) addItem(c, "Perf item", 1000).get("id"));
        for (int i = 0; i < 20; i++) { // warm-up
            inTenantTx(c.tenant().id(), () -> stock.issue(item, 1, null, "warm"));
        }
        List<Long> ns = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            long t0 = System.nanoTime();
            inTenantTx(c.tenant().id(), () -> stock.issue(item, 1, null, "perf"));
            ns.add((System.nanoTime() - t0) / 1_000);
        }
        java.util.Collections.sort(ns);
        long p95us = ns.get(94);
        System.out.println("PHL-003 issue() p95 us (own tx incl. tenant bind): " + p95us);
        assertThat(p95us).isLessThan(15_000);
    }

    @Test
    void invalidQuantityAndUnknownItemAreRefusedCleanly() {
        Clinic c = hybridClinic("bad");
        assertThatThrownBy(() -> inTenantTx(c.tenant().id(), () -> stock.issue(UUID.randomUUID(), 1, null, "x")))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).status()).isEqualTo(HttpStatus.NOT_FOUND);
        UUID item = UUID.fromString((String) addItem(c, "Bad qty", 5).get("id"));
        assertThatThrownBy(() -> inTenantTx(c.tenant().id(), () -> stock.receive(item, 0, c.staffId(), "x"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> inTenantTx(c.tenant().id(), () -> stock.adjust(item, 0, c.staffId(), "count_correction", "x"))).isInstanceOf(ApiException.class);
    }

    @Test
    void checkoutOfThreePharmacyLinesRollsBackWhenOneLineLacksStock() {
        Clinic c = hybridClinic("co");
        Map<?, ?> a = addItem(c, "Line A", 10), b = addItem(c, "Line B", 1), d = addItem(c, "Line C", 10);
        String patientId = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", "Rollback Patient", "phone", "+919999941111", "dob", "1971-01-01", "gender", "male")), Map.class).getBody().get("id");
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patientId, "doctorId", c.staffId().toString())), Map.class).getBody().get("id");
        for (String s : new String[]{"waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending"}) {
            exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", s)), Map.class);
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<?, ?> it : List.of(a, b, d)) {
            lines.add(Map.of("chargeCode", it.get("code"), "chargeName", it.get("name"), "category", "Pharmacy",
                    "quantity", it == b ? 5 : 2, "unitPrice", 100.00, "taxRatePercent", 12.00));
        }
        ResponseEntity<Map> r = exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", lines)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("type")).asString().contains("insufficient-stock");
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM pharmacy_stock_movements WHERE kind = 'dispense'", Integer.class))).isZero();
        assertThat(stockOf(c, UUID.fromString((String) a.get("id")))).isEqualTo(10);
        assertThat(stockOf(c, UUID.fromString((String) d.get("id")))).isEqualTo(10);
    }

    @Test
    void pharmacyTablesAreForcedAndWithCheckRejectsAnotherTenantsRow() {
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        for (String t : List.of("pharmacy_settings", "pharmacy_stock_movements", "pharmacy_item_profiles")) {
            assertThat(su.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = ?::regclass", Boolean.class, t))
                    .as(t + " FORCE RLS").isTrue();
            assertThat(su.queryForObject("SELECT count(*) FROM pg_policies WHERE tablename = ? AND cmd IN ('INSERT', 'UPDATE') AND with_check IS NOT NULL",
                    Integer.class, t)).as(t + " WITH CHECK").isGreaterThanOrEqualTo(1);
        }
        Clinic a = hybridClinic("rlsa"), b = hybridClinic("rlsb");
        UUID item = UUID.fromString((String) addItem(a, "RLS item", 3).get("id"));
        AtomicInteger n = new AtomicInteger();
        assertThatThrownBy(() -> inTenantTx(a.tenant().id(), () -> n.set(jdbc.update(
                "INSERT INTO pharmacy_stock_movements (tenant_id, item_id, kind, delta, qty_before, qty_after, reason) VALUES (?,?,'adjustment',1,3,4,'x')",
                b.tenant().id(), item)))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        // and tenant B cannot see A's movements
        assertThat(inTenantTx(b.tenant().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM pharmacy_stock_movements WHERE item_id = ?", Integer.class, item))).isZero();
    }
}

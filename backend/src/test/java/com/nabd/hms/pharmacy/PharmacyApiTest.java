package com.nabd.hms.pharmacy;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** E16 Pharmacy, Hybrid-mode scope only (the wireframe's own "Phase 2" label) — item CRUD gated
 * behind Hybrid mode, and one-tap dispense at checkout (bill + stock deduction, same transaction). */
class PharmacyApiTest extends ApiTestBase {

    // Distinct dob per patient avoids NB-060's fuzzy duplicate-candidate match, which two
    // similarly-named test patients sharing a dob would otherwise trip (no "id" in the response).
    private static final AtomicInteger DOB_YEAR = new AtomicInteger(1960);

    private String registerPatient(String token, String name, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", (DOB_YEAR.getAndIncrement()) + "-01-01", "gender", "male")), Map.class);
        return (String) resp.getBody().get("id");
    }

    private String checkoutPendingEntry(String token, UUID doctorId, String patientName, String phone) {
        String patientId = registerPatient(token, patientName, phone);
        ResponseEntity<Map> checkIn = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class);
        String queueEntryId = (String) checkIn.getBody().get("id");
        for (String s : new String[]{"waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending"}) {
            exchange("/v1/queue/" + queueEntryId + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", s)), Map.class);
        }
        return queueEntryId;
    }

    private String addHybridItem(String token, String name, int stockQty) {
        ResponseEntity<Map> resp = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "isRx", false, "hsnCode", "3304", "price", 350.00, "taxRatePercent", 12.00,
                "stockQty", stockQty)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) resp.getBody().get("code");
    }

    @Test
    void defaultModeIsExternalUntilSet() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pharm1@a.com", "+919600000001", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/pharmacy/settings", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getBody().get("mode")).isEqualTo("external");
    }

    @Test
    void updateSettingsToHybridPersists() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pharm2@a.com", "+919600000002", false);
        String token = loginAndGetAccessToken(staff);

        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        ResponseEntity<Map> resp = exchange("/v1/pharmacy/settings", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getBody().get("mode")).isEqualTo("hybrid");
    }

    @Test
    void addingItemOutsideHybridModeIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pharm3@a.com", "+919600000003", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Sunscreen SPF 50", "isRx", false, "price", 350.00, "stockQty", 10)), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("pharmacy-mode-not-hybrid");
    }

    @Test
    void hybridModeAddAndListItem() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pharm4@a.com", "+919600000004", false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);

        addHybridItem(token, "Medicated cleanser", 6);

        ResponseEntity<List> list = exchange("/v1/pharmacy/items", HttpMethod.GET, authed(token), List.class);
        assertThat(list.getBody()).hasSize(1);
        Map<?, ?> item = (Map<?, ?>) list.getBody().get(0);
        assertThat(item.get("name")).isEqualTo("Medicated cleanser");
        assertThat(item.get("stockQty")).isEqualTo(6);
    }

    @Test
    void pharmacyItemHiddenFromCheckoutChargesUnlessHybrid() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pharm5@a.com", "+919600000005", false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        String code = addHybridItem(token, "Topical antibiotic", 5);

        String queueEntryId = checkoutPendingEntry(token, staff.id(), "P5", "+919999940005");
        ResponseEntity<Map> hybridCtx = exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.GET, authed(token), Map.class);
        List<?> hybridCharges = (List<?>) hybridCtx.getBody().get("charges");
        assertThat(hybridCharges.stream().anyMatch(c -> code.equals(((Map<?, ?>) c).get("code")))).isTrue();

        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "external", "reason", "Test setup")), Map.class);
        ResponseEntity<Map> externalCtx = exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.GET, authed(token), Map.class);
        List<?> externalCharges = (List<?>) externalCtx.getBody().get("charges");
        assertThat(externalCharges.stream().anyMatch(c -> code.equals(((Map<?, ?>) c).get("code")))).isFalse();
    }

    @Test
    void checkingOutAPharmacyItemDecrementsStockAndInsufficientStockIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pharm6@a.com", "+919600000006", false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        String code = addHybridItem(token, "Amoxicillin 500mg", 2);

        String firstEntry = checkoutPendingEntry(token, staff.id(), "P6a", "+919999940006");
        ResponseEntity<Map> firstCheckout = exchange("/v1/billing/checkout/" + firstEntry, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", code, "chargeName", "Amoxicillin 500mg", "category", "Pharmacy",
                        "quantity", 1, "unitPrice", 350.00, "taxRatePercent", 12.00)))), Map.class);
        assertThat(firstCheckout.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<List> afterFirst = exchange("/v1/pharmacy/items", HttpMethod.GET, authed(token), List.class);
        assertThat(((Map<?, ?>) afterFirst.getBody().get(0)).get("stockQty")).isEqualTo(1);

        String secondEntry = checkoutPendingEntry(token, staff.id(), "P6b", "+919999940016");
        ResponseEntity<Map> secondCheckout = exchange("/v1/billing/checkout/" + secondEntry, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", code, "chargeName", "Amoxicillin 500mg", "category", "Pharmacy",
                        "quantity", 5, "unitPrice", 350.00, "taxRatePercent", 12.00)))), Map.class);
        assertThat(secondCheckout.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(secondCheckout.getBody().get("type")).asString().contains("insufficient-stock");
    }

    @Test
    void signedPrescriptionAppearsInTheDispensingQueueUntilCheckoutCompletes() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pharm8@a.com", "+919600000008", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "PharmQueue", "+919999940008");
        ResponseEntity<Map> checkIn = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", staff.id().toString())), Map.class);
        String queueEntryId = (String) checkIn.getBody().get("id");
        exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "Cetirizine", "dosage", "10mg", "frequency", "OD")))), Map.class);

        ResponseEntity<List> beforeSign = exchange("/v1/pharmacy/dispensing-queue", HttpMethod.GET, authed(token), List.class);
        assertThat(beforeSign.getBody()).isEmpty();

        exchange("/v1/clinical/prescriptions/" + queueEntryId + "/sign", HttpMethod.POST, authed(token), Map.class);

        ResponseEntity<List> afterSign = exchange("/v1/pharmacy/dispensing-queue", HttpMethod.GET, authed(token), List.class);
        assertThat(afterSign.getBody()).hasSize(1);
        Map<?, ?> entry = (Map<?, ?>) afterSign.getBody().get(0);
        assertThat(entry.get("patientName")).isEqualTo("PharmQueue");
        List<?> items = (List<?>) entry.get("items");
        assertThat(((Map<?, ?>) items.get(0)).get("drugName")).isEqualTo("Cetirizine");

        for (String s : new String[]{"waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending"}) {
            exchange("/v1/queue/" + queueEntryId + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", s)), Map.class);
        }
        exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", "X", "chargeName", "X", "category", "Service",
                        "quantity", 1, "unitPrice", 100, "taxRatePercent", 0)))), Map.class);

        ResponseEntity<List> afterCheckout = exchange("/v1/pharmacy/dispensing-queue", HttpMethod.GET, authed(token), List.class);
        assertThat(afterCheckout.getBody()).isEmpty();
    }

    @Test
    void roleWithoutPharmacyGrantIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "QueueOnly", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "pharm7@a.com", "+919600000007", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/pharmacy/settings", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private String hybridTenantToken(String email, String phone) {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, email, phone, false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        return token;
    }

    private String itemId(String token) {
        ResponseEntity<List> list = exchange("/v1/pharmacy/items", HttpMethod.GET, authed(token), List.class);
        return (String) ((Map<?, ?>) list.getBody().get(0)).get("id");
    }

    // V2-004: stock changes by delta or stock-take, reason required, immutable movement rows, audited.
    @Test
    void stockAdjustmentByDeltaAndStockTakeNeedsAReasonAndLeavesImmutableMovementRows() {
        String token = hybridTenantToken("pharm-v24a@a.com", "+919600000041");
        addHybridItem(token, "Zinc tablets", 10);
        String id = itemId(token);
        String path = "/v1/pharmacy/items/" + id + "/stock-adjustments";

        ResponseEntity<Map> noReason = exchange(path, HttpMethod.POST, authedJsonBody(token, Map.of("delta", -1)), Map.class);
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> neither = exchange(path, HttpMethod.POST, authedJsonBody(token, Map.of("reason", "x")), Map.class);
        assertThat(neither.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> both = exchange(path, HttpMethod.POST, authedJsonBody(token, Map.of("delta", 1, "setTo", 3, "reason", "x")), Map.class);
        assertThat(both.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> minus = exchange(path, HttpMethod.POST, authedJsonBody(token, Map.of("delta", -3, "reason", "Damaged in transit")), Map.class);
        assertThat(minus.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(minus.getBody().get("stockQty")).isEqualTo(7);

        ResponseEntity<Map> stockTake = exchange(path, HttpMethod.POST, authedJsonBody(token, Map.of("setTo", 12, "reason", "Stock-take 11 Oct")), Map.class);
        assertThat(stockTake.getBody().get("stockQty")).isEqualTo(12);

        ResponseEntity<Map> belowZero = exchange(path, HttpMethod.POST, authedJsonBody(token, Map.of("delta", -13, "reason", "Typo")), Map.class);
        assertThat(belowZero.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(belowZero.getBody().get("type")).asString().contains("insufficient-stock");

        ResponseEntity<Map> noChange = exchange(path, HttpMethod.POST, authedJsonBody(token, Map.of("setTo", 12, "reason", "Same")), Map.class);
        assertThat(noChange.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Opening stock + two adjustments = three immutable rows, newest first.
        ResponseEntity<List> movements = exchange("/v1/pharmacy/items/" + id + "/stock-movements", HttpMethod.GET, authed(token), List.class);
        assertThat(movements.getBody()).hasSize(3);
        Map<?, ?> newest = (Map<?, ?>) movements.getBody().get(0);
        assertThat(newest.get("delta")).isEqualTo(5);
        assertThat(newest.get("qtyBefore")).isEqualTo(7);
        assertThat(newest.get("qtyAfter")).isEqualTo(12);
        assertThat(newest.get("reason")).isEqualTo("Stock-take 11 Oct");
        // V3-003: listed order is the order the stock actually changed, so each row's before equals the next-older row's after.
        for (int i = 0; i + 1 < movements.getBody().size(); i++) {
            assertThat(((Map<?, ?>) movements.getBody().get(i)).get("qtyBefore"))
                    .isEqualTo(((Map<?, ?>) movements.getBody().get(i + 1)).get("qtyAfter"));
        }
    }

    @Test
    void stockAdjustmentsAreAudited() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pharm-v24b@a.com", "+919600000042", false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        addHybridItem(token, "ORS sachets", 4);
        String id = itemId(token);
        exchange("/v1/pharmacy/items/" + id + "/stock-adjustments", HttpMethod.POST,
                authedJsonBody(token, Map.of("delta", 6, "reason", "Supplier delivery")), Map.class);

        Map<String, Object> audited = inTenantTx(tenant.id(), () -> jdbc.queryForMap(
                "SELECT action, before, after FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.stock_adjusted' " +
                        "ORDER BY created_at DESC LIMIT 1", tenant.id()));
        assertThat(audited.get("before").toString()).contains("4");
        assertThat(audited.get("after").toString()).contains("10").contains("Supplier delivery");
    }

    // V2-004: the movement table cannot be edited or emptied, even by code that bypasses the service.
    @Test
    void stockMovementRowsAreAppendOnly() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pharm-v24c@a.com", "+919600000043", false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        addHybridItem(token, "Bandages", 5);

        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                inTenantTx(tenant.id(), () -> jdbc.update("UPDATE pharmacy_stock_movements SET reason = 'edited' WHERE tenant_id = ?", tenant.id())));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                inTenantTx(tenant.id(), () -> jdbc.update("DELETE FROM pharmacy_stock_movements WHERE tenant_id = ?", tenant.id())));
        assertTruncateRefusedByTrigger("TRUNCATE pharmacy_stock_movements", "pharmacy_stock_movements is append-only");
    }

    // V2-004: editing an item no longer writes stock, so a stale edit cannot overwrite a newer count.
    @Test
    void editingAnItemDoesNotTouchStock() {
        String token = hybridTenantToken("pharm-v24d@a.com", "+919600000044");
        addHybridItem(token, "Gauze", 8);
        String id = itemId(token);
        exchange("/v1/pharmacy/items/" + id + "/stock-adjustments", HttpMethod.POST, authedJsonBody(token, Map.of("delta", -2, "reason", "Used in clinic")), Map.class);

        ResponseEntity<Map> edit = exchange("/v1/pharmacy/items/" + id, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "name", "Gauze roll", "isRx", false, "price", 40.00, "stockQty", 99)), Map.class);

        assertThat(edit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edit.getBody().get("name")).isEqualTo("Gauze roll");
        assertThat(edit.getBody().get("stockQty")).isEqualTo(6);
    }

    // V2-004: two simultaneous decrements both land (no lost update) and each leaves its own row.
    @Test
    void concurrentAdjustmentsDoNotLoseUpdates() throws Exception {
        String token = hybridTenantToken("pharm-v24e@a.com", "+919600000045");
        addHybridItem(token, "Syringes", 10);
        String id = itemId(token);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<ResponseEntity<Map>>> calls = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            int n = i;
            calls.add(pool.submit(() -> {
                go.await();
                return exchange("/v1/pharmacy/items/" + id + "/stock-adjustments", HttpMethod.POST,
                        authedJsonBody(token, Map.of("delta", -3, "reason", "Pick " + n)), Map.class);
            }));
        }
        go.countDown();
        for (Future<ResponseEntity<Map>> f : calls) {
            assertThat(f.get().getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        pool.shutdown();

        ResponseEntity<List> list = exchange("/v1/pharmacy/items", HttpMethod.GET, authed(token), List.class);
        assertThat(((Map<?, ?>) list.getBody().get(0)).get("stockQty")).isEqualTo(4);
    }

    // V2-004: a checkout dispense leaves a movement row too.
    @Test
    void aCheckoutDispenseIsLoggedAsAMovement() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pharm-v24f@a.com", "+919600000046", false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        String code = addHybridItem(token, "Loratadine", 5);
        String entry = checkoutPendingEntry(token, staff.id(), "Dispense Logged", "+919999940046");
        exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", code, "chargeName", "Loratadine", "category", "Pharmacy",
                        "quantity", 2, "unitPrice", 50.00, "taxRatePercent", 0)))), Map.class);

        ResponseEntity<List> movements = exchange("/v1/pharmacy/items/" + itemId(token) + "/stock-movements", HttpMethod.GET, authed(token), List.class);
        Map<?, ?> newest = (Map<?, ?>) movements.getBody().get(0);
        assertThat(newest.get("kind")).isEqualTo("dispense");
        assertThat(newest.get("delta")).isEqualTo(-2);
        assertThat(newest.get("qtyAfter")).isEqualTo(3);
    }

    // V2-007: activate / deactivate is audited, idempotent, and removes the item from checkout.
    @Test
    void deactivatingAnItemHidesItFromCheckoutAndIsAudited() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pharm-v27@a.com", "+919600000047", false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        String code = addHybridItem(token, "Expired syrup", 3);
        String id = itemId(token);

        ResponseEntity<Map> off = exchange("/v1/pharmacy/items/" + id + "/status", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("active", false, "reason", "Past expiry")), Map.class);
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(off.getBody().get("active")).isEqualTo(false);
        exchange("/v1/pharmacy/items/" + id + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("active", false)), Map.class); // idempotent

        String entry = checkoutPendingEntry(token, staff.id(), "Inactive Hidden", "+919999940047");
        ResponseEntity<Map> ctx = exchange("/v1/billing/checkout/" + entry, HttpMethod.GET, authed(token), Map.class);
        assertThat(((List<?>) ctx.getBody().get("charges")).stream().anyMatch(c -> code.equals(((Map<?, ?>) c).get("code")))).isFalse();

        Integer deactivations = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.item_deactivated'", Integer.class, tenant.id()));
        assertThat(deactivations).isEqualTo(1);

        ResponseEntity<Map> on = exchange("/v1/pharmacy/items/" + id + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("active", true)), Map.class);
        assertThat(on.getBody().get("active")).isEqualTo(true);
    }

    // V3-004: a stale tab or direct API call cannot sell a deactivated item, and cannot choose the price of a stocked one.
    @Test
    void checkoutRefusesAnInactiveItemAndPricesStockedItemsFromTheCatalogue() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pharm-v34@a.com", "+919600000050", false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        String code = addHybridItem(token, "Recalled batch", 5);
        String id = itemId(token);

        String entry = checkoutPendingEntry(token, staff.id(), "Stale Tab", "+919999940050");
        ResponseEntity<Map> cheap = exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", code, "chargeName", "Anything", "category", "Pharmacy",
                        "quantity", 1, "unitPrice", 1.00, "taxRatePercent", 0)))), Map.class);
        assertThat(cheap.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> line = (Map<?, ?>) ((List<?>) cheap.getBody().get("lineItems")).get(0);
        assertThat(line.get("chargeName")).isEqualTo("Recalled batch");
        assertThat(new java.math.BigDecimal(line.get("unitPrice").toString())).isEqualByComparingTo("350.00");

        exchange("/v1/pharmacy/items/" + id + "/status", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("active", false, "reason", "Recalled")), Map.class);
        String entry2 = checkoutPendingEntry(token, staff.id(), "Stale Tab Two", "+919999940051");
        ResponseEntity<Map> blocked = exchange("/v1/billing/checkout/" + entry2, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", code, "chargeName", "Recalled batch", "category", "Pharmacy",
                        "quantity", 1, "unitPrice", 350.00, "taxRatePercent", 12)))), Map.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody().get("type")).asString().contains("item-inactive");
        ResponseEntity<List> items = exchange("/v1/pharmacy/items", HttpMethod.GET, authed(token), List.class);
        assertThat(((Map<?, ?>) items.getBody().get(0)).get("stockQty")).isEqualTo(4); // only the first sale dispensed
    }

    @Test
    void viewOnlyPharmacyRoleCannotAdjustStockOrToggleActive() {
        SeededTenant tenant = seedTenant();
        UUID ownerRole = seedFullAccessRole(tenant.id());
        SeededStaff owner = seedStaff(tenant, ownerRole, "pharm-v24g@a.com", "+919600000048", false);
        String ownerToken = loginAndGetAccessToken(owner);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(ownerToken, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        addHybridItem(ownerToken, "Cotton", 4);
        String id = itemId(ownerToken);
        UUID viewRole = seedRole(tenant.id(), "PharmView", false, new com.nabd.hms.common.ModuleGrant("pharmacy", true, false, false, false, false, false, false));
        String viewToken = loginAndGetAccessToken(seedStaff(tenant, viewRole, "pharm-v24h@a.com", "+919600000049", false));

        assertThat(exchange("/v1/pharmacy/items/" + id + "/stock-adjustments", HttpMethod.POST,
                authedJsonBody(viewToken, Map.of("delta", 1, "reason", "x")), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/pharmacy/items/" + id + "/status", HttpMethod.PATCH,
                authedJsonBody(viewToken, Map.of("active", false)), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/pharmacy/items/" + id + "/stock-movements", HttpMethod.GET, authed(viewToken), List.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ---- PHA-011 / PHA-003 ----

    private Map<?, ?> otcCharge(String token, String code) {
        Map<?, ?> body = exchange("/v1/billing/otc-charges", HttpMethod.GET, authed(token), Map.class).getBody();
        return ((List<Map<?, ?>>) body.get("charges")).stream().filter(c -> code.equals(c.get("code"))).findFirst().orElseThrow();
    }

    private int itemStock(String token, String code) {
        List<Map<?, ?>> items = exchange("/v1/pharmacy/items", HttpMethod.GET, authed(token), List.class).getBody();
        return (Integer) items.stream().filter(i -> code.equals(i.get("code"))).findFirst().orElseThrow().get("stockQty");
    }

    private Map<String, Object> pharmacyLine(String code, int qty) {
        return Map.of("chargeCode", code, "chargeName", "x", "category", "Pharmacy", "quantity", qty,
                "unitPrice", 350.00, "taxRatePercent", 12.00);
    }

    @Test
    void chargesCarryStockQtyAndOverSellingIsRejectedWithoutChangingStock() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "pharm20@a.com", "+919600000020", false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        String code = addHybridItem(token, "Cough syrup", 3);

        assertThat(otcCharge(token, code).get("stockQty")).isEqualTo(3);

        ResponseEntity<Map> over = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(pharmacyLine(code, 4)))), Map.class);
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(over.getBody().get("type")).asString().contains("insufficient-stock");
        assertThat(itemStock(token, code)).isEqualTo(3);
    }

    @Test
    void voidingAnUnpaidInvoiceRestoresStockWithAnImmutableMovementAndAuditRow() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "Owner2", true, fullGrant("pharmacy"), fullGrant("billing"));
        SeededStaff staff = seedStaff(tenant, roleId, "pharm21@a.com", "+919600000021", false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        String code = addHybridItem(token, "Ibuprofen", 10);
        ResponseEntity<Map> sale = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(pharmacyLine(code, 4)))), Map.class);
        String invoiceId = (String) sale.getBody().get("id");
        assertThat(itemStock(token, code)).isEqualTo(6);

        ResponseEntity<Map> blank = exchange("/v1/billing/invoices/" + invoiceId + "/void", HttpMethod.POST,
                authedJsonBody(token, Map.of("reason", " ")), Map.class);
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map> voided = exchange("/v1/billing/invoices/" + invoiceId + "/void", HttpMethod.POST,
                authedJsonBody(token, Map.of("reason", "Customer changed mind")), Map.class);
        assertThat(voided.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(voided.getBody().get("status")).isEqualTo("void");
        assertThat(itemStock(token, code)).isEqualTo(10);

        List<Map<?, ?>> items = exchange("/v1/pharmacy/items", HttpMethod.GET, authed(token), List.class).getBody();
        String itemId = (String) items.get(0).get("id");
        List<Map<?, ?>> moves = exchange("/v1/pharmacy/items/" + itemId + "/stock-movements", HttpMethod.GET, authed(token), List.class).getBody();
        assertThat(moves.get(0).get("kind")).isEqualTo("restock");
        assertThat(moves.get(0).get("delta")).isEqualTo(4);
        Integer audit = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'billing.invoice_voided' AND entity_id = ?", Integer.class, UUID.fromString(invoiceId)));
        assertThat(audit).isEqualTo(1);

        ResponseEntity<Map> again = exchange("/v1/billing/invoices/" + invoiceId + "/void", HttpMethod.POST,
                authedJsonBody(token, Map.of("reason", "again")), Map.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(itemStock(token, code)).isEqualTo(10);

        ResponseEntity<Map> pay = exchange("/v1/billing/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                authedJsonBody(token, Map.of("method", "cash", "amount", 10)), Map.class);
        assertThat(pay.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(pay.getBody().get("type")).asString().contains("invoice-not-payable"); // FIN-001: was invoice-void
    }

    @Test
    void voidIsRefusedWhenPaymentsExistAndWithoutTheDiscountGrant() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "pharm22@a.com", "+919600000022", false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        String code = addHybridItem(token, "Vitamin C", 5);
        String invoiceId = (String) exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(pharmacyLine(code, 2)))), Map.class).getBody().get("id");
        exchange("/v1/billing/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                authedJsonBody(token, Map.of("method", "cash", "amount", 10)), Map.class);

        ResponseEntity<Map> resp = exchange("/v1/billing/invoices/" + invoiceId + "/void", HttpMethod.POST,
                authedJsonBody(token, Map.of("reason", "oops")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody().get("type")).asString().contains("invoice-has-payments");
        assertThat(itemStock(token, code)).isEqualTo(3);

        // A billing role without the refund/discount grant cannot void at all.
        String invoice2 = (String) exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(pharmacyLine(code, 1)))), Map.class).getBody().get("id");
        UUID biller = seedRole(tenant.id(), "Biller", false,
                new com.nabd.hms.common.ModuleGrant("billing", true, true, true, false, false, false, false));
        String billerToken = loginAndGetAccessToken(seedStaff(tenant, biller, "pharm23@a.com", "+919600000023", false));
        ResponseEntity<Map> denied = exchange("/v1/billing/invoices/" + invoice2 + "/void", HttpMethod.POST,
                authedJsonBody(billerToken, Map.of("reason", "nope")), Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(itemStock(token, code)).isEqualTo(2);
    }

    @Test
    void dispensingQueueRowsCarryQueueEntryIdTokenAndPerItemStock() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "pharm24@a.com", "+919600000024", false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        addHybridItem(token, "Cetirizine", 7);
        String patientId = registerPatient(token, "PharmTok", "+919999940024");
        ResponseEntity<Map> checkIn = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", staff.id().toString())), Map.class);
        String queueEntryId = (String) checkIn.getBody().get("id");
        exchange("/v1/clinical/prescriptions/" + queueEntryId, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "items", List.of(Map.of("drugName", "cetirizine", "dosage", "10mg", "frequency", "OD"),
                        Map.of("drugName", "Unstocked drug", "dosage", "5mg", "frequency", "OD")))), Map.class);
        exchange("/v1/clinical/prescriptions/" + queueEntryId + "/sign", HttpMethod.POST, authed(token), Map.class);

        List<Map<?, ?>> queue = exchange("/v1/pharmacy/dispensing-queue", HttpMethod.GET, authed(token), List.class).getBody();
        Map<?, ?> row = queue.get(0);
        assertThat(row.get("queueEntryId")).isEqualTo(queueEntryId);
        assertThat(row.get("token")).isEqualTo(checkIn.getBody().get("tokenNumber"));
        List<Map<?, ?>> items = (List<Map<?, ?>>) row.get("items");
        assertThat(items.get(0).get("stockQty")).isEqualTo(7);
        assertThat(items.get(1).get("stockQty")).isNull();
    }

    // PHA-006: a mode change needs a 3-200 char reason and is audited with old mode, new mode and reason.
    @Test
    void modeChangeRequiresReasonAndIsAudited() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        String token = loginAndGetAccessToken(seedStaff(tenant, roleId, "pharm-pha6a@a.com", "+919600000091", false));

        ResponseEntity<Map> noReason = exchange("/v1/pharmacy/settings", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("mode", "hybrid")), Map.class);
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noReason.getBody().get("type")).asString().contains("reason-required");
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "  ab ")),
                Map.class).getBody().get("type")).asString().contains("reason-required");
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "x".repeat(201))),
                Map.class).getBody().get("type")).asString().contains("reason-required");
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.GET, authed(token), Map.class).getBody().get("mode")).isEqualTo("external");

        ResponseEntity<Map> ok = exchange("/v1/pharmacy/settings", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Opening OTC counter")), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("mode")).isEqualTo("hybrid");
        // re-sending the current mode is a no-op: no reason needed, nothing audited
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid")),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> audited = inTenantTx(tenant.id(), () -> jdbc.queryForList(
                "SELECT before::text AS before, after::text AS after FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.mode_changed'",
                tenant.id()));
        assertThat(audited).hasSize(1);
        assertThat(audited.get(0).get("before").toString()).contains("external");
        assertThat(audited.get(0).get("after").toString()).contains("hybrid").contains("Opening OTC counter");
    }

    // PHA-006: in-house (Phase 3) can't be switched to; a tenant already on it is left alone.
    @Test
    void switchingToInHouseIsNotAvailableUnlessAlreadyInHouse() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        String token = loginAndGetAccessToken(seedStaff(tenant, roleId, "pharm-pha6b@a.com", "+919600000092", false));

        ResponseEntity<Map> resp = exchange("/v1/pharmacy/settings", HttpMethod.PATCH,
                authedJsonBody(token, Map.of("mode", "in_house", "reason", "Want batch tracking")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("mode-not-available");

        // legacy tenant already on in_house (set directly, as before PHA-006): re-saving it is fine, leaving it needs a reason
        inTenantTx(tenant.id(), () -> jdbc.update("INSERT INTO pharmacy_settings (tenant_id, mode) VALUES (?, 'in_house')", tenant.id()));
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "in_house")),
                Map.class).getBody().get("mode")).isEqualTo("in_house");
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Back to hybrid")),
                Map.class).getBody().get("mode")).isEqualTo("hybrid");
    }
}

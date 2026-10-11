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

        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid")), Map.class);
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
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid")), Map.class);

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
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid")), Map.class);
        String code = addHybridItem(token, "Topical antibiotic", 5);

        String queueEntryId = checkoutPendingEntry(token, staff.id(), "P5", "+919999940005");
        ResponseEntity<Map> hybridCtx = exchange("/v1/billing/checkout/" + queueEntryId, HttpMethod.GET, authed(token), Map.class);
        List<?> hybridCharges = (List<?>) hybridCtx.getBody().get("charges");
        assertThat(hybridCharges.stream().anyMatch(c -> code.equals(((Map<?, ?>) c).get("code")))).isTrue();

        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "external")), Map.class);
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
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid")), Map.class);
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
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid")), Map.class);
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
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid")), Map.class);
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
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid")), Map.class);
        addHybridItem(token, "Bandages", 5);

        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                inTenantTx(tenant.id(), () -> jdbc.update("UPDATE pharmacy_stock_movements SET reason = 'edited' WHERE tenant_id = ?", tenant.id())));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                inTenantTx(tenant.id(), () -> jdbc.update("DELETE FROM pharmacy_stock_movements WHERE tenant_id = ?", tenant.id())));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                inTenantTx(tenant.id(), () -> jdbc.execute("TRUNCATE pharmacy_stock_movements")));
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
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid")), Map.class);
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
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid")), Map.class);
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

    @Test
    void viewOnlyPharmacyRoleCannotAdjustStockOrToggleActive() {
        SeededTenant tenant = seedTenant();
        UUID ownerRole = seedFullAccessRole(tenant.id());
        SeededStaff owner = seedStaff(tenant, ownerRole, "pharm-v24g@a.com", "+919600000048", false);
        String ownerToken = loginAndGetAccessToken(owner);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(ownerToken, Map.of("mode", "hybrid")), Map.class);
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
}

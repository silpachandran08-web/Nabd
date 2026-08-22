package com.nabd.hms.pharmacy;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    void roleWithoutPharmacyGrantIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "QueueOnly", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "pharm7@a.com", "+919600000007", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/pharmacy/settings", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}

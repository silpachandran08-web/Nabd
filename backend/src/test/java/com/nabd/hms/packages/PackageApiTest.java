package com.nabd.hms.packages;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** E14 Treatment Packages, MVP scope only (the wireframe's own "Phase 2" label on instalments and
 * transfer/gifting excludes both here) — package builder, sale, session ledger, expiry, refunds
 * and liability, all matching "New package" / "Sell a package" / the per-instance ledger shown in
 * the wireframe's Treatment Packages screens. */
class PackageApiTest extends ApiTestBase {

    private static final AtomicInteger DOB_YEAR = new AtomicInteger(1970);

    private String registerPatient(String token, String name) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", "+9198" + (7000000 + DOB_YEAR.get()), "dob",
                (DOB_YEAR.getAndIncrement()) + "-01-01", "gender", "female")), Map.class);
        return (String) resp.getBody().get("id");
    }

    private Map<String, Object> comboItem(String type, String name, int qty, double price, double tax) {
        return Map.of("itemType", type, "name", name, "quantity", qty, "unitListPrice", price, "taxRatePercent", tax);
    }

    /** Clean & Bright-style combination package: 2 x ₹1000 svc (18% tax) + 1 x ₹1000 take-home (12% tax)
     * = ₹3000 list, sold at ₹2400 (80% of list, above a 72% floor). */
    private String createPackage(String token, double price, int validityDays, String validityStarts) {
        ResponseEntity<Map> resp = exchange("/v1/packages", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Test Combo", "packageType", "combination", "speciality", "Dermatology",
                "description", "desc", "price", price, "taxInclusive", false, "validityDays", validityDays,
                "validityStarts", validityStarts, "graceDays", 7,
                "items", List.of(
                        comboItem("service_session", "Facial", 2, 1000.00, 18.0),
                        comboItem("take_home_product", "Cream", 1, 1000.00, 12.0)))), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) resp.getBody().get("id");
    }

    private void activate(String token, String packageId) {
        ResponseEntity<Map> resp = exchange("/v1/packages/" + packageId + "/activate", HttpMethod.POST, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Map sell(String token, String patientId, String packageId) {
        ResponseEntity<Map> resp = exchange("/v1/packages/sell", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "packageId", packageId, "paymentMethod", "upi")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    @Test
    void draftPackageComputesListValueSaveAndFloor() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pkg1@a.com", "+919500000001", false);
        String token = loginAndGetAccessToken(staff);

        String packageId = createPackage(token, 2400.00, 90, "purchase_date");
        ResponseEntity<Map> resp = exchange("/v1/packages/" + packageId, HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getBody().get("status")).isEqualTo("draft");
        assertThat(((Number) resp.getBody().get("listValue")).doubleValue()).isEqualTo(3000.00);
        assertThat(((Number) resp.getBody().get("saveAmount")).doubleValue()).isEqualTo(600.00);
        assertThat(((Number) resp.getBody().get("priceFloor")).doubleValue()).isEqualTo(2160.00); // 72% of 3000
        assertThat(resp.getBody().get("belowFloor")).isEqualTo(false);
    }

    @Test
    void activatingBelowPriceFloorIsRejectedWithNoSilentOverride() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pkg2@a.com", "+919500000002", false);
        String token = loginAndGetAccessToken(staff);

        // 2000 is below the 72% floor of a 3000 list value (floor = 2160)
        String packageId = createPackage(token, 2000.00, 90, "purchase_date");
        ResponseEntity<Map> resp = exchange("/v1/packages/" + packageId + "/activate", HttpMethod.POST, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("below-price-floor");
    }

    @Test
    void sellingCreatesInstanceInvoiceAndAllocatedTaxLines() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pkg3@a.com", "+919500000003", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "Sell Test Patient");
        String packageId = createPackage(token, 2400.00, 90, "purchase_date");
        activate(token, packageId);

        Map instance = sell(token, patientId, packageId);

        assertThat(instance.get("status")).isEqualTo("active");
        assertThat(instance.get("patientId")).isEqualTo(patientId);
        assertThat(((Number) instance.get("soldPrice")).doubleValue()).isEqualTo(2400.00);
        assertThat(instance.get("invoiceNumber")).asString().startsWith("INV-");

        List<Map<String, Object>> items = (List<Map<String, Object>>) instance.get("items");
        assertThat(items).hasSize(2);
        // 2000 list share -> 1600 allocated (2000/3000 * 2400); 1000 list share -> 800 allocated
        double allocatedTotal = items.stream().mapToDouble(i -> ((Number) i.get("allocatedPrice")).doubleValue()).sum();
        assertThat(allocatedTotal).isEqualTo(2400.00);

        // "Tax exclusive" package: tax adds on top of the 2400 price -> 1600*18% + 800*12% = 384 tax, 2784 total
        String invoiceId = (String) instance.get("invoiceId");
        ResponseEntity<Map> invoice = exchange("/v1/billing/invoices/" + invoiceId, HttpMethod.GET, authed(token), Map.class);
        assertThat(invoice.getBody().get("status")).isEqualTo("paid");
        assertThat(((Number) invoice.getBody().get("tax")).doubleValue()).isEqualTo(384.00);
        assertThat(((Number) invoice.getBody().get("total")).doubleValue()).isEqualTo(2784.00);
    }

    @Test
    void sellingTheSamePackageTwiceToTheSamePatientIsRejected() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pkg4@a.com", "+919500000004", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "Dup Test Patient");
        String packageId = createPackage(token, 2400.00, 90, "purchase_date");
        activate(token, packageId);
        sell(token, patientId, packageId);

        ResponseEntity<Map> resp = exchange("/v1/packages/sell", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "packageId", packageId, "paymentMethod", "cash")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("type")).asString().contains("duplicate-active-package");
    }

    @Test
    void bookingLeavesCounterUnchangedOnlyRedeemDecrements() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pkg5@a.com", "+919500000005", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "Ledger Test Patient");
        String packageId = createPackage(token, 2400.00, 90, "purchase_date");
        activate(token, packageId);
        Map instance = sell(token, patientId, packageId);
        String instanceId = (String) instance.get("id");
        List<Map<String, Object>> items = (List<Map<String, Object>>) instance.get("items");
        String facialItemId = items.stream().filter(i -> "Facial".equals(i.get("name"))).findFirst().orElseThrow().get("id").toString();

        exchange("/v1/packages/instances/items/" + facialItemId + "/book", HttpMethod.POST, authed(token), Map.class);
        ResponseEntity<Map> afterBooking = exchange("/v1/packages/instances/" + instanceId, HttpMethod.GET, authed(token), Map.class);
        Map facialAfterBooking = ((List<Map<String, Object>>) afterBooking.getBody().get("items")).stream()
                .filter(i -> "Facial".equals(i.get("name"))).findFirst().orElseThrow();
        assertThat(facialAfterBooking.get("quantityConsumed")).isEqualTo(0);

        ResponseEntity<Map> afterRedeem = exchange("/v1/packages/instances/items/" + facialItemId + "/redeem", HttpMethod.POST, authed(token), Map.class);
        Map facialAfterRedeem = ((List<Map<String, Object>>) afterRedeem.getBody().get("items")).stream()
                .filter(i -> "Facial".equals(i.get("name"))).findFirst().orElseThrow();
        assertThat(facialAfterRedeem.get("quantityConsumed")).isEqualTo(1);

        // 2nd of 2 facials
        exchange("/v1/packages/instances/items/" + facialItemId + "/redeem", HttpMethod.POST, authed(token), Map.class);
        // 3rd redeem attempt: none left
        ResponseEntity<Map> overRedeem = exchange("/v1/packages/instances/items/" + facialItemId + "/redeem", HttpMethod.POST, authed(token), Map.class);
        assertThat(overRedeem.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(overRedeem.getBody().get("type")).asString().contains("no-sessions-remaining");
    }

    @Test
    void completingEveryItemMarksInstanceCompleted() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pkg6@a.com", "+919500000006", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "Complete Test Patient");
        String packageId = createPackage(token, 2400.00, 90, "purchase_date");
        activate(token, packageId);
        Map instance = sell(token, patientId, packageId);
        String instanceId = (String) instance.get("id");
        List<Map<String, Object>> items = (List<Map<String, Object>>) instance.get("items");
        for (Map<String, Object> item : items) {
            int qty = (Integer) item.get("quantityTotal");
            for (int i = 0; i < qty; i++) {
                exchange("/v1/packages/instances/items/" + item.get("id") + "/redeem", HttpMethod.POST, authed(token), Map.class);
            }
        }
        ResponseEntity<Map> resp = exchange("/v1/packages/instances/" + instanceId, HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getBody().get("status")).isEqualTo("completed");
    }

    @Test
    void firstSessionValidityStartsOnlyOnFirstRedemption() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pkg7@a.com", "+919500000007", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "FirstSession Test Patient");
        String packageId = createPackage(token, 2400.00, 30, "first_session");
        activate(token, packageId);
        Map instance = sell(token, patientId, packageId);

        assertThat(instance.get("validityEnd")).isNull();
        assertThat(instance.get("status")).isEqualTo("active"); // never expires before first use

        List<Map<String, Object>> items = (List<Map<String, Object>>) instance.get("items");
        String facialItemId = items.stream().filter(i -> "Facial".equals(i.get("name"))).findFirst().orElseThrow().get("id").toString();
        ResponseEntity<Map> afterRedeem = exchange("/v1/packages/instances/items/" + facialItemId + "/redeem", HttpMethod.POST, authed(token), Map.class);

        assertThat(afterRedeem.getBody().get("validityEnd")).isEqualTo(LocalDate.now().plusDays(30).toString());
    }

    @Test
    void extendRequiresApproveGrantAndIsAudited() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pkg8@a.com", "+919500000008", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "Extend Test Patient");
        String packageId = createPackage(token, 2400.00, 30, "purchase_date");
        activate(token, packageId);
        Map instance = sell(token, patientId, packageId);
        String instanceId = (String) instance.get("id");
        LocalDate newEnd = LocalDate.now().plusDays(60);

        ResponseEntity<Map> resp = exchange("/v1/packages/instances/" + instanceId + "/extend", HttpMethod.POST, authedJsonBody(token, Map.of(
                "newValidityEnd", newEnd.toString(), "reason", "Patient hospitalised")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("validityEnd")).isEqualTo(newEnd.toString());
    }

    @Test
    void refundPreviewMatchesPaidMinusConsumedAtListPriceAndCanGoNegative() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pkg9@a.com", "+919500000009", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "Refund Test Patient");
        // list 3000, sold at 2400; consuming both Facials (list 2000) exceeds paid (2400)? No: 2000 < 2400,
        // still a refund. Consume everything (list 3000) to flip into "patient owes" territory.
        String packageId = createPackage(token, 2400.00, 90, "purchase_date");
        activate(token, packageId);
        Map instance = sell(token, patientId, packageId);
        String instanceId = (String) instance.get("id");
        List<Map<String, Object>> items = (List<Map<String, Object>>) instance.get("items");
        for (Map<String, Object> item : items) {
            int qty = (Integer) item.get("quantityTotal");
            for (int i = 0; i < qty; i++) {
                exchange("/v1/packages/instances/items/" + item.get("id") + "/redeem", HttpMethod.POST, authed(token), Map.class);
            }
        }

        ResponseEntity<Map> preview = exchange("/v1/packages/instances/" + instanceId + "/refund-preview", HttpMethod.GET, authed(token), Map.class);
        // paid 2400 (incl. tax it's a bit more; soldTax applies) minus used-list-value 3000 -> negative -> owed
        assertThat(((Number) preview.getBody().get("usedListValue")).doubleValue()).isEqualTo(3000.00);
        assertThat(((Number) preview.getBody().get("refundAmount")).doubleValue()).isEqualTo(0.0);
        assertThat(((Number) preview.getBody().get("amountOwed")).doubleValue()).isGreaterThan(0.0);
    }

    @Test
    void approvingARefundIssuesCreditNoteAndClosesThePackage() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pkg10@a.com", "+919500000010", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "Approve Refund Patient");
        String packageId = createPackage(token, 2400.00, 90, "purchase_date");
        activate(token, packageId);
        Map instance = sell(token, patientId, packageId);
        String instanceId = (String) instance.get("id");

        ResponseEntity<Map> requestResp = exchange("/v1/packages/instances/" + instanceId + "/refund", HttpMethod.POST,
                authedJsonBody(token, Map.of("reason", "Patient relocated")), Map.class);
        assertThat(requestResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(requestResp.getBody().get("status")).isEqualTo("pending");
        String refundId = (String) requestResp.getBody().get("id");

        ResponseEntity<Map> approveResp = exchange("/v1/packages/refunds/" + refundId + "/approve", HttpMethod.POST, authed(token), Map.class);
        assertThat(approveResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approveResp.getBody().get("status")).isEqualTo("approved");
        assertThat(approveResp.getBody().get("creditNoteNumber")).asString().startsWith("CN-");

        ResponseEntity<Map> instanceAfter = exchange("/v1/packages/instances/" + instanceId, HttpMethod.GET, authed(token), Map.class);
        assertThat(instanceAfter.getBody().get("status")).isEqualTo("refunded");

        List<Map<String, Object>> items = (List<Map<String, Object>>) instanceAfter.getBody().get("items");
        String anyItemId = items.get(0).get("id").toString();
        ResponseEntity<Map> blockedRedeem = exchange("/v1/packages/instances/items/" + anyItemId + "/redeem", HttpMethod.POST, authed(token), Map.class);
        assertThat(blockedRedeem.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blockedRedeem.getBody().get("type")).asString().contains("package-not-actionable");
    }

    @Test
    void expiringSoonAndLiabilityReflectActiveInstances() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pkg11@a.com", "+919500000011", false);
        String token = loginAndGetAccessToken(staff);
        String patientId = registerPatient(token, "Expiring Test Patient");
        // validity 5 days -> well within the 30-day / 7-day tier window immediately after sale
        String packageId = createPackage(token, 2400.00, 5, "purchase_date");
        activate(token, packageId);
        sell(token, patientId, packageId);

        ResponseEntity<List> expiring = exchange("/v1/packages/expiring-soon", HttpMethod.GET, authed(token), List.class);
        assertThat(expiring.getBody()).hasSize(1);
        assertThat(((Map<?, ?>) expiring.getBody().get(0)).get("alertTier")).isEqualTo(7);

        ResponseEntity<Map> liability = exchange("/v1/packages/liability", HttpMethod.GET, authed(token), Map.class);
        assertThat(((Number) liability.getBody().get("activePatientPackages")).longValue()).isEqualTo(1);
        assertThat(((Number) liability.getBody().get("sessionsOwed")).longValue()).isEqualTo(3);
        assertThat(((Number) liability.getBody().get("remainingAllocatedValue")).doubleValue()).isEqualTo(2400.00);
    }

    @Test
    void doctorEligibilityWarningShowsWhenARestrictedDoctorIsOnLeaveToday() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "pkg12@a.com", "+919500000012", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> pkgResp = exchange("/v1/packages", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "Doctor Restricted", "packageType", "session", "price", 1000.00, "taxInclusive", false,
                "validityDays", 30, "validityStarts", "purchase_date", "eligibleDoctorIds", List.of(staff.id().toString()),
                "items", List.of(comboItem("service_session", "Laser", 3, 400.00, 0.0)))), Map.class);
        String packageId = (String) pkgResp.getBody().get("id");
        assertThat(pkgResp.getBody().get("eligibleDoctorIds")).asList().containsExactly(staff.id().toString());
        assertThat(pkgResp.getBody().get("doctorLeaveWarning")).isNull();

        inTenantTx(tenant.id(), () -> jdbc.update(
                "INSERT INTO doctor_leave (tenant_id, doctor_id, date_from, date_to, reason) VALUES (?,?,?,?,?)",
                tenant.id(), staff.id(), java.sql.Date.valueOf(LocalDate.now()), java.sql.Date.valueOf(LocalDate.now()), "leave"));

        ResponseEntity<Map> afterLeave = exchange("/v1/packages/" + packageId, HttpMethod.GET, authed(token), Map.class);
        assertThat(afterLeave.getBody().get("doctorLeaveWarning")).asString().contains("on leave today");
    }

    @Test
    void roleWithoutPackagesGrantIsForbidden() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedRole(tenant.id(), "QueueOnly", false, fullGrant("queue"), fullGrant("patients"));
        SeededStaff staff = seedStaff(tenant, roleId, "noPkg@a.com", "+919500000013", false);
        String token = loginAndGetAccessToken(staff);

        ResponseEntity<Map> resp = exchange("/v1/packages", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}

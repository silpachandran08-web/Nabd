package com.nabd.hms.pharmacy;

import com.nabd.hms.platform.entitlements.PlanEntitlements;
import com.nabd.hms.staff.presets.RolePresets;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * PHL-032: a pharmacy-only tenant. Provisioned with edition 'pharmacy' (facility type, roles, plan, in-house stock), refused on every
 * clinical and scheduling endpoint with 403 MODULE_NOT_ENTITLED, no invoice without a drug licence number, and a 2,000-line opening
 * stock list through the ADM-013 import in under two minutes.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl032PharmacyEditionApiTest extends ApiTestBase {

    @Autowired
    private PlanEntitlements planEntitlements;

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private record Shop(SeededTenant tenant, String token) {
        UUID t() {
            return tenant.id();
        }
    }

    /** A seeded tenant turned into a pharmacy-only tenant (what provisioning does with edition 'pharmacy'), with in-house stock. */
    private Shop pharmacyShop() {
        SeededTenant t = seedTenant();
        su().update("UPDATE tenants SET facility_type = 'pharmacy' WHERE id = ?", t.id());
        planEntitlements.evict(t.id());
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "own" + UUID.randomUUID() + "@p32.test",
                "+9197" + (10000000 + (int) (Math.random() * 89999999)), false);
        String token = loginAndGetAccessToken(owner);
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "in_house", "reason", "PHL-032 test")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Shop(t, token);
    }

    @Test
    void provisioningAPharmacyEditionGivesFacilityTypeRolesPlanAndInHouseStock() {
        String op = platformLoginAndGetAccessToken(seedOperator("super-" + UUID.randomUUID() + "@nabd.health", "super_admin", false));
        String slug = "pharm-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> created = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(op, Map.of(
                "tenantSlug", slug, "tenantName", "Chemist " + slug, "region", "IN", "ownerEmail", "owner-" + slug + "@nabd.health",
                "ownerName", "Test Owner", "ownerMobile", "+9198765" + String.format("%05d", Math.abs(slug.hashCode() % 100000)),
                "brandName", "Brand " + slug, "path", "self_serve", "edition", "pharmacy")), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("edition")).isEqualTo("pharmacy");
        Map<String, Object> body = null;
        for (int i = 0; i < 6; i++) {
            body = exchange("/v1/platform/provisioning-jobs/" + created.getBody().get("id") + "/advance", HttpMethod.POST, authed(op), Map.class).getBody();
        }
        assertThat(body.get("status")).isEqualTo("done");
        UUID tenantId = UUID.fromString((String) body.get("createdTenantId"));

        assertThat(su().queryForObject("SELECT facility_type FROM tenants WHERE id = ?", String.class, tenantId)).isEqualTo("pharmacy");
        assertThat(su().queryForList("SELECT preset_code FROM roles WHERE tenant_id = ?", String.class, tenantId))
                .containsExactlyInAnyOrderElementsOf(RolePresets.PHARMACY.stream().map(RolePresets.Preset::code).toList());
        assertThat(su().queryForObject("SELECT mode FROM pharmacy_settings WHERE tenant_id = ?", String.class, tenantId)).isEqualTo("in_house");
        assertThat(su().queryForList("SELECT e.module_code FROM master.subscriptions s JOIN master.plans p ON p.id = s.plan_id " +
                "JOIN master.plan_entitlements e ON e.plan_id = p.id AND e.plan_version = s.plan_version WHERE s.tenant_id = ?", String.class, tenantId))
                .containsExactlyInAnyOrder("pharmacy_inhouse", "billing");
    }

    @Test
    void aClinicProvisioningJobStaysAClinic() {
        String op = platformLoginAndGetAccessToken(seedOperator("super-" + UUID.randomUUID() + "@nabd.health", "super_admin", false));
        ResponseEntity<Map> bad = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(op, Map.of(
                "tenantSlug", "nope-" + UUID.randomUUID().toString().substring(0, 6), "tenantName", "X", "region", "IN", "ownerEmail", "o@nabd.health",
                "ownerName", "O", "ownerMobile", "+919876500001", "brandName", "B", "path", "self_serve", "edition", "spaceship")), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void everyClinicalAndSchedulingAreaIs403ModuleNotEntitledForAPharmacyButTheClinicStillWorks() {
        Shop shop = pharmacyShop();
        for (String path : List.of("/v1/appointments", "/v1/queue/eta", "/v1/schedule/day", "/v1/clinical/encounters", "/v1/nursing/procedure-orders/today", "/v1/packages",
                "/v1/waitlist", "/v1/booking-requests")) {
            ResponseEntity<Map> r = exchange(path, HttpMethod.GET, authed(shop.token()), Map.class);
            assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(String.valueOf(r.getBody())).as(path).contains("MODULE_NOT_ENTITLED");
        }
        ResponseEntity<Map> write = exchange("/v1/appointments", HttpMethod.POST, authedJsonBody(shop.token(), Map.of()), Map.class);
        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // pharmacy, billing, the drug master and the entitlement answer stay open
        assertThat(exchange("/v1/pharmacy/items", HttpMethod.GET, authed(shop.token()), List.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/clinical/drugs", HttpMethod.GET, authed(shop.token()), Object.class).getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/me/entitlements", HttpMethod.GET, authed(shop.token()), Map.class).getBody().get("edition")).isEqualTo("pharmacy");

        // the same call for an ordinary clinic is not refused for its edition
        SeededTenant clinic = seedTenant();
        String clinicToken = loginAndGetAccessToken(seedStaff(clinic, seedFullAccessRole(clinic.id()), "c" + UUID.randomUUID() + "@p32.test", "+9197" + (10000000 + (int) (Math.random() * 89999999)), false));
        assertThat(exchange("/v1/appointments", HttpMethod.GET, authed(clinicToken), Map.class).getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/me/entitlements", HttpMethod.GET, authed(clinicToken), Map.class).getBody().get("edition")).isEqualTo("clinic");
    }

    @Test
    void withoutADrugLicenceNumberNoInvoiceIsIssuedAndTheWizardSaysWhy() {
        Shop shop = pharmacyShop();
        Map<String, Object> item = (Map<String, Object>) exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(shop.token(), Map.of(
                "name", "Testamol " + UUID.randomUUID().toString().substring(0, 5), "isRx", false, "price", 20.00, "taxRatePercent", 0, "stockQty", 10)), Map.class).getBody();

        Map<String, Object> st = exchange("/v1/pharmacy/setup", HttpMethod.GET, authed(shop.token()), Map.class).getBody();
        assertThat(st.get("pharmacyEdition")).isEqualTo(true);
        assertThat(st.get("invoicingBlocked")).isEqualTo(true);
        assertThat((List<String>) st.get("missingLicenceKeys")).containsExactly("IN_20B", "IN_21B");

        Map<String, Object> sale = Map.of("lineItems", List.of(Map.of("chargeCode", item.get("code"), "quantity", 1)));
        ResponseEntity<Map> refused = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(shop.token(), sale), Map.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(String.valueOf(refused.getBody())).contains("DRUG_LICENCE_MISSING");
        assertThat(su().queryForObject("SELECT count(*) FROM invoices WHERE tenant_id = ?", Integer.class, shop.t())).isZero();
        assertThat(su().queryForObject("SELECT count(*) FROM invoice_series WHERE tenant_id = ? AND next_number > 1", Integer.class, shop.t())).as("no number consumed").isZero();

        // one of the two India licences is still a blocker
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(shop.token(), Map.of("licenceNumbers", Map.of("IN_20B", "SYN-20B"))), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(shop.token(), sale), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(shop.token(), Map.of("licenceNumbers", Map.of("IN_20B", "SYN-20B", "IN_21B", "SYN-21B"))), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/pharmacy/setup", HttpMethod.GET, authed(shop.token()), Map.class).getBody().get("invoicingBlocked")).isEqualTo(false);
        ResponseEntity<Map> ok = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(shop.token(), sale), Map.class);
        assertThat(ok.getStatusCode().is2xxSuccessful()).as(String.valueOf(ok.getBody())).isTrue();

        ResponseEntity<Map> printer = exchange("/v1/pharmacy/setup/printer-test", HttpMethod.POST, authedJsonBody(shop.token(), Map.of()), Map.class);
        assertThat(printer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/pharmacy/setup", HttpMethod.GET, authed(shop.token()), Map.class).getBody().get("printerTestedAt")).isNotNull();
    }

    @Test
    void aClinicWithoutALicenceIsNotBlockedFromInvoicing() {
        SeededTenant t = seedTenant();
        String token = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "k" + UUID.randomUUID() + "@p32.test", "+9197" + (10000000 + (int) (Math.random() * 89999999)), false));
        assertThat(exchange("/v1/pharmacy/setup", HttpMethod.GET, authed(token), Map.class).getBody().get("invoicingBlocked")).isEqualTo(false);
    }

    @Test
    void anOpeningStockListOf2000LinesShowsADryRunDiffAndCommitsInUnderTwoMinutes() {
        Shop shop = pharmacyShop();
        DateTimeFormatter dmy = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        LocalDate exp = LocalDate.now().plusYears(2);
        StringBuilder sb = new StringBuilder("Item Name,Batch No,Expiry Date,Quantity,MRP,Purchase Rate,Selling Price");
        for (int i = 1; i <= 2000; i++) {
            sb.append("\nSyn Item ").append(i).append(",B-").append(i).append(',').append(exp.format(dmy)).append(",10,25.00,14.00,22.50");
        }
        long t0 = System.nanoTime();
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(shop.token());
        h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        String uploadId = (String) exchange("/v1/setup/import-uploads?fileName=stock.csv&format=csv", HttpMethod.POST,
                new HttpEntity<>(sb.toString().getBytes(StandardCharsets.UTF_8), h), Map.class).getBody().get("uploadId");
        ResponseEntity<Map> job = exchange("/v1/setup/import-jobs", HttpMethod.POST,
                authedJsonBody(shop.token(), Map.of("uploadId", uploadId, "importType", "stock", "source", "csv")), Map.class);
        assertThat(job.getStatusCode()).as(String.valueOf(job.getBody())).isEqualTo(HttpStatus.CREATED);
        String id = (String) job.getBody().get("id");
        Map<String, Object> dry = awaitJob(shop, id, "dry_run");
        assertThat((Map<String, Object>) dry.get("diff")).containsEntry("ready", 2000).containsEntry("errors", 0);
        assertThat(su().queryForObject("SELECT count(*) FROM stock_batches WHERE tenant_id = ?", Integer.class, shop.t())).as("a dry run writes nothing").isZero();
        assertThat(exchange("/v1/setup/import-jobs/" + id + "/commit", HttpMethod.POST, authedJsonBody(shop.token(), Map.of("duplicateDecisions", List.of())), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map<String, Object> done = awaitJob(shop, id, "done");
        long seconds = Duration.ofNanos(System.nanoTime() - t0).toSeconds();
        assertThat((Map<String, Object>) done.get("diff")).containsEntry("created", 2000);
        assertThat(su().queryForObject("SELECT count(*) FROM stock_batches WHERE tenant_id = ?", Integer.class, shop.t())).isEqualTo(2000);
        assertThat(seconds).as("upload, dry run and commit of 2,000 lines, seconds").isLessThan(120);
    }

    private Map<String, Object> awaitJob(Shop shop, String id, String status) {
        Map<String, Object>[] last = new Map[1];
        await().atMost(Duration.ofSeconds(110)).pollInterval(Duration.ofMillis(250)).until(() -> {
            last[0] = exchange("/v1/setup/import-jobs/" + id, HttpMethod.GET, authed(shop.token()), Map.class).getBody();
            assertThat(last[0].get("status")).as("job failed: " + last[0].get("errorMessage")).isNotEqualTo("failed");
            return status.equals(last[0].get("status"));
        });
        return last[0];
    }
}

package com.nabd.hms.billing;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PHA-010 (counter sale gated by pharmacy mode) and QA-OI-2 (catalogue lines priced server-side). */
class CheckoutServerPricingApiTest extends ApiTestBase {

    private String token(SeededTenant tenant, String email, String phone) {
        return loginAndGetAccessToken(seedStaff(tenant, seedFullAccessRole(tenant.id()), email, phone, false));
    }

    private void setMode(SeededTenant tenant, String mode) {
        inTenantTx(tenant.id(), () -> jdbc.update(
                "INSERT INTO pharmacy_settings (tenant_id, mode) VALUES (?, ?) ON CONFLICT (tenant_id) DO UPDATE SET mode = EXCLUDED.mode",
                tenant.id(), mode));
    }

    private void catalogue(SeededTenant tenant, String code, String category, double base, Double followUp, double tax, boolean override) {
        inTenantTx(tenant.id(), () -> jdbc.update(
                "INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, follow_up_amount, tax_rate_percent, doctor_override) " +
                        "VALUES (?,?,?,?,?,?,?,?)", tenant.id(), code, "Cat " + code, category, base, followUp, tax, override));
    }

    private ResponseEntity<Map> otc(String token, Map<String, Object> line) {
        return exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(token, Map.of("lineItems", List.of(line))), Map.class);
    }

    private Map<String, Object> line(String code, String name, double price, double tax) {
        Map<String, Object> l = new java.util.HashMap<>(Map.of("chargeCode", code, "chargeName", name, "category", "Client", "quantity", 2, "unitPrice", price, "taxRatePercent", tax));
        if (code.equals("ADHOC") || code.equals("FREE")) l.put("override", Map.of("reasonCode", "other")); // FIN-003: free text needs a reason
        return l;
    }

    @Test
    void externalModeBlocksCounterSalesButHybridAndInHouseAllowThem() {
        SeededTenant tenant = seedTenant();
        String token = token(tenant, "otc1@a.com", "+919710000001");

        // default (no pharmacy_settings row) is external
        for (ResponseEntity<Map> r : List.of(
                exchange("/v1/billing/otc-charges", HttpMethod.GET, authed(token), Map.class),
                otc(token, line("FREE", "Free text", 100, 0)))) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(r.getBody().get("type")).asString().contains("otc-not-available");
        }
        setMode(tenant, "external");
        assertThat(exchange("/v1/billing/otc-charges", HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        setMode(tenant, "hybrid");
        assertThat(exchange("/v1/billing/otc-charges", HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otc(token, line("FREE", "Free text", 100, 0)).getStatusCode()).isEqualTo(HttpStatus.OK);
        setMode(tenant, "in_house");
        assertThat(exchange("/v1/billing/otc-charges", HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otc(token, line("FREE", "Free text", 100, 0)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void tamperedPriceNameAndTaxOnACatalogueLineAreIgnoredInOtcCheckout() {
        SeededTenant tenant = seedTenant();
        String token = token(tenant, "otc2@a.com", "+919710000002");
        setMode(tenant, "hybrid");
        catalogue(tenant, "XRAY", "Procedure", 500, null, 10, false);

        ResponseEntity<Map> resp = otc(token, line("XRAY", "Hacked", 1, 0));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 2 x 500 @ 10% = 1000 + 100
        assertThat(((Number) resp.getBody().get("subtotal")).doubleValue()).isEqualTo(1000.00);
        assertThat(((Number) resp.getBody().get("total")).doubleValue()).isEqualTo(1100.00);
        Map<?, ?> li = (Map<?, ?>) ((List<?>) resp.getBody().get("lineItems")).get(0);
        assertThat(((Number) li.get("unitPrice")).doubleValue()).isEqualTo(500.00);
        assertThat(li.get("chargeName")).isEqualTo("Cat XRAY");
        assertThat(li.get("category")).isEqualTo("Procedure");
    }

    @Test
    void doctorOverrideCatalogueLineKeepsTheClientPrice() {
        SeededTenant tenant = seedTenant();
        String token = token(tenant, "otc3@a.com", "+919710000003");
        setMode(tenant, "hybrid");
        catalogue(tenant, "CUSTOM", "Procedure", 500, null, 0, true);

        ResponseEntity<Map> resp = otc(token, line("CUSTOM", "x", 300, 0));
        assertThat(((Number) resp.getBody().get("subtotal")).doubleValue()).isEqualTo(600.00);
    }

    @Test
    void lineWithoutCatalogueEntryKeepsClientValuesButNegativePriceAndBlankNameAreRejected() {
        SeededTenant tenant = seedTenant();
        String token = token(tenant, "otc4@a.com", "+919710000004");
        setMode(tenant, "hybrid");

        assertThat(((Number) otc(token, line("ADHOC", "Ad hoc", 75, 0)).getBody().get("subtotal")).doubleValue()).isEqualTo(150.00);
        assertThat(otc(token, line("ADHOC", "Ad hoc", -5, 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(otc(token, line("ADHOC", " ", 5, 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void checkoutPricesCatalogueLinesFromTheServerAndUsesFollowUpAmountOnlyWhenEligible() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "co1@a.com", "+919710000005", false);
        String token = loginAndGetAccessToken(staff);
        catalogue(tenant, "CONS", "Consultation", 400, 150.0, 0, false);

        String first = checkoutPending(token, staff.id(), "P1", "+919999940001");
        // tampered: client claims 1 rupee; first visit is not follow-up -> base 400
        ResponseEntity<Map> inv = exchange("/v1/billing/checkout/" + first, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", "CONS", "chargeName", "x", "category", "Consultation",
                        "quantity", 1, "unitPrice", 1, "taxRatePercent", 0)))), Map.class);
        assertThat(inv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) inv.getBody().get("total")).doubleValue()).isEqualTo(400.00);

        // same patient + doctor again, within 14 days of a completed visit -> follow-up amount 150, even if client sends 400
        String patientId = (String) exchange("/v1/billing/invoices/" + inv.getBody().get("id"), HttpMethod.GET, authed(token), Map.class)
                .getBody().get("patientId");
        String second = checkIn(token, patientId, staff.id());
        move(token, second, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        ResponseEntity<Map> inv2 = exchange("/v1/billing/checkout/" + second, HttpMethod.POST, authedJsonBody(token, Map.of(
                "lineItems", List.of(Map.of("chargeCode", "CONS", "chargeName", "x", "category", "Consultation",
                        "quantity", 1, "unitPrice", 400, "taxRatePercent", 0)))), Map.class);
        assertThat(((Number) inv2.getBody().get("total")).doubleValue()).isEqualTo(150.00);
    }

    private String checkIn(String token, String patientId, UUID doctorId) {
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class).getBody().get("id");
    }

    private void move(String token, String id, String... statuses) {
        for (String s : statuses) {
            exchange("/v1/queue/" + id + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", s)), Map.class);
        }
    }

    private String checkoutPending(String token, UUID doctorId, String name, String phone) {
        String patientId = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "male")), Map.class).getBody().get("id");
        String id = checkIn(token, patientId, doctorId);
        move(token, id, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        return id;
    }
}

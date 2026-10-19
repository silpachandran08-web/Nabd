package com.nabd.hms.billing;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FIN-003: server prices every line from the catalogue; overrides and discounts need billing.discount, a reason, and add up. */
class Fin003ServerPricingApiTest extends ApiTestBase {

    private static final java.util.concurrent.atomic.AtomicInteger PHONES = new java.util.concurrent.atomic.AtomicInteger((int) (System.nanoTime() % 5_000_000));
    private int seq = 0;

    private record Env(SeededTenant tenant, SeededStaff owner, String ownerToken) {
    }

    private Env env() {
        SeededTenant t = seedTenant();
        SeededStaff owner = seedStaff(t, seedFullAccessRole(t.id()), "own" + t.id() + "@a.com", phone(), false);
        String token = loginAndGetAccessToken(owner);
        catalogue(t, "CONS", "Consultation", 400, 150.0, 0);
        catalogue(t, "XRAY", "Procedure", 500, null, 10);
        return new Env(t, owner, token);
    }

    private String phone() {
        return "+91971" + String.format("%07d", 1_000_000 + PHONES.getAndIncrement());
    }

    private void catalogue(SeededTenant t, String code, String category, double base, Double followUp, double tax) {
        inTenantTx(t.id(), () -> jdbc.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, follow_up_amount, tax_rate_percent) VALUES (?,?,?,?,?,?,?)",
                t.id(), code, "Cat " + code, category, base, followUp, tax));
    }

    private String tokenFor(Env e, String roleName, boolean billingView, boolean billingCreate, boolean discount, boolean queue) {
        List<ModuleGrant> g = new java.util.ArrayList<>();
        if (billingView || billingCreate) g.add(new ModuleGrant("billing", billingView, billingCreate, billingCreate, false, false, discount, false));
        if (queue) { g.add(fullGrant("queue")); g.add(fullGrant("patients")); }
        UUID role = seedRole(e.tenant().id(), roleName, false, g.toArray(new ModuleGrant[0]));
        return loginAndGetAccessToken(seedStaff(e.tenant(), role, roleName + e.tenant().id() + "@a.com", phone(), false));
    }

    private String patient(String token) {
        return (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "P" + seq++, "phone", phone(), "dob", "1990-01-01", "gender", "male")), Map.class).getBody().get("id");
    }

    private String checkIn(String token, String patientId, UUID doctorId) {
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class).getBody().get("id");
    }

    private void move(String token, String id, String... statuses) {
        for (String s : statuses) exchange("/v1/queue/" + id + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", s)), Map.class);
    }

    private String pending(Env e) {
        String id = checkIn(e.ownerToken(), patient(e.ownerToken()), e.owner().id());
        move(e.ownerToken(), id, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        return id;
    }

    private ResponseEntity<Map> post(String token, String entry, Map<String, Object> body) {
        return exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private static Map<String, Object> line(String code, int qty) {
        return new HashMap<>(Map.of("chargeCode", code, "quantity", qty));
    }

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    private static Map<?, ?> firstLine(ResponseEntity<Map> r) {
        return (Map<?, ?>) ((List<?>) r.getBody().get("lineItems")).get(0);
    }

    private static String type(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("type"));
    }

    // AC1 (J9) + AC6
    @Test
    void receptionistPriceZeroIsIgnoredAndCataloguePriceIsBilledAndReturned() {
        Env e = env();
        String reception = tokenFor(e, "Reception", true, true, false, true);
        String entry = pending(e);
        Map<String, Object> l = line("CONS", 1);
        l.put("unitPrice", 0);
        l.put("chargeName", "free");
        l.put("taxRatePercent", 0);
        l.put("tier", "follow_up"); // client cannot force the follow-up price

        ResponseEntity<Map> r = post(reception, entry, Map.of("lineItems", List.of(l)));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(num(firstLine(r).get("unitPrice"))).isEqualTo(400.0);
        assertThat(num(firstLine(r).get("listUnitPrice"))).isEqualTo(400.0);
        assertThat(firstLine(r).get("tier")).isEqualTo("normal");
        assertThat(firstLine(r).get("chargeName")).isEqualTo("Cat CONS");
        assertThat(num(r.getBody().get("total"))).isEqualTo(400.0);
    }

    // AC2
    @Test
    void unknownChargeIs422WithoutDiscountPermissionAndFreeTextIsAllowedWithIt() {
        Env e = env();
        String reception = tokenFor(e, "Reception", true, true, false, true);
        Map<String, Object> ghost = line("GHOST", 1);
        ghost.putAll(Map.of("chargeName", "Ghost", "category", "Service", "unitPrice", 0, "taxRatePercent", 0));

        ResponseEntity<Map> denied = post(reception, pending(e), Map.of("lineItems", List.of(ghost)));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(type(denied)).contains("unknown-charge");

        ghost.put("unitPrice", 75);
        ghost.put("override", Map.of("reasonCode", "other")); // FIN-003 (QA-12): free text needs a reason
        ResponseEntity<Map> ok = post(e.ownerToken(), pending(e), Map.of("lineItems", List.of(ghost)));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstLine(ok).get("tier")).isEqualTo("custom");
        assertThat(num(firstLine(ok).get("unitPrice"))).isEqualTo(75.0);
    }

    // AC3 + AC7 (role x field matrix) + nfr audit
    @Test
    void overrideNeedsPermissionThenAReasonThenIsStoredAndAudited() {
        Env e = env();
        String reception = tokenFor(e, "Reception", true, true, false, true);
        String billing = tokenFor(e, "Billing", true, true, false, false);
        String doctor = tokenFor(e, "Doctor", false, false, false, true);
        Map<String, Object> o = new HashMap<>(Map.of("unitPrice", 300, "reasonCode", "goodwill"));
        Map<String, Object> l = line("CONS", 1);
        l.put("override", o);
        String entry = pending(e);

        // matrix: reception and billing (no billing.discount) are refused, doctor has no billing grant at all
        for (String token : List.of(reception, billing)) {
            ResponseEntity<Map> r = post(token, entry, Map.of("lineItems", List.of(l)));
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(type(r)).contains("discount-not-permitted");
        }
        assertThat(post(doctor, entry, Map.of("lineItems", List.of(l))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // discount-bearing field is refused for them too
        assertThat(type(post(reception, entry, Map.of("lineItems", List.of(line("CONS", 1)),
                "discounts", List.of(Map.of("kind", "amount", "value", 10, "reasonCode", "goodwill")))))).contains("discount-not-permitted");

        // owner: reason required, unknown reason refused
        o.remove("reasonCode");
        ResponseEntity<Map> noReason = post(e.ownerToken(), entry, Map.of("lineItems", List.of(l)));
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(type(noReason)).contains("reason-required");
        o.put("reasonCode", "not_a_reason");
        assertThat(type(post(e.ownerToken(), entry, Map.of("lineItems", List.of(l))))).contains("invalid-reason");

        // nothing was billed by the refusals
        assertThat(count(e, "SELECT count(*) FROM invoices")).isZero();

        o.put("reasonCode", "goodwill");
        o.put("note", "regular patient");
        ResponseEntity<Map> ok = post(e.ownerToken(), entry, Map.of("lineItems", List.of(l)));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(num(firstLine(ok).get("unitPrice"))).isEqualTo(300.0);
        assertThat(num(firstLine(ok).get("listUnitPrice"))).isEqualTo(400.0);
        assertThat(firstLine(ok).get("overrideReason")).isEqualTo("goodwill");

        inTenantTx(e.tenant().id(), () -> {
            Map<String, Object> row = jdbc.queryForMap("SELECT list_unit_price, unit_price, override_reason_code, override_note, overridden_by, charge_catalogue_id FROM invoice_line_items");
            assertThat(((BigDecimal) row.get("list_unit_price")).doubleValue()).isEqualTo(400.0);
            assertThat(((BigDecimal) row.get("unit_price")).doubleValue()).isEqualTo(300.0);
            assertThat(row.get("override_reason_code")).isEqualTo("goodwill");
            assertThat(row.get("override_note")).isEqualTo("regular patient");
            assertThat(row.get("overridden_by").toString()).isEqualTo(e.owner().id().toString());
            assertThat(row.get("charge_catalogue_id")).isNotNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'billing.price_overridden'", Integer.class)).isEqualTo(1);
        });
    }

    // AC4: interim checkpoint 100 + checkout 50 = 150, both rows listed; AC5 exceeds
    @Test
    void discountsAccumulateAcrossCheckpointsAndCannotExceedTheSubtotal() {
        Env e = env();
        var t = e.tenant();
        String dept = (String) ((Map<?, ?>) exchange("/v1/departments", HttpMethod.GET, authed(e.ownerToken()), List.class).getBody().stream()
                .filter(d -> Boolean.TRUE.equals(((Map<?, ?>) d).get("isDefault"))).findFirst().orElseThrow()).get("id");
        exchange("/v1/departments/" + dept + "/workflow", HttpMethod.POST, authedJsonBody(e.ownerToken(), Map.of(
                "templateCode", "clinic_walkin_with_billing", "toggles", Map.of("vitals_enabled", true))), Map.class);
        String entry = checkIn(e.ownerToken(), patient(e.ownerToken()), e.owner().id());
        move(e.ownerToken(), entry, "waiting", "billing_pending");

        Map<String, Object> d100 = Map.of("kind", "amount", "value", 100, "reasonCode", "goodwill");
        ResponseEntity<Map> tooBig = post(e.ownerToken(), entry, Map.of("lineItems", List.of(line("CONS", 1)),
                "discounts", List.of(Map.of("kind", "amount", "value", 400.01, "reasonCode", "goodwill"))));
        assertThat(tooBig.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(type(tooBig)).contains("discount-exceeds-subtotal");
        assertThat(count(e, "SELECT count(*) FROM invoices")).isZero();
        assertThat(count(e, "SELECT count(*) FROM invoice_discounts")).isZero();

        ResponseEntity<Map> interim = post(e.ownerToken(), entry, Map.of("lineItems", List.of(line("CONS", 1)), "discounts", List.of(d100)));
        assertThat(interim.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(num(interim.getBody().get("discount"))).isEqualTo(100.0);
        move(e.ownerToken(), entry, "vitals_done", "in_consult", "checkout_pending");

        ResponseEntity<Map> fin = post(e.ownerToken(), entry, Map.of("lineItems", List.of(),
                "discounts", List.of(Map.of("kind", "amount", "value", 50, "reasonCode", "staff", "note", "n"))));
        assertThat(fin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(num(fin.getBody().get("discount"))).isEqualTo(150.0);
        assertThat(num(fin.getBody().get("total"))).isEqualTo(250.0);
        inTenantTx(t.id(), () -> {
            assertThat(jdbc.queryForList("SELECT amount FROM invoice_discounts ORDER BY created_at, amount DESC", BigDecimal.class))
                    .extracting(BigDecimal::doubleValue).containsExactlyInAnyOrder(100.0, 50.0);
            assertThat(jdbc.queryForObject("SELECT discount FROM invoices", BigDecimal.class).doubleValue()).isEqualTo(150.0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'billing.discount_applied'", Integer.class)).isEqualTo(2);
        });
    }

    @Test
    void percentDiscountIsBookedAsItsAmountAndTheLegacyScalarStillWorks() {
        Env e = env();
        ResponseEntity<Map> r = post(e.ownerToken(), pending(e), Map.of("lineItems", List.of(line("XRAY", 2)),
                "discounts", List.of(Map.of("kind", "percent", "value", 10, "reasonCode", "staff")), "discount", 5));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        // subtotal 1000: legacy 5 first, then 10% of the remaining 995 = 99.50
        assertThat(num(r.getBody().get("discount"))).isEqualTo(104.5);
        assertThat(count(e, "SELECT count(*) FROM invoice_discounts WHERE reason_code = 'unspecified'")).isEqualTo(1);
    }

    // AC6: follow-up chosen by the server
    @Test
    void followUpPriceIsPickedByTheServerOnlyWhenEligible() {
        Env e = env();
        String patientId = patient(e.ownerToken());
        String first = checkIn(e.ownerToken(), patientId, e.owner().id());
        move(e.ownerToken(), first, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        Map<String, Object> asked = line("CONS", 1);
        asked.put("tier", "follow_up");
        ResponseEntity<Map> r1 = post(e.ownerToken(), first, Map.of("lineItems", List.of(asked)));
        assertThat(num(firstLine(r1).get("unitPrice"))).isEqualTo(400.0); // first visit: asking for follow_up changes nothing

        String second = checkIn(e.ownerToken(), patientId, e.owner().id());
        ResponseEntity<Map> ctx = exchange("/v1/billing/checkout/" + second, HttpMethod.GET, authed(e.ownerToken()), Map.class);
        assertThat(ctx.getBody().get("followUpEligible")).isEqualTo(true);
        Map<?, ?> suggested = (Map<?, ?>) ((List<?>) ctx.getBody().get("suggestedLines")).get(0);
        assertThat(suggested.get("tier")).isEqualTo("follow_up");
        assertThat(num(suggested.get("unitPrice"))).isEqualTo(150.0);
        move(e.ownerToken(), second, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        Map<String, Object> normal = line("CONS", 1);
        normal.put("tier", "normal");
        ResponseEntity<Map> r2 = post(e.ownerToken(), second, Map.of("lineItems", List.of(normal)));
        assertThat(num(firstLine(r2).get("unitPrice"))).isEqualTo(150.0);
        assertThat(firstLine(r2).get("tier")).isEqualTo("follow_up");
    }

    @Test
    void inactiveCatalogueCodeCountsAsUnknownForARoleWithoutDiscount() {
        Env e = env();
        inTenantTx(e.tenant().id(), () -> jdbc.update("UPDATE charge_catalogue SET active = false WHERE code = 'XRAY'"));
        String reception = tokenFor(e, "Reception", true, true, false, true);
        assertThat(type(post(reception, pending(e), Map.of("lineItems", List.of(line("XRAY", 1)))))).contains("unknown-charge");
    }

    // RLS: another tenant's session sees none of this tenant's ledger
    @Test
    void discountLedgerIsTenantIsolated() {
        Env a = env();
        post(a.ownerToken(), pending(a), Map.of("lineItems", List.of(line("CONS", 1)),
                "discounts", List.of(Map.of("kind", "amount", "value", 10, "reasonCode", "staff"))));
        SeededTenant b = seedTenant();
        assertThat(count(a, "SELECT count(*) FROM invoice_discounts")).isEqualTo(1);
        assertThat(inTenantTx(b.id(), () -> jdbc.queryForObject("SELECT count(*) FROM invoice_discounts", Integer.class))).isZero();
    }

    private int count(Env e, String sql) {
        return inTenantTx(e.tenant().id(), () -> jdbc.queryForObject(sql, Integer.class));
    }
}

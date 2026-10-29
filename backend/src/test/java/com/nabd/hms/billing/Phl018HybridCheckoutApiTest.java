package com.nabd.hms.billing;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** PHL-018: checkout pre-loads the visit's in-house Rx items; "dispense anyway" keeps stock at zero or above and leaves a stock discrepancy. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl018HybridCheckoutApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 700_000);

    private record Ctx(SeededTenant tenant, SeededStaff staff, String token) {
    }

    private static String phone() {
        return "+9196" + String.format("%08d", SEQ.incrementAndGet());
    }

    private Ctx ctx(String mode) {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = ClinicianFixture.licensed(seedStaff(tenant, seedFullAccessRole(tenant.id()), "p18" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        String token = loginAndGetAccessToken(staff);
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", mode, "reason", "PHL-018 test")),
                Map.class).getStatusCode().is2xxSuccessful()).isTrue();
        return new Ctx(tenant, staff, token);
    }

    /** Rx-only in-house (or bought-outside) catalogue item; the name is what the prescription line is matched on. */
    private Map<String, Object> item(Ctx c, String name, int stock, boolean inHouse) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", name, "isRx", true, "hsnCode", "3004", "price", 10.00, "taxRatePercent", 12, "stockQty", stock, "inHouse", inHouse)), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("item %s", r.getBody()).isTrue();
        return r.getBody();
    }

    private String visit(Ctx c) {
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", "Prefill Patient " + SEQ.incrementAndGet(), "phone", phone(),
                "dob", LocalDate.of(1960, 1, 1).plusDays(SEQ.get() % 9000).toString(), "gender", "female")), Map.class).getBody().get("id");
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patient, "doctorId", c.staff().id().toString())), Map.class).getBody().get("id");
    }

    private void toCheckout(Ctx c, String qe) {
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + qe + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", s)), Map.class);
        }
    }

    /** Signs a prescription of {name, qty} lines for the visit; returns the prescription item ids in order. */
    private List<String> prescribe(Ctx c, String qe, Object[]... lines) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object[] l : lines) {
            items.add(Map.of("drugName", l[0], "dosage", "1 tab", "frequency", "BD", "quantity", l[1], "quantityUnit", "tablets"));
        }
        ResponseEntity<Map> saved = exchange("/v1/clinical/prescriptions/" + qe, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("items", items)), Map.class);
        assertThat(saved.getStatusCode()).as("rx %s", saved.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> signed = exchange("/v1/clinical/prescriptions/" + qe + "/sign", HttpMethod.POST, authed(c.token()), Map.class);
        assertThat(signed.getStatusCode()).as("sign %s", signed.getBody()).isEqualTo(HttpStatus.OK);
        List<String> ids = new ArrayList<>();
        for (Object o : (List<?>) saved.getBody().get("items")) ids.add((String) ((Map<?, ?>) o).get("id"));
        return ids;
    }

    private ResponseEntity<Map> prefill(Ctx c, String qe) {
        return exchange("/v1/billing/checkout/" + qe + "/prefill", HttpMethod.GET, authed(c.token()), Map.class);
    }

    private static Map<String, Object> line(String code, int qty, String rxItem, String shortfallReason) {
        Map<String, Object> m = new HashMap<>(Map.of("chargeCode", code, "quantity", qty, "prescriptionItemId", rxItem));
        if (shortfallReason != null) m.put("shortfallReason", shortfallReason);
        return m;
    }

    private ResponseEntity<Map> collect(Ctx c, String qe, List<Map<String, Object>> lines) {
        return exchange("/v1/billing/checkout/" + qe, HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", lines)), Map.class);
    }

    private int stock(Ctx c, Object itemId) {
        return inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT stock_qty FROM charge_catalogue WHERE id = ?::uuid", Integer.class, String.valueOf(itemId)));
    }

    private int count(Ctx c, String sql, Object... args) {
        return inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    @Test
    void prefillListsOnlyTheInHouseItemsAndCollectBillsAndDeductsOnOneInvoice() {
        Ctx c = ctx("hybrid");
        Map<String, Object> a = item(c, "Pf drug alpha", 20, true), b = item(c, "Pf drug beta", 5, true);
        item(c, "Pf drug gamma", 9, false); // matched but bought outside
        String qe = visit(c);
        // a consultation charge on the same bill
        new JdbcTemplate(superuserDataSource()).update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, tax_rate_percent) VALUES (?, 'CONS18', 'Consult', 'Consultation', 400, 0)", c.tenant().id());
        List<String> rx = prescribe(c, qe, new Object[]{"Pf drug alpha", 4}, new Object[]{"Pf drug beta", 2}, new Object[]{"Pf drug gamma", 3});
        toCheckout(c, qe);

        ResponseEntity<Map> p = prefill(c, qe);
        assertThat(p.getStatusCode()).as("%s", p.getBody()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> lines = (List<Map<String, Object>>) p.getBody().get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).containsEntry("chargeCode", a.get("code")).containsEntry("qty", 4).containsEntry("prescriptionItemId", rx.get(0)).containsEntry("onHand", 20);
        assertThat(lines.get(1)).containsEntry("chargeCode", b.get("code")).containsEntry("qty", 2).containsEntry("prescriptionItemId", rx.get(1)).containsEntry("onHand", 5);

        List<Map<String, Object>> bill = new ArrayList<>();
        bill.add(new HashMap<>(Map.of("chargeCode", "CONS18", "quantity", 1)));
        for (Map<String, Object> l : lines) bill.add(line((String) l.get("chargeCode"), (Integer) l.get("qty"), (String) l.get("prescriptionItemId"), null));
        ResponseEntity<Map> inv = collect(c, qe, bill);
        assertThat(inv.getStatusCode()).as("%s", inv.getBody()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> invLines = (List<Map<String, Object>>) inv.getBody().get("lineItems");
        assertThat(invLines).hasSize(3);
        assertThat(invLines.get(1).get("taxes")).as("per-line tax on the medicine line").isNotNull();
        assertThat((List) invLines.get(1).get("taxes")).isNotEmpty();
        assertThat(stock(c, a.get("id"))).isEqualTo(16);
        assertThat(stock(c, b.get("id"))).isEqualTo(3);
        assertThat(count(c, "SELECT count(*) FROM dispenses WHERE source = 'nabd_rx'")).isEqualTo(1);
        assertThat(count(c, "SELECT count(*) FROM stock_discrepancies")).isZero();
        // once billed, nothing is left to pre-fill
        assertThat((List) prefill(c, qe).getBody().get("lines")).isEmpty();
    }

    @Test
    void dispenseAnywayAtZeroStockCompletesTheBillKeepsStockAtZeroAndRecordsADiscrepancy() {
        Ctx c = ctx("hybrid");
        Map<String, Object> a = item(c, "Sf drug zero", 0, true);
        String qe = visit(c);
        String rx = prescribe(c, qe, new Object[]{"Sf drug zero", 2}).get(0);
        toCheckout(c, qe);
        Map<?, ?> pf = (Map<?, ?>) ((List<?>) prefill(c, qe).getBody().get("lines")).get(0);
        assertThat(pf.get("onHand")).isEqualTo(0);

        ResponseEntity<Map> refused = collect(c, qe, List.of(line((String) a.get("code"), 2, rx, null)));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(refused.getBody().get("type"))).endsWith("/insufficient-stock");

        ResponseEntity<Map> blank = collect(c, qe, List.of(line((String) a.get("code"), 2, rx, "   ")));
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(String.valueOf(blank.getBody().get("type"))).endsWith("/reason-required");
        assertThat(count(c, "SELECT count(*) FROM invoices")).isZero();

        ResponseEntity<Map> ok = collect(c, qe, List.of(line((String) a.get("code"), 2, rx, "Shelf count not updated")));
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(stock(c, a.get("id"))).isZero();
        assertThat(count(c, "SELECT count(*) FROM pharmacy_stock_movements WHERE item_id = ?::uuid AND kind = 'dispense'", a.get("id"))).isZero();
        assertThat(count(c, "SELECT count(*) FROM stock_discrepancies WHERE item_id = ?::uuid AND qty = 2 AND reason = 'Shelf count not updated' " +
                "AND dispense_id IS NOT NULL AND resolved_by_stock_take_id IS NULL", a.get("id"))).isEqualTo(1);
        assertThat(count(c, "SELECT count(*) FROM dispenses")).isEqualTo(1);

        // voiding that invoice must not conjure the two units that never left the shelf
        ResponseEntity<Map> voided = exchange("/v1/billing/invoices/" + ok.getBody().get("id") + "/void", HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("reason", "test void")), Map.class);
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(stock(c, a.get("id"))).isZero();
    }

    @Test
    void partialShortfallIssuesWhatIsOnHandAndRecordsTheRest() {
        Ctx c = ctx("hybrid");
        Map<String, Object> a = item(c, "Sf drug part", 1, true);
        String qe = visit(c);
        String rx = prescribe(c, qe, new Object[]{"Sf drug part", 3}).get(0);
        toCheckout(c, qe);
        ResponseEntity<Map> ok = collect(c, qe, List.of(line((String) a.get("code"), 3, rx, "Delivery not booked in")));
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(stock(c, a.get("id"))).isZero();
        assertThat(count(c, "SELECT count(*) FROM pharmacy_stock_movements WHERE item_id = ?::uuid AND kind = 'dispense' AND delta = -1", a.get("id"))).isEqualTo(1);
        assertThat(count(c, "SELECT count(*) FROM stock_discrepancies WHERE item_id = ?::uuid AND qty = 2", a.get("id"))).isEqualTo(1);
    }

    @Test
    void aReasonIsIgnoredWhenTheShelfCoversTheQuantity() {
        Ctx c = ctx("hybrid");
        Map<String, Object> a = item(c, "Sf drug fine", 5, true);
        String qe = visit(c);
        String rx = prescribe(c, qe, new Object[]{"Sf drug fine", 3}).get(0);
        toCheckout(c, qe);
        assertThat(collect(c, qe, List.of(line((String) a.get("code"), 3, rx, "just in case"))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stock(c, a.get("id"))).isEqualTo(2);
        assertThat(count(c, "SELECT count(*) FROM stock_discrepancies")).isZero();
    }

    @Test
    void externalPharmacyUnknownVisitAndMissingPermissionAreHandled() {
        Ctx ext = ctx("external");
        String qe = visit(ext);
        prescribe(ext, qe, new Object[]{"Ext drug", 2});
        toCheckout(ext, qe);
        ResponseEntity<Map> p = prefill(ext, qe);
        assertThat(p.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List) p.getBody().get("lines")).isEmpty();
        assertThat(prefill(ext, UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // view-only billing role: no prefill
        UUID role = seedRole(ext.tenant().id(), "BillView", false, new ModuleGrant("billing", true, false, false, false, false, false, false));
        SeededStaff s = seedStaff(ext.tenant(), role, "billview" + SEQ.incrementAndGet() + "@q.com", phone(), false);
        assertThat(prefill(new Ctx(ext.tenant(), s, loginAndGetAccessToken(s)), qe).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anotherClinicCannotSeeTheDiscrepancyOrDispenseRows() {
        Ctx a = ctx("hybrid"), b = ctx("hybrid");
        Map<String, Object> it = item(a, "Iso drug", 0, true);
        String qe = visit(a);
        String rx = prescribe(a, qe, new Object[]{"Iso drug", 1}).get(0);
        toCheckout(a, qe);
        assertThat(collect(a, qe, List.of(line((String) it.get("code"), 1, rx, "iso"))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count(a, "SELECT count(*) FROM stock_discrepancies")).isEqualTo(1);
        assertThat(count(b, "SELECT count(*) FROM stock_discrepancies")).isZero();
        assertThat(count(b, "SELECT count(*) FROM dispenses")).isZero();
        // and B's session cannot write A's tenant onto a row
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> inTenantTx(b.tenant().id(), () -> jdbc.update(
                "INSERT INTO stock_discrepancies (tenant_id, item_id, qty, reason, created_by) VALUES (?, ?::uuid, 1, 'x', ?)",
                a.tenant().id(), it.get("id"), a.staff().id()))).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}

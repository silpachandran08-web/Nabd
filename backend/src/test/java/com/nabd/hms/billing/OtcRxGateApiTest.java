package com.nabd.hms.billing;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PHL-001 (hazard H-04): prescription-only lines need a signed prescription line of the same patient with quantity left. */
class OtcRxGateApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000));

    private record Ctx(SeededTenant tenant, SeededStaff staff, String token) {
    }

    private record Rx(String patientId, String queueEntryId, String itemId) {
    }

    private static String phone() {
        return "+9198" + String.format("%08d", SEQ.incrementAndGet());
    }

    private Ctx ctx() {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = seedStaff(tenant, roleId, "rxg" + SEQ.incrementAndGet() + "@a.com", phone(), false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        return new Ctx(tenant, staff, token);
    }

    /** isRx is the clinic's own flag on its catalogue item; the names here are test fixtures, not a clinical list. */
    private String item(Ctx c, String name, boolean isRx, int stock) {
        ResponseEntity<Map> item = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", name, "isRx", isRx, "hsnCode", "3004", "price", 10.00, "taxRatePercent", 0, "stockQty", stock)), Map.class);
        assertThat(item.getStatusCode().is2xxSuccessful()).as("item create").isTrue();
        return (String) item.getBody().get("code");
    }

    private String patient(Ctx c, String phone) {
        ResponseEntity<Map> resp = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", "Rx Patient " + SEQ.incrementAndGet(), "phone", phone,
                // distinct DOBs: same DOB + similar name would be held back as a possible duplicate
                "dob", java.time.LocalDate.of(1950, 1, 1).plusDays(SEQ.incrementAndGet() % 20_000).toString(), "gender", "male")), Map.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).as("patient create " + resp.getBody()).isTrue();
        return (String) resp.getBody().get("id");
    }

    private String checkIn(Ctx c, String patientId) {
        ResponseEntity<Map> resp = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patientId, "doctorId", c.staff().id().toString())), Map.class);
        return (String) resp.getBody().get("id");
    }

    private void toCheckout(Ctx c, String queueEntryId) {
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + queueEntryId + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", s)), Map.class);
        }
    }

    /** A prescription with one line of {@code qty} units; signed unless {@code sign} is false. */
    private Rx prescribe(Ctx c, String patientId, int qty, boolean sign) {
        String qe = checkIn(c, patientId);
        ResponseEntity<Map> saved = exchange("/v1/clinical/prescriptions/" + qe, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of(
                "items", List.of(Map.of("drugName", "Test drug", "dosage", "1 tab", "frequency", "OD", "quantity", qty, "quantityUnit", "tablets")))), Map.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        String itemId = (String) ((Map<?, ?>) ((List<?>) saved.getBody().get("items")).get(0)).get("id");
        if (sign) {
            assertThat(exchange("/v1/clinical/prescriptions/" + qe + "/sign", HttpMethod.POST, authed(c.token()), Map.class)
                    .getStatusCode().is2xxSuccessful()).as("sign").isTrue();
        }
        return new Rx(patientId, qe, itemId);
    }

    private ResponseEntity<Map> otc(Ctx c, List<Map<String, Object>> lines) {
        return exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", lines)), Map.class);
    }

    private static Map<String, Object> line(String code, int qty, String rxItemId) {
        Map<String, Object> m = new HashMap<>(Map.of("chargeCode", code, "quantity", qty));
        if (rxItemId != null) m.put("prescriptionItemId", rxItemId);
        return m;
    }

    private int stock(Ctx c, String code) {
        ResponseEntity<List> items = exchange("/v1/pharmacy/items", HttpMethod.GET, authed(c.token()), List.class);
        for (Object o : items.getBody()) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (code.equals(m.get("code"))) return ((Number) m.get("stockQty")).intValue();
        }
        throw new AssertionError("item not listed: " + code);
    }

    private static JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static int invoices(UUID tenantId) {
        return su().queryForObject("SELECT count(*) FROM invoices WHERE tenant_id = ?", Integer.class, tenantId);
    }

    private static void assertProblem(ResponseEntity<Map> r, String type) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat((String) r.getBody().get("type")).endsWith("/" + type);
    }

    @Test
    void otcRxLineWithoutPrescriptionIs422RxRequiredWritesNothingAndIsAudited() {
        Ctx c = ctx();
        String rx = item(c, "Rx Item A", true, 20);
        int before = invoices(c.tenant().id());

        ResponseEntity<Map> r = otc(c, List.of(line(rx, 2, null)));

        assertProblem(r, "rx-required");
        assertThat(r.getHeaders().getContentType().toString()).contains("problem+json");
        assertThat(invoices(c.tenant().id())).isEqualTo(before);
        assertThat(stock(c, rx)).isEqualTo(20);
        Map<String, Object> audit = su().queryForMap("SELECT actor_id, entity_type, after::text AS after FROM audit_log " +
                "WHERE tenant_id = ? AND action = 'pharmacy.rx_gate_blocked'", c.tenant().id());
        assertThat(audit.get("actor_id")).isEqualTo(c.staff().id());
        assertThat((String) audit.get("after")).contains(rx).contains("rx-required");
        Map<String, Object> event = su().queryForMap("SELECT payload::text AS p FROM outbox_events WHERE tenant_id = ? AND event_type = ?",
                c.tenant().id(), "pharmacy.rx_gate.blocked.v1");
        assertThat((String) event.get("p")).contains("rx-required").contains(c.staff().id().toString());
    }

    @Test
    void visitCheckoutUnlinkedRxIs422AndLinkedToOwnSignedRxSucceeds() {
        Ctx c = ctx();
        String rx = item(c, "Rx Item B", true, 20);
        String p = patient(c, phone());
        Rx script = prescribe(c, p, 10, true);
        toCheckout(c, script.queueEntryId());

        ResponseEntity<Map> blocked = exchange("/v1/billing/checkout/" + script.queueEntryId(), HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("lineItems", List.of(line(rx, 3, null)))), Map.class);
        assertProblem(blocked, "rx-required");

        // the checkout context hands billing the signed line id and what is left on it (pre-link)
        ResponseEntity<Map> context = exchange("/v1/billing/checkout/" + script.queueEntryId(), HttpMethod.GET, authed(c.token()), Map.class);
        Map<?, ?> prescribed = (Map<?, ?>) ((List<?>) context.getBody().get("prescribedItems")).get(0);
        assertThat(prescribed.get("prescriptionItemId")).isEqualTo(script.itemId());
        assertThat(((Number) prescribed.get("remainingQty")).intValue()).isEqualTo(10);
        assertThat(((List<?>) context.getBody().get("charges")).stream().map(o -> (Map<?, ?>) o)
                .filter(m -> rx.equals(m.get("code"))).findFirst().orElseThrow().get("isRx")).isEqualTo(true);

        ResponseEntity<Map> ok = exchange("/v1/billing/checkout/" + script.queueEntryId(), HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("lineItems", List.of(line(rx, 3, script.itemId())))), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stock(c, rx)).isEqualTo(17);
        Map<String, Object> row = su().queryForMap("SELECT is_rx, prescription_item_id FROM invoice_line_items WHERE tenant_id = ? AND charge_code = ?",
                c.tenant().id(), rx);
        assertThat(row.get("is_rx")).isEqualTo(true);
        assertThat(row.get("prescription_item_id")).isEqualTo(UUID.fromString(script.itemId()));
    }

    @Test
    void visitCheckoutLinkingAnotherPatientsPrescriptionIs422PatientMismatch() {
        Ctx c = ctx();
        String rx = item(c, "Rx Item C", true, 20);
        Rx forA = prescribe(c, patient(c, phone()), 10, true);
        String qeB = checkIn(c, patient(c, phone()));
        toCheckout(c, qeB);

        ResponseEntity<Map> r = exchange("/v1/billing/checkout/" + qeB, HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("lineItems", List.of(line(rx, 1, forA.itemId())))), Map.class);

        assertProblem(r, "rx-patient-mismatch");
        assertThat(stock(c, rx)).isEqualTo(20);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.rx_gate_blocked' " +
                "AND after->>'reason' = 'rx-patient-mismatch'", Integer.class, c.tenant().id())).isEqualTo(1);
    }

    @Test
    void otcLinesFromTwoPatientsAreAPatientMismatch() {
        Ctx c = ctx();
        String rx = item(c, "Rx Item C2", true, 20);
        Rx a = prescribe(c, patient(c, phone()), 10, true);
        Rx b = prescribe(c, patient(c, phone()), 10, true);
        assertProblem(otc(c, List.of(line(rx, 1, a.itemId()), line(rx, 1, b.itemId()))), "rx-patient-mismatch");
    }

    @Test
    void quantityIsCappedAtThePrescribedAmount() {
        Ctx c = ctx();
        String rx = item(c, "Rx Item D", true, 50);
        String p = patient(c, phone());
        Rx script = prescribe(c, p, 10, true);

        ResponseEntity<Map> first = otc(c, List.of(line(rx, 10, script.itemId()))); // boundary: exactly the prescribed 10
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("patientId")).isEqualTo(p); // the counter sale is tied to the prescription's patient

        assertProblem(otc(c, List.of(line(rx, 5, script.itemId()))), "rx-quantity-exhausted");
        assertThat(stock(c, rx)).isEqualTo(40);

        // a voided sale puts its units back on the prescription line too
        String invoiceId = (String) first.getBody().get("id");
        assertThat(exchange("/v1/billing/invoices/" + invoiceId + "/void", HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("reason", "Test void")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otc(c, List.of(line(rx, 5, script.itemId()))).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void twoLinesOnOneBillAgainstOnePrescriptionLineAreSummed() {
        Ctx c = ctx();
        String rx = item(c, "Rx Item D2", true, 50);
        Rx script = prescribe(c, patient(c, phone()), 10, true);
        assertProblem(otc(c, List.of(line(rx, 6, script.itemId()), line(rx, 6, script.itemId()))), "rx-quantity-exhausted");
    }

    @Test
    void draftPrescriptionIs422RxNotSigned() {
        Ctx c = ctx();
        String rx = item(c, "Rx Item E", true, 20);
        Rx draft = prescribe(c, patient(c, phone()), 10, false);
        assertProblem(otc(c, List.of(line(rx, 1, draft.itemId()))), "rx-not-signed");
        assertThat(stock(c, rx)).isEqualTo(20);
    }

    @Test
    void nonRxItemStillSellsOverTheCounterWithoutAPrescription() {
        Ctx c = ctx();
        String otcItem = item(c, "Non Rx Item", false, 20);
        long t0 = System.nanoTime();
        ResponseEntity<Map> r = otc(c, List.of(line(otcItem, 3, null)));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("patientId")).isNull();
        assertThat(stock(c, otcItem)).isEqualTo(17);
        assertThat(ms).isLessThan(30_000); // J10 budget, end to end through the API
    }

    @Test
    void anotherTenantsPrescriptionLineIsNotFoundAndNothingLeaks() {
        Ctx a = ctx();
        Rx scriptA = prescribe(a, patient(a, phone()), 10, true);
        Ctx b = ctx();
        String rxB = item(b, "Rx Item F", true, 20);

        ResponseEntity<Map> r = otc(b, List.of(line(rxB, 1, scriptA.itemId())));

        assertProblem(r, "rx-required");
        assertThat(r.getBody().toString()).doesNotContain(scriptA.patientId());
        assertThat(su().queryForObject("SELECT count(*) FROM invoice_line_items WHERE prescription_item_id = ?::uuid", Integer.class,
                scriptA.itemId())).isZero();
    }

    @Test
    void theCheckConstraintRejectsADirectUnlinkedRxInsert() {
        Ctx c = ctx();
        String code = item(c, "Rx Item G", true, 5);
        ResponseEntity<Map> sale = otc(c, List.of(line(item(c, "Non Rx G", false, 5), 1, null)));
        UUID invoiceId = UUID.fromString((String) sale.getBody().get("id"));
        assertThatThrownBy(() -> su().update("INSERT INTO invoice_line_items (tenant_id, invoice_id, charge_code, charge_name, category, " +
                        "quantity, unit_price, tax_rate_percent, line_total, is_rx) VALUES (?,?,?,?,?,1,10,0,10,true)",
                c.tenant().id(), invoiceId, code, "x", "Pharmacy"))
                .hasMessageContaining("invoice_line_items_rx_linked");
    }

    @Test
    void searchByPhoneListsSignedLinesWithQuantityLeft() {
        Ctx c = ctx();
        String ph = phone();
        String p = patient(c, ph);
        Rx signed = prescribe(c, p, 10, true);

        ResponseEntity<List> found = exchange("/v1/pharmacy/rx-lines/search", HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("phone", ph.substring(3))), List.class);
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody()).hasSize(1);
        Map<?, ?> hit = (Map<?, ?>) found.getBody().get(0);
        assertThat(hit.get("prescriptionItemId")).isEqualTo(signed.itemId());
        assertThat(((Number) hit.get("remainingQty")).intValue()).isEqualTo(10);

        assertThat(exchange("/v1/pharmacy/rx-lines/search", HttpMethod.POST, authedJsonBody(c.token(), Map.of("phone", "12")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void twoCheckoutsRacingOnTheLastFiveUnitsExactlyOneWins() throws Exception {
        Ctx c = ctx();
        String rx = item(c, "Rx Item H", true, 50);
        Rx script = prescribe(c, patient(c, phone()), 5, true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return otc(c, List.of(line(rx, 5, script.itemId())));
            }));
        }
        go.countDown();
        List<HttpStatus> codes = new ArrayList<>();
        for (var f : results) codes.add(HttpStatus.valueOf(f.get().getStatusCode().value()));
        pool.shutdown();
        assertThat(codes).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(stock(c, rx)).isEqualTo(45);
    }

    /** DB invariant over 100 random checkouts: every persisted is_rx line points at a signed prescription line. */
    @Test
    void hundredRandomCheckoutsNeverPersistAnUnlinkedRxLine() {
        Ctx c = ctx();
        String rx = item(c, "Rx Item R", true, 10_000);
        String plain = item(c, "Non Rx R", false, 10_000);
        String p = patient(c, phone());
        Rx signed = prescribe(c, p, 150, true);
        Rx draft = prescribe(c, patient(c, phone()), 150, false); // one open visit per patient, so the draft is another patient's
        Random rnd = new Random(42);
        int ok = 0;
        for (int i = 0; i < 100; i++) {
            List<Map<String, Object>> lines = new ArrayList<>();
            int kind = rnd.nextInt(4);
            int qty = 1 + rnd.nextInt(3);
            if (kind == 0) lines.add(line(rx, qty, signed.itemId()));
            if (kind == 1) lines.add(line(rx, qty, null));
            if (kind == 2) lines.add(line(rx, qty, draft.itemId()));
            if (rnd.nextBoolean() || lines.isEmpty()) lines.add(line(plain, 1, null));
            if (otc(c, lines).getStatusCode() == HttpStatus.OK) ok++;
        }
        assertThat(ok).isGreaterThan(0);
        Integer bad = su().queryForObject("SELECT count(*) FROM invoice_line_items li " +
                "LEFT JOIN prescription_items pi ON pi.id = li.prescription_item_id " +
                "LEFT JOIN prescriptions pr ON pr.id = pi.prescription_id " +
                "WHERE li.tenant_id = ? AND li.is_rx AND (li.prescription_item_id IS NULL OR pr.status IS DISTINCT FROM 'signed')",
                Integer.class, c.tenant().id());
        assertThat(bad).isZero();
        assertThat(su().queryForObject("SELECT count(*) FROM invoice_line_items WHERE tenant_id = ? AND is_rx", Integer.class, c.tenant().id()))
                .isPositive();
        assertThat(su().queryForObject("SELECT COALESCE(SUM(quantity), 0) FROM invoice_line_items WHERE tenant_id = ? AND is_rx",
                Integer.class, c.tenant().id())).isLessThanOrEqualTo(150);
    }
}

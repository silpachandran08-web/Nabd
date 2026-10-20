package com.nabd.hms.billing;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import com.nabd.hms.support.Hazard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
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

/** Independent QA (S11 PHL-001, hazard H-04): a prescription-only line is only billable against a signed prescription line
 * of the same tenant and patient with quantity left. Item names here are test fixtures, not a clinical list. */
@SuppressWarnings({"unchecked", "rawtypes"})
@ExtendWith(OutputCaptureExtension.class)
@Hazard("H-04")
class S11QaRxGateTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 500_000);

    private record Ctx(SeededTenant tenant, SeededStaff staff, String token) {
    }

    private record Rx(String patientId, String queueEntryId, String itemId) {
    }

    private static String phone() {
        return "+9196" + String.format("%08d", SEQ.incrementAndGet());
    }

    private Ctx ctx() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = ClinicianFixture.licensed(seedStaff(tenant, seedFullAccessRole(tenant.id()),
                "qa1rx" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        String token = loginAndGetAccessToken(staff);
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "QA setup")),
                Map.class).getStatusCode().is2xxSuccessful()).isTrue();
        return new Ctx(tenant, staff, token);
    }

    private String item(Ctx c, String name, boolean isRx, int stock) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", name, "isRx", isRx, "hsnCode", "3004", "price", 12.00, "taxRatePercent", 0, "stockQty", stock)), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("item %s", r.getBody()).isTrue();
        return (String) r.getBody().get("code");
    }

    private String patient(Ctx c, String name) {
        ResponseEntity<Map> r = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", name, "phone", phone(),
                "dob", LocalDate.of(1952, 3, 1).plusDays(SEQ.incrementAndGet() % 18_000).toString(), "gender", "female")), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("patient %s", r.getBody()).isTrue();
        return (String) r.getBody().get("id");
    }

    private String patient(Ctx c) {
        return patient(c, "Qa Rx " + Integer.toString(SEQ.incrementAndGet(), 36) + " Pt");
    }

    private String checkIn(Ctx c, String patientId) {
        ResponseEntity<Map> r = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patientId, "doctorId", c.staff().id().toString())), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("check-in %s", r.getBody()).isTrue();
        return (String) r.getBody().get("id");
    }

    private void toCheckout(Ctx c, String qe) {
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + qe + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", s)), Map.class);
        }
    }

    private Rx prescribe(Ctx c, String patientId, int qty, boolean sign) {
        String qe = checkIn(c, patientId);
        ResponseEntity<Map> saved = exchange("/v1/clinical/prescriptions/" + qe, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of(
                "items", List.of(Map.of("drugName", "Qa fixture drug", "dosage", "1 tab", "frequency", "BD", "quantity", qty,
                        "quantityUnit", "tablets")))), Map.class);
        assertThat(saved.getStatusCode()).as("rx %s", saved.getBody()).isEqualTo(HttpStatus.OK);
        String itemId = (String) ((Map<?, ?>) ((List<?>) saved.getBody().get("items")).get(0)).get("id");
        if (sign) {
            ResponseEntity<Map> s = exchange("/v1/clinical/prescriptions/" + qe + "/sign", HttpMethod.POST, authed(c.token()), Map.class);
            assertThat(s.getStatusCode()).as("sign %s", s.getBody()).isEqualTo(HttpStatus.OK);
        }
        return new Rx(patientId, qe, itemId);
    }

    private static Map<String, Object> line(String code, int qty, String rxItemId) {
        Map<String, Object> m = new HashMap<>(Map.of("chargeCode", code, "quantity", qty));
        if (rxItemId != null) m.put("prescriptionItemId", rxItemId);
        return m;
    }

    private ResponseEntity<Map> otc(Ctx c, List<Map<String, Object>> lines) {
        return exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", lines)), Map.class);
    }

    private ResponseEntity<Map> visitCheckout(Ctx c, String qe, List<Map<String, Object>> lines) {
        return exchange("/v1/billing/checkout/" + qe, HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", lines)), Map.class);
    }

    private int stock(Ctx c, String code) {
        for (Object o : exchange("/v1/pharmacy/items", HttpMethod.GET, authed(c.token()), List.class).getBody()) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (code.equals(m.get("code"))) return ((Number) m.get("stockQty")).intValue();
        }
        throw new AssertionError("item not listed: " + code);
    }

    private static JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static int count(String sql, Object... args) {
        return su().queryForObject(sql, Integer.class, args);
    }

    private static void assert422(ResponseEntity<Map> r, String type) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(String.valueOf(r.getBody().get("type"))).as("%s", r.getBody()).endsWith("/" + type);
        assertThat(r.getHeaders().getContentType().toString()).contains("problem+json");
    }

    // ── AC1 + AC7 ────────────────────────────────────────────────────────

    @Test
    void otcRxLineWithoutLinkIs422RxRequiredNoInvoiceStockUnchangedAuditedAndLogsCarryNoPhi(CapturedOutput output) {
        Ctx c = ctx();
        String drugName = "Qadrugname" + Integer.toString(SEQ.incrementAndGet(), 36);
        String rx = item(c, drugName, true, 20);
        String plain = item(c, "Qa plain item", false, 20);
        int invoicesBefore = count("SELECT count(*) FROM invoices WHERE tenant_id = ?", c.tenant().id());
        int logStart = output.length();

        ResponseEntity<Map> r = otc(c, List.of(line(plain, 1, null), line(rx, 2, null)));

        assert422(r, "rx-required");
        assertThat(count("SELECT count(*) FROM invoices WHERE tenant_id = ?", c.tenant().id())).isEqualTo(invoicesBefore);
        assertThat(count("SELECT count(*) FROM invoice_line_items WHERE tenant_id = ?", c.tenant().id())).isZero();
        assertThat(stock(c, rx)).isEqualTo(20);
        assertThat(stock(c, plain)).isEqualTo(20); // the whole sale is refused, not only the Rx line
        Map<String, Object> audit = su().queryForMap("SELECT actor_id, after::text AS after FROM audit_log WHERE tenant_id = ? " +
                "AND action = 'pharmacy.rx_gate_blocked'", c.tenant().id());
        assertThat(audit.get("actor_id")).isEqualTo(c.staff().id());
        assertThat((String) audit.get("after")).contains(rx);
        String logs = output.toString().substring(logStart);
        assertThat(logs).contains(rx); // the block is logged by item code ...
        assertThat(logs).doesNotContain(drugName); // ... never by the drug's free-text name
    }

    @Test
    void visitCheckoutUnlinkedRxLineIs422RxRequired() {
        Ctx c = ctx();
        String rx = item(c, "Qa visit rx", true, 20);
        Rx script = prescribe(c, patient(c), 10, true);
        toCheckout(c, script.queueEntryId());
        assert422(visitCheckout(c, script.queueEntryId(), List.of(line(rx, 1, null))), "rx-required");
        assertThat(stock(c, rx)).isEqualTo(20);
        ResponseEntity<Map> ok = visitCheckout(c, script.queueEntryId(), List.of(line(rx, 1, script.itemId())));
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(stock(c, rx)).isEqualTo(19);
    }

    // ── (a) free-text lines ──────────────────────────────────────────────

    /** A free-text line that reuses the Rx item's own code is resolved to the catalogue item and still gated. */
    @Test
    void freeTextLineReusingTheRxItemCodeIsStillGated() {
        Ctx c = ctx();
        String rx = item(c, "Qa rx same code", true, 20);
        Map<String, Object> l = line(rx, 1, null);
        l.putAll(Map.of("chargeName", "Something harmless", "category", "Service", "unitPrice", 1, "taxRatePercent", 0,
                "override", Map.of("unitPrice", 1, "reasonCode", "other", "note", "qa")));
        assert422(otc(c, List.of(l)), "rx-required");
        assertThat(stock(c, rx)).isEqualTo(20);
    }

    /** Engineer-reported bypass: the Rx-only catalogue item typed in as a free-text line (new code, same name) is sold
     * over the counter with no prescription. The story asks that the counter refuse ANY prescription-only line. */
    @Test
    void rxOnlyItemTypedAsAFreeTextLineIsRefused() {
        Ctx c = ctx();
        String name = "Qa rx freetext " + Integer.toString(SEQ.incrementAndGet(), 36);
        item(c, name, true, 20);
        Map<String, Object> l = line("FT-" + SEQ.incrementAndGet(), 1, null);
        l.putAll(Map.of("chargeName", name, "category", "Pharmacy", "unitPrice", 12, "taxRatePercent", 0,
                "override", Map.of("unitPrice", 12, "reasonCode", "other", "note", "typed in")));
        ResponseEntity<Map> r = otc(c, List.of(l));
        assertThat(r.getStatusCode()).as("free-text sale of an Rx-only item: %s", r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    // ── AC2 / AC3 / AC4 ──────────────────────────────────────────────────

    @Test
    void visitCheckoutForPatientBLinkingPatientAsRxIs422PatientMismatch() {
        Ctx c = ctx();
        String rx = item(c, "Qa mismatch rx", true, 20);
        Rx forA = prescribe(c, patient(c), 10, true);
        String qeB = checkIn(c, patient(c));
        toCheckout(c, qeB);
        ResponseEntity<Map> r = visitCheckout(c, qeB, List.of(line(rx, 1, forA.itemId())));
        assert422(r, "rx-patient-mismatch");
        assertThat(r.getBody().toString()).doesNotContain(forA.patientId());
        assertThat(stock(c, rx)).isEqualTo(20);
        assertThat(count("SELECT count(*) FROM invoice_line_items WHERE tenant_id = ?", c.tenant().id())).isZero();
    }

    @Test
    void tenOfTenDispensedThenFiveMoreIs422QuantityExhausted() {
        Ctx c = ctx();
        String rx = item(c, "Qa qty rx", true, 100);
        Rx script = prescribe(c, patient(c), 10, true);
        // dispense 7 + 3 over two sales: exactly the prescribed 10 is allowed (boundary)
        assertThat(otc(c, List.of(line(rx, 7, script.itemId()))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otc(c, List.of(line(rx, 3, script.itemId()))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assert422(otc(c, List.of(line(rx, 5, script.itemId()))), "rx-quantity-exhausted");
        assert422(otc(c, List.of(line(rx, 1, script.itemId()))), "rx-quantity-exhausted"); // boundary: 11th unit
        assertThat(stock(c, rx)).isEqualTo(90);
    }

    @Test
    void draftPrescriptionLinkIs422RxNotSigned() {
        Ctx c = ctx();
        String rx = item(c, "Qa draft rx", true, 20);
        Rx draft = prescribe(c, patient(c), 10, false);
        assert422(otc(c, List.of(line(rx, 1, draft.itemId()))), "rx-not-signed");
        toCheckout(c, draft.queueEntryId());
        assert422(visitCheckout(c, draft.queueEntryId(), List.of(line(rx, 1, draft.itemId()))), "rx-not-signed");
        assertThat(stock(c, rx)).isEqualTo(20);
    }

    // ── AC5 ──────────────────────────────────────────────────────────────

    @Test
    void nonRxItemSellsOtcWithoutPrescriptionAsBefore() {
        Ctx c = ctx();
        String plain = item(c, "Qa cosmeceutical", false, 20);
        long t0 = System.nanoTime();
        ResponseEntity<Map> r = otc(c, List.of(line(plain, 2, null)));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(stock(c, plain)).isEqualTo(18);
        assertThat(ms).isLessThan(30_000);
        assertThat(su().queryForObject("SELECT is_rx FROM invoice_line_items WHERE tenant_id = ? AND charge_code = ?", Boolean.class,
                c.tenant().id(), plain)).isFalse();
    }

    // ── AC6 + DB invariant ───────────────────────────────────────────────

    @Test
    void directInsertOfUnlinkedRxLineIsRejectedByTheDatabase() {
        Ctx c = ctx();
        String rx = item(c, "Qa db rx", true, 5);
        UUID invoiceId = UUID.fromString((String) otc(c, List.of(line(item(c, "Qa db plain", false, 5), 1, null))).getBody().get("id"));
        assertThatThrownBy(() -> inTenantTx(c.tenant().id(), () -> jdbc.update("INSERT INTO invoice_line_items (tenant_id, invoice_id, " +
                        "charge_code, charge_name, category, quantity, unit_price, tax_rate_percent, line_total, is_rx) " +
                        "VALUES (?,?,?,?,?,1,10,0,10,true)", c.tenant().id(), invoiceId, rx, "x", "Pharmacy")))
                .hasStackTraceContaining("invoice_line_items_rx_linked");
        assertThat(count("SELECT count(*) FROM pg_constraint WHERE conname = 'invoice_line_items_rx_linked' AND convalidated")).isEqualTo(1);
    }

    @Test
    void hundredRandomOtcAndVisitCheckoutsNeverPersistAnUnlinkedOrUnsignedRxLine() {
        Ctx c = ctx();
        String rx = item(c, "Qa random rx", true, 10_000);
        String plain = item(c, "Qa random plain", false, 10_000);
        String pa = patient(c);
        Rx signedA = prescribe(c, pa, 120, true);
        Rx draftB = prescribe(c, patient(c), 120, false);
        Rx signedC = prescribe(c, patient(c), 120, true);
        Random rnd = new Random(20261019);
        int ok = 0;
        for (int i = 0; i < 100; i++) {
            List<Map<String, Object>> lines = new ArrayList<>();
            int qty = 1 + rnd.nextInt(3);
            switch (rnd.nextInt(5)) {
                case 0 -> lines.add(line(rx, qty, signedA.itemId()));
                case 1 -> lines.add(line(rx, qty, null));
                case 2 -> lines.add(line(rx, qty, draftB.itemId()));
                case 3 -> { lines.add(line(rx, 1, signedA.itemId())); lines.add(line(rx, 1, signedC.itemId())); } // two patients
                default -> lines.add(line(rx, qty, UUID.randomUUID().toString())); // unknown id
            }
            if (rnd.nextBoolean()) lines.add(line(plain, 1, null));
            if (otc(c, lines).getStatusCode() == HttpStatus.OK) ok++;
        }
        assertThat(ok).isPositive();
        assertThat(count("SELECT count(*) FROM invoice_line_items li LEFT JOIN prescription_items pi ON pi.id = li.prescription_item_id " +
                "LEFT JOIN prescriptions p ON p.id = pi.prescription_id WHERE li.tenant_id = ? AND li.is_rx " +
                "AND (li.prescription_item_id IS NULL OR p.status IS DISTINCT FROM 'signed' OR p.tenant_id <> li.tenant_id)", c.tenant().id()))
                .isZero();
        assertThat(count("SELECT count(*) FROM invoice_line_items WHERE tenant_id = ? AND is_rx", c.tenant().id())).isPositive();
        // every Rx sale is booked to the prescription's patient
        assertThat(count("SELECT count(*) FROM invoices i JOIN invoice_line_items li ON li.invoice_id = i.id WHERE i.tenant_id = ? " +
                "AND li.is_rx AND i.patient_id IS DISTINCT FROM ?::uuid", c.tenant().id(), pa)).isZero();
    }

    // ── tenant isolation, concurrency, permission ────────────────────────

    @Test
    void anotherTenantsSignedPrescriptionLineCannotBeUsed() {
        Ctx a = ctx();
        Rx scriptA = prescribe(a, patient(a), 10, true);
        Ctx b = ctx();
        String rxB = item(b, "Qa tenant rx", true, 20);
        ResponseEntity<Map> r = otc(b, List.of(line(rxB, 1, scriptA.itemId())));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString()).doesNotContain(scriptA.patientId()).doesNotContain("Qa fixture drug");
        assertThat(stock(b, rxB)).isEqualTo(20);
        assertThat(count("SELECT count(*) FROM invoice_line_items WHERE prescription_item_id = ?::uuid", scriptA.itemId())).isZero();
    }

    @Test
    void twoCountersRacingForTheLastUnitsOnOneLineExactlyOneSucceeds() throws Exception {
        Ctx c = ctx();
        String rx = item(c, "Qa race rx", true, 50);
        Rx script = prescribe(c, patient(c), 4, true);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> fs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return otc(c, List.of(line(rx, 3, script.itemId())));
            }));
        }
        go.countDown();
        int ok = 0, refused = 0;
        for (var f : fs) {
            HttpStatus s = HttpStatus.valueOf(f.get().getStatusCode().value());
            if (s == HttpStatus.OK) ok++;
            if (s == HttpStatus.UNPROCESSABLE_CONTENT) refused++;
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(refused).isEqualTo(2);
        assertThat(stock(c, rx)).isEqualTo(47);
    }

    @Test
    void otcCheckoutNeedsBillingCreate() {
        Ctx c = ctx();
        String plain = item(c, "Qa perm plain", false, 5);
        UUID viewOnly = seedRole(c.tenant().id(), "QaBillView" + SEQ.incrementAndGet(), false,
                new ModuleGrant("billing", true, false, false, false, false, false, false));
        String tok = loginAndGetAccessToken(seedStaff(c.tenant(), viewOnly, "qa1bv" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        ResponseEntity<Map> r = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(tok, Map.of("lineItems",
                List.of(line(plain, 1, null)))), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(stock(c, plain)).isEqualTo(5);
    }
}

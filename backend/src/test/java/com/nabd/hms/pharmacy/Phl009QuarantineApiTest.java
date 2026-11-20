package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.pharmacy.api.StockCommands;
import com.nabd.hms.pharmacy.api.StockCommands.BatchReceipt;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PHL-009: the nightly quarantine of expired batches, manual quarantine, the supplier return note (gapless debit-note number,
 * 'return_out' movements), write-offs with the witness rule for controlled classes, and the valuation split.
 * The job's clock is frozen per step (MutableClock); stock and API calls run on the real clock.
 */
@Import(Phl008StockAlertsApiTest.FrozenClock.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl009QuarantineApiTest extends ApiTestBase {

    private static final AtomicInteger PHONE = new AtomicInteger(7300000);
    private static final String IST = "Asia/Kolkata";

    @Autowired MutableClock clock;
    @Autowired StockCommands stock;
    @Autowired QuarantineJob job;

    @AfterEach
    void realTime() {
        clock.reset();
    }

    private record Clinic(SeededTenant tenant, String token, UUID staffId) {
        UUID t() {
            return tenant.id();
        }
    }

    private static String phone() {
        return "+9194" + String.format("%08d", PHONE.incrementAndGet());
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    /** An In-house clinic on India time; its full-access staff member (the maker) holds pharmacy:adjust_approve. */
    private Clinic clinic() {
        SeededTenant tenant = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", IST, tenant.id());
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), UUID.randomUUID().toString().substring(0, 8) + "@p9.test", phone(), false);
        String token = loginAndGetAccessToken(staff);
        assertThat(call(token, HttpMethod.PATCH, "/v1/pharmacy/settings", Map.of("mode", "in_house", "reason", "PHL-009 test")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        su().update("UPDATE staff SET name = 'Maker Pharmacist' WHERE id = ?", staff.id());
        return new Clinic(tenant, token, staff.id());
    }

    private SeededStaff colleague(Clinic c, String name, boolean edit, boolean approve) {
        UUID role = seedRole(c.t(), name + " role " + UUID.randomUUID().toString().substring(0, 6), false,
                new ModuleGrant("pharmacy", true, edit, edit, false, approve, false, false));
        SeededStaff s = seedStaff(c.tenant(), role, UUID.randomUUID().toString().substring(0, 8) + "@p9.test", phone(), false);
        su().update("UPDATE staff SET name = ? WHERE id = ?", name, s.id());
        return s;
    }

    private ResponseEntity<Map> call(String token, HttpMethod m, String path, Object body) {
        return exchange(path, m, authedJsonBody(token, body), Map.class);
    }

    private Map get(Clinic c, String path) {
        ResponseEntity<Map> r = exchange(path, HttpMethod.GET, authed(c.token()), Map.class);
        assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private UUID item(Clinic c, String name, String scheduleClass) {
        ResponseEntity<Map> r = call(c.token(), HttpMethod.POST, "/v1/pharmacy/items", Map.of(
                "name", name, "isRx", !"OTC".equals(scheduleClass), "price", 100.00, "taxRatePercent", 12.00, "scheduleClass", scheduleClass));
        assertThat(r.getStatusCode()).as("item %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private UUID receive(Clinic c, UUID item, String no, LocalDate expiry, int qty, String rate, UUID supplierId) {
        return inTenantTx(c.t(), () -> stock.receiveBatch(item, new BatchReceipt(no, expiry, new BigDecimal("20.00"), new BigDecimal(rate), qty,
                "A1", supplierId, null), c.staffId(), "GRN " + no).batchId());
    }

    private UUID receive(Clinic c, UUID item, String no, int qty, String rate) {
        return receive(c, item, no, LocalDate.now().plusDays(400), qty, rate, null);
    }

    private void quarantine(Clinic c, UUID batch) {
        ResponseEntity<Map> r = call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + batch + "/quarantine", Map.of("reason", "damaged"));
        assertThat(r.getStatusCode()).as("quarantine %s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    private static Map<String, Object> line(UUID batch, int qty) {
        return Map.of("batchId", batch.toString(), "qty", qty);
    }

    private static String slug(ResponseEntity<Map> r) {
        String type = String.valueOf(r.getBody().get("type"));
        return type.substring(type.lastIndexOf('/') + 1);
    }

    private static BigDecimal num(Object o) {
        return new BigDecimal(String.valueOf(o));
    }

    private String status(UUID batch) {
        return su().queryForObject("SELECT status FROM stock_batches WHERE id = ?", String.class, batch);
    }

    private int batchQty(UUID batch) {
        return su().queryForObject("SELECT qty_on_hand FROM stock_batches WHERE id = ?", Integer.class, batch);
    }

    private int cache(UUID item) {
        return su().queryForObject("SELECT stock_qty FROM charge_catalogue WHERE id = ?", Integer.class, item);
    }

    private List<String> quarantinedEvents(Clinic c) {
        return inTenantTx(c.t(), () -> jdbc.queryForList("SELECT payload::text FROM outbox_events WHERE tenant_id = ? " +
                "AND event_type = 'pharmacy.batch.quarantined.v1' ORDER BY id", String.class, c.t()));
    }

    /** The job's pass for one clinic at 00:30 clinic time on {@code day}. */
    private int runOn(Clinic c, LocalDate day) {
        clock.setLocal(day + "T00:30:00", IST);
        try {
            return job.runFor(c.t());
        } finally {
            clock.reset();
        }
    }

    // ---------------- AC1: the nightly job quarantines a batch past its expiry date; it is not pickable ----------------

    @Test
    void theNightlyJobQuarantinesABatchOnceItsExpiryDateHasPassedAndItIsNeverPicked() {
        Clinic c = clinic();
        Clinic other = clinic();
        LocalDate today = LocalDate.now(ZoneId.of(IST));
        UUID it = item(c, "Expiring syrup", "OTC");
        UUID soon = receive(c, it, "EXP-1", today.plusDays(1), 10, "4.0000", null);
        UUID fresh = receive(c, it, "FRESH-1", today.plusDays(400), 10, "4.0000", null);
        UUID otherBatch = receive(other, item(other, "Other clinic syrup", "OTC"), "EXP-9", today.plusDays(1), 3, "1.0000", null);

        assertThat(runOn(c, today)).as("not expired yet").isZero();
        assertThat(runOn(c, today.plusDays(1))).as("sellable through its expiry date (the FEFO rule)").isZero();
        assertThat(status(soon)).isEqualTo("active");

        assertThat(runOn(c, today.plusDays(2))).as("the night its expiry date ended").isEqualTo(1);
        assertThat(status(soon)).isEqualTo("quarantined");
        assertThat(status(fresh)).isEqualTo("active");
        assertThat(status(otherBatch)).as("another clinic's batch is untouched by this clinic's pass").isEqualTo("active");
        assertThat(runOn(c, today.plusDays(2))).as("a second pass finds nothing").isZero();

        assertThat(quarantinedEvents(c)).singleElement().satisfies(p -> assertThat(p).contains(soon.toString()).contains("\"reason\": \"expired\"")
                .doesNotContain("Expiring syrup"));
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.batch_quarantined' " +
                "AND entity_id = ? AND actor_type = 'system'", Integer.class, c.t(), soon)).isEqualTo(1);

        // not pickable (real clock: by date alone the batch would still be sellable today, so this is the status at work)
        assertThat(inTenantTx(c.t(), () -> stock.pick(it, 10, null))).allSatisfy(p -> assertThat(p.batchId()).isEqualTo(fresh));
        assertThatThrownBy(() -> inTenantTx(c.t(), () -> stock.pick(it, 11, null))).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).typeSlug()).isEqualTo("insufficient-stock"));
        assertThatThrownBy(() -> inTenantTx(c.t(), () -> stock.issueFromBatch(it, soon, 1, null, c.staffId(), "customer asked")))
                .isInstanceOf(ApiException.class).satisfies(e -> assertThat(((ApiException) e).typeSlug()).isEqualTo("batch-not-usable"));

        List<Map> bin = (List<Map>) get(c, "/v1/pharmacy/quarantine").get("items");
        assertThat(bin).singleElement().satisfies(b -> assertThat(b).containsEntry("batchId", soon.toString()).containsEntry("batchNo", "EXP-1")
                .containsEntry("qtyOnHand", 10).containsEntry("controlled", false));
        assertThat((List<Map>) get(other, "/v1/pharmacy/quarantine").get("items")).isEmpty();
    }

    // ---------------- manual quarantine: permission, reasons, 409 the second time, tenant isolation ----------------

    @Test
    void aBatchIsQuarantinedByHandOnceWithAReasonAndOnlyByItsOwnClinic() {
        Clinic c = clinic();
        Clinic other = clinic();
        UUID b = receive(c, item(c, "Dropped vial", "OTC"), "DMG-1", 6, "9.5000");
        String path = "/v1/pharmacy/batches/" + b + "/quarantine";

        SeededStaff viewer = colleague(c, "Counter Assistant", false, false);
        assertThat(call(loginAndGetAccessToken(viewer), HttpMethod.POST, path, Map.of("reason", "damaged")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(other.token(), HttpMethod.POST, path, Map.of("reason", "damaged")).getStatusCode()).as("another clinic").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(c.token(), HttpMethod.POST, path, Map.of("reason", "stolen")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(c.token(), HttpMethod.POST, path, Map.of("reason", "other")).getStatusCode()).as("'other' needs a note").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(status(b)).isEqualTo("active");

        ResponseEntity<Map> ok = call(c.token(), HttpMethod.POST, path, Map.of("reason", "damaged", "note", "Carton crushed in the store room"));
        assertThat(ok.getStatusCode()).as("quarantine %s", ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("status", "quarantined").containsEntry("qtyOnHand", 6);
        assertThat(num(ok.getBody().get("value"))).isEqualByComparingTo("57.00");

        ResponseEntity<Map> again = call(c.token(), HttpMethod.POST, path, Map.of("reason", "damaged"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(slug(again)).isEqualTo("already-quarantined");
        assertThat(call(c.token(), HttpMethod.POST, "/v1/pharmacy/batches/" + UUID.randomUUID() + "/quarantine", Map.of("reason", "damaged")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(quarantinedEvents(c)).singleElement().satisfies(p -> assertThat(p).contains(b.toString()).contains("\"reason\": \"damaged\"")
                .doesNotContain("Carton"));
        assertThat(su().queryForObject("SELECT after->>'note' FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.batch_quarantined' AND entity_id = ?",
                String.class, c.t(), b)).isEqualTo("Carton crushed in the store room");
    }

    // ---------------- AC2: one return note per supplier: batch, qty, purchase rate, value; stock out as return_out ----------------

    @Test
    void aSupplierReturnListsBatchQtyRateAndValueAndMovesTheStockOutAsReturnOut() {
        Clinic c = clinic();
        UUID supplier = UUID.randomUUID();
        UUID it = item(c, "Returnable tablets", "OTC");
        UUID b1 = receive(c, it, "RT-1", LocalDate.now().plusDays(400), 10, "7.5000", supplier);
        UUID b2 = receive(c, it, "RT-2", LocalDate.now().plusDays(500), 20, "2.2500", null);
        UUID onShelf = receive(c, it, "RT-3", 5, "1.0000");
        quarantine(c, b1);
        quarantine(c, b2);

        // refused requests change nothing and consume no debit-note number
        ResponseEntity<Map> notQuarantined = call(c.token(), HttpMethod.POST, "/v1/pharmacy/supplier-returns",
                Map.of("supplierId", supplier.toString(), "supplierName", "Medi Supplies", "lines", List.of(line(b1, 4), line(onShelf, 1))));
        assertThat(notQuarantined.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(slug(notQuarantined)).isEqualTo("batch-not-quarantined");
        assertThat(slug(call(c.token(), HttpMethod.POST, "/v1/pharmacy/supplier-returns",
                Map.of("supplierId", UUID.randomUUID().toString(), "supplierName", "Someone Else", "lines", List.of(line(b1, 4))))))
                .as("b1 was received from another supplier").isEqualTo("batch-not-from-supplier");
        assertThat(slug(call(c.token(), HttpMethod.POST, "/v1/pharmacy/supplier-returns",
                Map.of("supplierId", supplier.toString(), "supplierName", "Medi Supplies", "lines", List.of(line(b1, 11))))))
                .isEqualTo("insufficient-stock");
        assertThat(batchQty(b1)).isEqualTo(10);
        assertThat(call(loginAndGetAccessToken(colleague(c, "Counter Assistant", false, false)), HttpMethod.POST, "/v1/pharmacy/supplier-returns",
                Map.of("supplierName", "Medi Supplies", "lines", List.of(line(b1, 1)))).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> r = call(c.token(), HttpMethod.POST, "/v1/pharmacy/supplier-returns", Map.of("supplierId", supplier.toString(),
                "supplierName", " Medi Supplies ", "reason", "Expired stock, agreed with the rep", "lines", List.of(line(b1, 4), line(b2, 20))));
        assertThat(r.getStatusCode()).as("return %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        Map note = r.getBody();
        assertThat((String) note.get("debitNoteNumber")).matches("DN-.+-000001");
        assertThat(note).containsEntry("supplierName", "Medi Supplies").containsEntry("status", "posted").containsEntry("createdByName", "Maker Pharmacist");
        assertThat(num(note.get("value"))).isEqualByComparingTo("75.00"); // 4 x 7.50 + 20 x 2.25
        List<Map> lines = (List<Map>) note.get("lines");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).containsEntry("batchId", b1.toString()).containsEntry("batchNo", "RT-1").containsEntry("qty", 4)
                .containsEntry("itemName", "Returnable tablets");
        assertThat(num(lines.get(0).get("rate"))).isEqualByComparingTo("7.5");
        assertThat(num(lines.get(0).get("value"))).isEqualByComparingTo("30.00");
        assertThat(lines.get(1)).containsEntry("batchNo", "RT-2").containsEntry("qty", 20);
        assertThat(num(lines.get(1).get("value"))).isEqualByComparingTo("45.00");

        UUID returnId = UUID.fromString((String) note.get("returnId"));
        List<Map<String, Object>> moves = su().queryForList("SELECT batch_id, kind, delta, qty_after, actor_id FROM pharmacy_stock_movements " +
                "WHERE source_type = 'supplier_return' AND source_id = ? ORDER BY delta DESC", returnId);
        assertThat(moves).hasSize(2).allSatisfy(m -> assertThat(m).containsEntry("kind", "return_out").containsEntry("actor_id", c.staffId()));
        assertThat(moves.get(0)).containsEntry("batch_id", b1).containsEntry("delta", -4).containsEntry("qty_after", 6);
        assertThat(moves.get(1)).containsEntry("batch_id", b2).containsEntry("delta", -20).containsEntry("qty_after", 0);
        assertThat(batchQty(b1)).isEqualTo(6);
        assertThat(batchQty(b2)).isZero();
        assertThat(cache(it)).as("item on-hand follows its batches").isEqualTo(11);
        assertThat(inTenantTx(c.t(), () -> jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = " +
                "'pharmacy.stock.batch_changed.v1' AND payload->>'kind' = 'return_out'", Integer.class, c.t()))).isEqualTo(2);

        // the note can be read again; an emptied batch has left the bin; the next note takes the next number (no gap from the refusals)
        assertThat(get(c, "/v1/pharmacy/supplier-returns/" + returnId)).containsEntry("debitNoteNumber", note.get("debitNoteNumber"));
        assertThat((List<Map>) get(c, "/v1/pharmacy/quarantine").get("items")).extracting(m -> m.get("batchId")).containsExactly(b1.toString());
        ResponseEntity<Map> second = call(c.token(), HttpMethod.POST, "/v1/pharmacy/supplier-returns",
                Map.of("supplierId", supplier.toString(), "supplierName", "Medi Supplies", "lines", List.of(line(b1, 6))));
        assertThat((String) second.getBody().get("debitNoteNumber")).matches("DN-.+-000002");

        Clinic other = clinic();
        assertThat(exchange("/v1/pharmacy/supplier-returns/" + returnId, HttpMethod.GET, authed(other.token()), Map.class).getStatusCode())
                .as("another clinic cannot read the note").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(other.token(), HttpMethod.POST, "/v1/pharmacy/supplier-returns",
                Map.of("supplierName", "Medi Supplies", "lines", List.of(line(b1, 1)))).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------- AC3: a controlled-class write-off needs a second staff member and leaves a register entry ----------------

    @Test
    void aControlledWriteOffNeedsAWitnessWithTheirOwnPinAndWritesARegisterEntry() {
        Clinic c = clinic();
        UUID ndps = item(c, "Controlled ampoule", "IN_NDPS");
        UUID bn = receive(c, ndps, "N-1", 8, "50.0000");
        UUID otc = item(c, "Plain lozenge", "OTC");
        UUID bo = receive(c, otc, "O-1", 12, "1.5000");
        UUID active = receive(c, otc, "O-2", 5, "1.5000");
        quarantine(c, bn);
        quarantine(c, bo);
        SeededStaff witness = colleague(c, "Second Pharmacist", true, true);
        SeededStaff noRight = colleague(c, "Junior Pharmacist", true, false);
        Map<String, Object> body = Map.of("method", "destroyed", "note", "Incinerated", "lines", List.of(line(bn, 8), line(bo, 2)));

        assertThat(call(loginAndGetAccessToken(noRight), HttpMethod.POST, "/v1/pharmacy/write-offs", body).getStatusCode())
                .as("writing off needs pharmacy:adjust_approve").isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> none = call(c.token(), HttpMethod.POST, "/v1/pharmacy/write-offs", body);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(slug(none)).isEqualTo("witness-required");
        assertThat(slug(writeOff(c, body, c.staffId(), STAFF_PIN))).as("the maker cannot witness").isEqualTo("witness-required");
        assertThat(slug(writeOff(c, body, noRight.id(), STAFF_PIN))).as("no adjust_approve").isEqualTo("witness-required");
        assertThat(slug(writeOff(c, body, witness.id(), "9999"))).isEqualTo("witness-pin-invalid");
        assertThat(su().queryForObject("SELECT count(*) FROM login_attempts WHERE staff_id = ? AND factor = 'step_up' AND NOT succeeded",
                Integer.class, witness.id())).as("the failed PIN is counted although the write-off rolled back").isEqualTo(1);
        assertThat(batchQty(bn)).isEqualTo(8);
        assertThat(su().queryForObject("SELECT count(*) FROM stock_write_offs WHERE tenant_id = ?", Integer.class, c.t())).isZero();
        assertThat(slug(call(c.token(), HttpMethod.POST, "/v1/pharmacy/write-offs", Map.of("method", "damaged", "lines", List.of(line(active, 1))))))
                .as("only quarantined stock is written off").isEqualTo("batch-not-quarantined");

        ResponseEntity<Map> ok = writeOff(c, body, witness.id(), STAFF_PIN);
        assertThat(ok.getStatusCode()).as("write-off %s", ok.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(ok.getBody()).containsEntry("method", "destroyed").containsEntry("approvedByName", "Maker Pharmacist")
                .containsEntry("witnessName", "Second Pharmacist");
        assertThat(num(ok.getBody().get("value"))).isEqualByComparingTo("403.00"); // 8 x 50 + 2 x 1.50
        UUID id = UUID.fromString((String) ok.getBody().get("writeOffId"));

        assertThat(su().queryForMap("SELECT method, witness_staff_id, approved_by FROM stock_write_offs WHERE id = ?", id))
                .containsEntry("method", "destroyed").containsEntry("witness_staff_id", witness.id()).containsEntry("approved_by", c.staffId());
        // the register entry: the controlled line names its statutory register, and the immutable movement carries maker and witness
        assertThat(su().queryForObject("SELECT register_type FROM stock_write_off_lines WHERE write_off_id = ? AND batch_id = ?", String.class, id, bn))
                .isNotNull();
        assertThat(su().queryForObject("SELECT register_type FROM stock_write_off_lines WHERE write_off_id = ? AND batch_id = ?", String.class, id, bo))
                .isNull();
        Map<String, Object> mv = su().queryForMap("SELECT kind, delta, actor_id, approved_by, reason FROM pharmacy_stock_movements " +
                "WHERE source_type = 'stock_write_off' AND source_id = ? AND batch_id = ?", id, bn);
        assertThat(mv).containsEntry("kind", "write_off").containsEntry("delta", -8).containsEntry("actor_id", c.staffId())
                .containsEntry("approved_by", witness.id()).containsEntry("reason", "Write-off: destroyed: Incinerated");
        assertThatThrownBy(() -> su().update("UPDATE pharmacy_stock_movements SET delta = 0 WHERE source_id = ?", id)).as("the entry is immutable")
                .isInstanceOf(Exception.class);
        assertThat(batchQty(bn)).isZero();
        assertThat(batchQty(bo)).isEqualTo(10);
        assertThat(cache(ndps)).isZero();
        assertThat(su().queryForObject("SELECT after->>'witnessName' FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.stock_written_off' " +
                "AND entity_id = ?", String.class, c.t(), id)).isEqualTo("Second Pharmacist");

        // a class without a register needs no witness
        ResponseEntity<Map> plain = call(c.token(), HttpMethod.POST, "/v1/pharmacy/write-offs", Map.of("method", "damaged", "lines", List.of(line(bo, 10))));
        assertThat(plain.getStatusCode()).as("plain write-off %s", plain.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(plain.getBody().get("witnessName")).isNull();
        assertThat((List<Map>) get(c, "/v1/pharmacy/quarantine").get("items")).isEmpty();
    }

    private ResponseEntity<Map> writeOff(Clinic c, Map<String, Object> body, UUID witnessId, String pin) {
        Map<String, Object> b = new java.util.HashMap<>(body);
        b.put("witnessStaffId", witnessId.toString());
        b.put("witnessPin", pin);
        return call(c.token(), HttpMethod.POST, "/v1/pharmacy/write-offs", b);
    }

    // ---------------- AC4: the valuation shows quarantined value apart from saleable value ----------------

    @Test
    void theValuationShowsQuarantinedValueSeparatelyFromSaleableValue() {
        Clinic c = clinic();
        UUID it = item(c, "Valued capsules", "OTC");
        receive(c, it, "V-1", 10, "2.0000");
        UUID q = receive(c, it, "V-2", 5, "3.0000");

        Map before = get(c, "/v1/pharmacy/reports/valuation");
        assertThat(num(before.get("saleableValue"))).isEqualByComparingTo("35.00");
        assertThat(num(before.get("quarantinedValue"))).isEqualByComparingTo("0");

        quarantine(c, q);
        Map v = get(c, "/v1/pharmacy/reports/valuation");
        assertThat(num(v.get("saleableValue"))).isEqualByComparingTo("20.00");
        assertThat(num(v.get("quarantinedValue"))).isEqualByComparingTo("15.00");
        assertThat(num(v.get("totalValue"))).isEqualByComparingTo("35.00");
        assertThat(v).containsEntry("saleableBatches", 1).containsEntry("quarantinedBatches", 1);
        assertThat(num(get(c, "/v1/pharmacy/quarantine").get("totalValue"))).isEqualByComparingTo("15.00");

        assertThat(call(c.token(), HttpMethod.POST, "/v1/pharmacy/write-offs", Map.of("method", "lost", "lines", List.of(line(q, 5)))).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        Map after = get(c, "/v1/pharmacy/reports/valuation");
        assertThat(num(after.get("quarantinedValue"))).isEqualByComparingTo("0");
        assertThat(num(after.get("totalValue"))).isEqualByComparingTo("20.00");
    }
}

package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.pharmacy.api.StockCommands;
import com.nabd.hms.pharmacy.api.StockCommands.BatchReceipt;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** PHL-012: the In-house worklist from clinical.rx.signed.v1 (outbox dispatcher path; the Valkey/SSE path is Phl012WorklistLiveApiTest). */
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl012WorklistApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 400_000);

    @Autowired
    private StockCommands stock;
    @Autowired
    private OutboxDispatcher dispatcher;
    @Autowired
    private RxSignedWorklistConsumer consumer;
    @Autowired
    private PharmacyWorklistExpiryJob expiryJob;

    record Clinic(SeededTenant tenant, SeededStaff staff, String token) {
        UUID t() {
            return tenant.id();
        }
    }

    private static String phone() {
        return "+9196" + String.format("%08d", SEQ.incrementAndGet());
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private Clinic clinic(String mode) {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = ClinicianFixture.licensed(seedStaff(tenant, seedFullAccessRole(tenant.id()), "p12" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        su().update("UPDATE staff SET name = 'Dr Rao' WHERE id = ?", staff.id());
        String token = loginAndGetAccessToken(staff);
        if (!"external".equals(mode)) {
            assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", mode, "reason", "PHL-012 test")),
                    Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        return new Clinic(tenant, staff, token);
    }

    /** A pharmacist: pharmacy view + edit (so pharmacy:dispense), named for the "taken by" message. */
    private String pharmacist(Clinic c, String name) {
        UUID role = seedRole(c.t(), "Pharmacist " + SEQ.incrementAndGet(), false, new ModuleGrant("pharmacy", true, true, true, false, false, false, false));
        SeededStaff s = seedStaff(c.tenant(), role, "ph" + SEQ.incrementAndGet() + "@q.com", phone(), false);
        su().update("UPDATE staff SET name = ? WHERE id = ?", name, s.id());
        return loginAndGetAccessToken(s);
    }

    private UUID item(Clinic c, String name, String scheduleClass, boolean inHouse) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", name,
                "isRx", !"OTC".equals(scheduleClass), "price", 10.00, "taxRatePercent", 12, "scheduleClass", scheduleClass, "inHouse", inHouse)), Map.class);
        assertThat(r.getStatusCode()).as("item %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private UUID receive(Clinic c, UUID item, int qty) {
        return inTenantTx(c.t(), () -> stock.receiveBatch(item, new BatchReceipt("B" + SEQ.incrementAndGet(), LocalDate.now().plusDays(400),
                new BigDecimal("12.00"), new BigDecimal("7.50"), qty, null, null, null), c.staff().id(), "GRN").batchId());
    }

    private String code(Clinic c, UUID item) {
        return su().queryForObject("SELECT code FROM charge_catalogue WHERE id = ?", String.class, item);
    }

    private String visit(Clinic c, String patientName) {
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", patientName, "phone", phone(),
                "dob", LocalDate.of(1960, 1, 1).plusDays(SEQ.get() % 9000).toString(), "gender", "female")), Map.class).getBody().get("id");
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patient, "doctorId", c.staff().id().toString())), Map.class).getBody().get("id");
    }

    /** Signs {name, qty} lines for the visit; returns the prescription item ids in order. */
    private List<String> sign(Clinic c, String qe, Object[]... lines) {
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

    private void deliver() {
        for (int i = 0; i < 5; i++) dispatcher.poll();
    }

    private ResponseEntity<Map> worklist(String token, String query) {
        return exchange("/v1/pharmacy/worklist" + query, HttpMethod.GET, authed(token), Map.class);
    }

    private List<Map<String, Object>> items(String token, String query) {
        ResponseEntity<Map> r = worklist(token, query);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("items");
    }

    private ResponseEntity<Map> claim(String token, Object entryId) {
        return exchange("/v1/pharmacy/worklist/" + entryId + "/claim", HttpMethod.POST, authedJsonBody(token, Map.of()), Map.class);
    }

    private int entries(Clinic c) {
        return su().queryForObject("SELECT count(*) FROM pharmacy_worklist_entries WHERE tenant_id = ?", Integer.class, c.t());
    }

    private List<String> events(Clinic c, String type) {
        return su().queryForList("SELECT payload::text FROM outbox_events WHERE tenant_id = ? AND event_type = ? ORDER BY id", String.class, c.t(), type);
    }

    private static String slug(ResponseEntity<Map> r) {
        String type = String.valueOf(r.getBody().get("type"));
        return type.substring(type.lastIndexOf('/') + 1);
    }

    private void toCheckout(Clinic c, String qe) {
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + qe + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", s)), Map.class);
        }
    }

    private ResponseEntity<Map> checkout(Clinic c, String qe, Object[]... lines) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] l : lines) out.add(new HashMap<>(Map.of("chargeCode", l[0], "quantity", l[1], "prescriptionItemId", l[2])));
        return exchange("/v1/billing/checkout/" + qe, HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", out)), Map.class);
    }

    // ---------------- AC1: 3 in-house + 1 external line -> one entry with 3 lines ----------------

    @Test
    void aSignedRxWithThreeInHouseLinesAndOneExternalIsOneEntryWithThreeLines() {
        Clinic c = clinic("in_house");
        receive(c, item(c, "Wl alpha", "OTC", true), 20);
        receive(c, item(c, "Wl beta", "OTC", true), 20);
        receive(c, item(c, "Wl gamma", "OTC", true), 20);
        item(c, "Wl outside", "OTC", false); // bought outside: never on the worklist
        String qe = visit(c, "Meera Nair");
        List<String> rx = sign(c, qe, new Object[]{"Wl alpha", 4}, new Object[]{"Wl outside", 1}, new Object[]{"wl BETA ", 2}, new Object[]{"Wl gamma", 6});
        assertThat(entries(c)).as("nothing before the event is delivered").isZero();
        deliver();

        List<Map<String, Object>> list = items(c.token(), "");
        assertThat(list).hasSize(1);
        Map<String, Object> e = list.get(0);
        assertThat(e).containsEntry("status", "new").containsEntry("patientName", "Meera Nair").containsEntry("doctorName", "Dr Rao")
                .containsEntry("queueEntryId", qe).containsEntry("claimedBy", null).containsEntry("validUntil", null);
        assertThat(e.get("token")).isNotNull();
        List<Map<String, Object>> lines = (List<Map<String, Object>>) e.get("lines");
        assertThat(lines).extracting(l -> l.get("drug")).containsExactly("Wl alpha", "Wl beta", "Wl gamma");
        assertThat(lines).extracting(l -> l.get("prescriptionItemId")).containsExactly(rx.get(0), rx.get(2), rx.get(3));
        assertThat(((Number) lines.get(0).get("qtyPrescribed")).doubleValue()).isEqualTo(4.0);
        assertThat(((Number) lines.get(0).get("qtyDispensed")).doubleValue()).isZero();
        assertThat(lines.get(0)).containsEntry("inStock", true);
        assertThat(events(c, "pharmacy.worklist.entry_created.v1")).hasSize(1).first().asString()
                .contains("\"entryId\": \"" + e.get("id") + "\"").doesNotContain("Meera").doesNotContain("Wl alpha");
    }

    // ---------------- AC2: two claims at once -> one 200, one 409 naming the winner ----------------

    @Test
    void twoPharmacistsStartingTheSameEntryAtOnceGetOneWinnerAndTakenByName() throws Exception {
        Clinic c = clinic("in_house");
        receive(c, item(c, "Race drug", "OTC", true), 10);
        sign(c, visit(c, "Race Patient"), new Object[]{"Race drug", 2});
        deliver();
        Object id = items(c.token(), "").get(0).get("id");
        String asha = pharmacist(c, "Asha Pharmacist"), bilal = pharmacist(c, "Bilal Pharmacist");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<ResponseEntity<Map>> a = pool.submit(() -> { go.await(); return claim(asha, id); });
            Future<ResponseEntity<Map>> b = pool.submit(() -> { go.await(); return claim(bilal, id); });
            go.countDown();
            List<ResponseEntity<Map>> rs = List.of(a.get(), b.get());
            assertThat(rs).extracting(ResponseEntity::getStatusCode).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
            ResponseEntity<Map> won = rs.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).findFirst().orElseThrow();
            ResponseEntity<Map> lost = rs.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).findFirst().orElseThrow();
            String winner = (String) won.getBody().get("claimedByName");
            assertThat(won.getBody()).containsEntry("status", "in_progress");
            assertThat(slug(lost)).isEqualTo("already-claimed");
            assertThat(lost.getBody().get("detail")).isEqualTo("Taken by " + winner + ".");
            assertThat(lost.getBody()).containsEntry("claimedByName", winner);
            // the winner claiming again is a no-op 200; the loser stays refused
            String winnerToken = winner.startsWith("Asha") ? asha : bilal, loserToken = winner.startsWith("Asha") ? bilal : asha;
            assertThat(claim(winnerToken, id).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(slug(claim(loserToken, id))).isEqualTo("already-claimed");
        } finally {
            pool.shutdownNow();
        }
        assertThat(events(c, "pharmacy.worklist.status_changed.v1")).hasSize(1).first().asString().contains("in_progress");
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.worklist_claimed'", Integer.class, c.t()))
                .isEqualTo(1);
        assertThat(items(c.token(), "?status=in_progress")).hasSize(1);
        assertThat(items(c.token(), "?status=new")).isEmpty();
    }

    // ---------------- AC3: 0 usable stock -> out of stock inline, still partially dispensable ----------------

    @Test
    void anItemWithNoUsableStockShowsOutOfStockAndTheEntryCanBePartiallyDispensed() {
        Clinic c = clinic("in_house");
        UUID inStock = item(c, "Pd stocked", "OTC", true), empty = item(c, "Pd empty", "OTC", true), lapsed = item(c, "Pd lapsed", "OTC", true);
        receive(c, inStock, 10);
        UUID old = receive(c, lapsed, 5);
        su().update("UPDATE stock_batches SET expiry_date = current_date - 1 WHERE id = ?", old); // only expired stock: not usable
        String qe = visit(c, "Partial Patient");
        List<String> rx = sign(c, qe, new Object[]{"Pd stocked", 3}, new Object[]{"Pd empty", 2}, new Object[]{"Pd lapsed", 1});
        deliver();
        List<Map<String, Object>> lines = (List<Map<String, Object>>) items(c.token(), "").get(0).get("lines");
        assertThat(lines).extracting(l -> l.get("inStock")).containsExactly(true, false, false);

        toCheckout(c, qe);
        ResponseEntity<Map> bill = checkout(c, qe, new Object[]{code(c, inStock), 3, rx.get(0)});
        assertThat(bill.getStatusCode()).as("%s", bill.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, Object> e = items(c.token(), "").get(0);
        assertThat(e).containsEntry("status", "partially_dispensed");
        lines = (List<Map<String, Object>>) e.get("lines");
        assertThat(lines).extracting(l -> ((Number) l.get("qtyDispensed")).doubleValue()).containsExactly(3.0, 0.0, 0.0);
        assertThat(events(c, "pharmacy.worklist.status_changed.v1")).anySatisfy(p -> assertThat(p).contains("partially_dispensed"));

        // the rest arrives and is billed on a second checkout: dispensed (leaves the open columns)
        receive(c, empty, 4);
        receive(c, lapsed, 4);
        ResponseEntity<Map> rest = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", List.of(
                Map.of("chargeCode", code(c, empty), "quantity", 2, "prescriptionItemId", rx.get(1)),
                Map.of("chargeCode", code(c, lapsed), "quantity", 1, "prescriptionItemId", rx.get(2))))), Map.class); // the counter bills the rest
        assertThat(rest.getStatusCode()).as("%s", rest.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(items(c.token(), "")).isEmpty();
        assertThat(items(c.token(), "?status=dispensed")).hasSize(1).first().satisfies(x -> assertThat(x).containsEntry("status", "dispensed"));
    }

    // ---------------- AC4: Rx validity passed -> expired, cannot be claimed or dispensed ----------------

    @Test
    void anEntryPastItsRxValidityExpiresAndCanNeitherBeStartedNorDispensed() {
        Clinic c = clinic("in_house");
        // The validity value is test data only: real periods need clinical safety officer sign-off (none is set in the migration).
        su().update("UPDATE pharmacy_schedule_rules SET rx_validity_days = 5 WHERE market = 'IN' AND schedule_class = 'IN_H'");
        try {
            UUID abx = item(c, "Exp antibiotic", "IN_H", true);
            receive(c, abx, 30);
            receive(c, item(c, "Exp otc", "OTC", true), 30);
            String qe = visit(c, "Expiry Patient");
            List<String> rx = sign(c, qe, new Object[]{"Exp antibiotic", 10}, new Object[]{"Exp otc", 1});
            deliver();
            Map<String, Object> e = items(c.token(), "").get(0);
            Instant signedAt = Instant.parse((String) e.get("signedAt")), validUntil = Instant.parse((String) e.get("validUntil"));
            assertThat(Duration.between(signedAt, validUntil)).as("the shortest class validity of its lines").isEqualTo(Duration.ofDays(5));

            su().update("UPDATE pharmacy_worklist_entries SET valid_until = now() - interval '1 minute' WHERE id = ?::uuid", e.get("id")); // 5 days later
            assertThat(items(c.token(), "")).as("reads expired before the job runs").isEmpty();
            ResponseEntity<Map> start = claim(c.token(), e.get("id"));
            assertThat(start.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(slug(start)).isEqualTo("rx-expired");
            toCheckout(c, qe);
            ResponseEntity<Map> bill = checkout(c, qe, new Object[]{code(c, abx), 10, rx.get(0)});
            assertThat(bill.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(slug(bill)).isEqualTo("rx-expired");
            assertThat(su().queryForObject("SELECT stock_qty FROM charge_catalogue WHERE id = ?", Integer.class, abx)).as("nothing left the shelf").isEqualTo(30);

            assertThat(expiryJob.runOnce()).isGreaterThanOrEqualTo(1);
            assertThat(su().queryForObject("SELECT status FROM pharmacy_worklist_entries WHERE id = ?::uuid", String.class, e.get("id"))).isEqualTo("expired");
            assertThat(events(c, "pharmacy.worklist.status_changed.v1")).hasSize(1).first().asString().contains("expired");
            assertThat(items(c.token(), "?status=expired")).hasSize(1);
            assertThat(expiryJob.runOnce()).as("an expired entry is not expired twice").isZero();
        } finally {
            su().update("UPDATE pharmacy_schedule_rules SET rx_validity_days = NULL WHERE market = 'IN' AND schedule_class = 'IN_H'");
        }
    }

    // ---------------- AC5: External or Hybrid clinics get no entries ----------------

    @Test
    void hybridAndExternalClinicsGetNoWorklistEntries() {
        Clinic hybrid = clinic("hybrid");
        ResponseEntity<Map> added = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(hybrid.token(), Map.of("name", "Hy drug",
                "isRx", false, "price", 10.00, "taxRatePercent", 12, "stockQty", 9)), Map.class);
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        sign(hybrid, visit(hybrid, "Hybrid Patient"), new Object[]{"Hy drug", 1});
        Clinic external = clinic("external");
        sign(external, visit(external, "External Patient"), new Object[]{"Any drug", 1});
        deliver();

        assertThat(entries(hybrid)).isZero();
        assertThat(entries(external)).isZero();
        assertThat(slug(worklist(hybrid.token(), ""))).isEqualTo("feature-requires-inhouse");
        assertThat(worklist(external.token(), "").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // the outbox row was consumed (not retried) in both clinics
        for (Clinic c : List.of(hybrid, external)) {
            assertThat(su().queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'clinical.rx.signed.v1' AND status <> 'done'",
                    Integer.class, c.t())).isZero();
        }
    }

    // ---------------- AC6: the event delivered twice -> exactly one entry ----------------

    @Test
    void theSameEventDeliveredTwiceIsExactlyOneEntry() throws Exception {
        Clinic c = clinic("in_house");
        receive(c, item(c, "Dup drug", "OTC", true), 10);
        sign(c, visit(c, "Dup Patient"), new Object[]{"Dup drug", 1});
        deliver();
        Map<String, Object> row = su().queryForMap("SELECT id, tenant_id, payload::text AS payload, created_at FROM outbox_events WHERE tenant_id = ? " +
                "AND event_type = 'clinical.rx.signed.v1'", c.t());
        OutboxEvent again = new OutboxEvent((Long) row.get("id"), c.t(), "clinical.rx.signed.v1", (String) row.get("payload"), 1);
        consumer.handle(again);
        consumer.handle(again);
        su().update("UPDATE outbox_events SET status = 'pending', attempts = 0 WHERE id = ?", row.get("id")); // and the dispatcher once more
        deliver();
        assertThat(entries(c)).isEqualTo(1);
        assertThat(events(c, "pharmacy.worklist.entry_created.v1")).hasSize(1);
    }

    // ---------------- permissions, tenancy, validation ----------------

    @Test
    void theWorklistNeedsPharmacyDispenseAndStaysInsideTheClinic() {
        Clinic a = clinic("in_house");
        receive(a, item(a, "Iso drug", "OTC", true), 10);
        sign(a, visit(a, "Iso Patient"), new Object[]{"Iso drug", 1});
        deliver();
        Object id = items(a.token(), "").get(0).get("id");

        UUID viewOnly = seedRole(a.t(), "Pharmacy viewer " + SEQ.incrementAndGet(), false, new ModuleGrant("pharmacy", true, false, false, false, false, false, false));
        String viewer = loginAndGetAccessToken(seedStaff(a.tenant(), viewOnly, "pv" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        assertThat(worklist(viewer, "").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(claim(viewer, id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Clinic b = clinic("in_house");
        assertThat(items(b.token(), "")).isEmpty();
        assertThat(claim(b.token(), id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(su().queryForObject("SELECT status FROM pharmacy_worklist_entries WHERE id = ?::uuid", String.class, id)).isEqualTo("new");

        assertThat(worklist(a.token(), "?status=bogus").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(worklist(a.token(), "?limit=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(worklist(a.token(), "?cursor=%25%25").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void cursorPagesNewestFirstWithoutRepeats() {
        Clinic c = clinic("in_house");
        receive(c, item(c, "Pg drug", "OTC", true), 50);
        for (int i = 0; i < 3; i++) sign(c, visit(c, "Page Patient " + i), new Object[]{"Pg drug", 1});
        deliver();
        ResponseEntity<Map> first = worklist(c.token(), "?limit=2");
        List<Map<String, Object>> p1 = (List<Map<String, Object>>) first.getBody().get("items");
        assertThat(p1).extracting(e -> e.get("patientName")).containsExactly("Page Patient 2", "Page Patient 1");
        String next = (String) first.getBody().get("nextCursor");
        Map body = worklist(c.token(), "?limit=2&cursor=" + next).getBody();
        assertThat((List<Map<String, Object>>) body.get("items")).extracting(e -> e.get("patientName")).containsExactly("Page Patient 0");
        assertThat(body.get("nextCursor")).isNull();
    }

    // ---------------- migrate_data: the V2950 backfill ----------------

    @Test
    void theBackfillAddsRecentUndispensedInHousePrescriptionsOnce() throws Exception {
        Clinic c = clinic("in_house");
        receive(c, item(c, "Bf drug", "OTC", true), 10);
        sign(c, visit(c, "Backfill Patient"), new Object[]{"Bf drug", 2}, new Object[]{"Bf outside drug", 1});
        String older = visit(c, "Old Patient");
        sign(c, older, new Object[]{"Bf drug", 1});
        // outside the 7 days (signed rows are immutable: the trigger is bypassed on this connection only)
        su().execute("SET session_replication_role = replica; UPDATE prescriptions SET signed_at = now() - interval '8 days' WHERE queue_entry_id = '"
                + UUID.fromString(older) + "'");
        su().update("DELETE FROM pharmacy_worklist_entries WHERE tenant_id = ?", c.t()); // as before the migration (events not delivered)
        String sql = Files.readString(Path.of("../db/migrations/V2950__pharmacy_worklist.sql"));
        String backfill = sql.substring(sql.indexOf("DO $$"));
        su().execute(backfill);
        su().execute(backfill); // idempotent
        List<Map<String, Object>> list = items(c.token(), "");
        assertThat(list).hasSize(1).first().satisfies(e -> assertThat(e).containsEntry("patientName", "Backfill Patient"));
        assertThat((List<?>) list.get(0).get("lines")).hasSize(1);
    }
}

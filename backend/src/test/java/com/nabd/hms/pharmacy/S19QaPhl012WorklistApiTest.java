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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** S19 QA (independent): PHL-012 In-house worklist edges. Validity values below are test data only (no clinical value is asserted). */
@SuppressWarnings({"unchecked", "rawtypes"})
class S19QaPhl012WorklistApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 100_000);

    @Autowired StockCommands stock;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired RxSignedWorklistConsumer consumer;
    @Autowired PharmacyWorklistExpiryJob expiryJob;

    record Clinic(SeededTenant tenant, SeededStaff staff, String token) {
        UUID t() { return tenant.id(); }
    }

    private static String phone() { return "+9197" + String.format("%08d", SEQ.incrementAndGet()); }

    private JdbcTemplate su() { return new JdbcTemplate(superuserDataSource()); }

    private Clinic clinic(String mode) {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = ClinicianFixture.licensed(seedStaff(tenant, seedFullAccessRole(tenant.id()), "q12" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        String token = loginAndGetAccessToken(staff);
        if (!"external".equals(mode)) setMode(token, mode);
        return new Clinic(tenant, staff, token);
    }

    private void setMode(String token, String mode) {
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", mode, "reason", "S19 QA")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String staffToken(Clinic c, String name, ModuleGrant... grants) {
        UUID role = seedRole(c.t(), "R" + SEQ.incrementAndGet(), false, grants);
        SeededStaff s = seedStaff(c.tenant(), role, "qs" + SEQ.incrementAndGet() + "@q.com", phone(), false);
        su().update("UPDATE staff SET name = ? WHERE id = ?", name, s.id());
        return loginAndGetAccessToken(s);
    }

    private String pharmacist(Clinic c, String name) {
        return staffToken(c, name, new ModuleGrant("pharmacy", true, true, true, false, false, false, false));
    }

    private UUID item(Clinic c, String name, String cls, boolean inHouse) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", name,
                "isRx", !"OTC".equals(cls), "price", 10.00, "taxRatePercent", 12, "scheduleClass", cls, "inHouse", inHouse)), Map.class);
        assertThat(r.getStatusCode()).as("item %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private void receive(Clinic c, UUID item, int qty) {
        inTenantTx(c.t(), () -> stock.receiveBatch(item, new BatchReceipt("QB" + SEQ.incrementAndGet(), LocalDate.now().plusDays(400),
                new BigDecimal("12.00"), new BigDecimal("7.50"), qty, null, null, null), c.staff().id(), "GRN"));
    }

    private String code(UUID item) { return su().queryForObject("SELECT code FROM charge_catalogue WHERE id = ?", String.class, item); }

    private String visit(Clinic c, String patientName) {
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", patientName, "phone", phone(),
                "dob", LocalDate.of(1970, 1, 1).plusDays(SEQ.get() % 9000).toString(), "gender", "male")), Map.class).getBody().get("id");
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patient, "doctorId", c.staff().id().toString())), Map.class).getBody().get("id");
    }

    private List<String> sign(Clinic c, String qe, Object[]... lines) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object[] l : lines) items.add(Map.of("drugName", l[0], "dosage", "1 tab", "frequency", "BD", "quantity", l[1], "quantityUnit", "tablets"));
        ResponseEntity<Map> saved = exchange("/v1/clinical/prescriptions/" + qe, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("items", items)), Map.class);
        assertThat(saved.getStatusCode()).as("rx %s", saved.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> signed = exchange("/v1/clinical/prescriptions/" + qe + "/sign", HttpMethod.POST, authed(c.token()), Map.class);
        assertThat(signed.getStatusCode()).as("sign %s", signed.getBody()).isEqualTo(HttpStatus.OK);
        List<String> ids = new ArrayList<>();
        for (Object o : (List<?>) saved.getBody().get("items")) ids.add((String) ((Map<?, ?>) o).get("id"));
        return ids;
    }

    private void deliver() { for (int i = 0; i < 5; i++) dispatcher.poll(); }

    private ResponseEntity<Map> worklist(String token, String q) { return exchange("/v1/pharmacy/worklist" + q, HttpMethod.GET, authed(token), Map.class); }

    private List<Map<String, Object>> items(String token, String q) {
        ResponseEntity<Map> r = worklist(token, q);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("items");
    }

    private ResponseEntity<Map> claim(String token, Object id) {
        return exchange("/v1/pharmacy/worklist/" + id + "/claim", HttpMethod.POST, authedJsonBody(token, Map.of()), Map.class);
    }

    private int entries(Clinic c) { return su().queryForObject("SELECT count(*) FROM pharmacy_worklist_entries WHERE tenant_id = ?", Integer.class, c.t()); }

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

    private Map<String, Object> oneEntry(Clinic c, String drug, String patient, int qty) {
        receive(c, item(c, drug, "OTC", true), 20);
        sign(c, visit(c, patient), new Object[]{drug, qty});
        deliver();
        return items(c.token(), "").get(0);
    }

    // W-API-01 / 02 (AC1)
    @Test
    void wApi01_threeInHouseLinesAndOneExternalGiveOneEntryOfThreeAndAnAllExternalRxGivesNone() {
        Clinic c = clinic("in_house");
        receive(c, item(c, "Qa amlo", "OTC", true), 10);
        receive(c, item(c, "Qa metf", "OTC", true), 10);
        item(c, "Qa zero", "OTC", true); // in-house but nothing received: still on the worklist (out of stock inline)
        item(c, "Qa outside", "OTC", false);
        String qe = visit(c, "Kavya Iyer");
        List<String> rx = sign(c, qe, new Object[]{"Qa outside", 2}, new Object[]{"Qa amlo", 1}, new Object[]{"QA METF", 3}, new Object[]{"Qa zero", 1});
        deliver();
        List<Map<String, Object>> list = items(c.token(), "");
        assertThat(list).hasSize(1);
        List<Map<String, Object>> lines = (List<Map<String, Object>>) list.get(0).get("lines");
        assertThat(lines).extracting(l -> l.get("prescriptionItemId")).containsExactly(rx.get(1), rx.get(2), rx.get(3));
        assertThat(lines).extracting(l -> l.get("inStock")).containsExactly(true, true, false);
        assertThat(events(c, "pharmacy.worklist.entry_created.v1")).hasSize(1).first().asString().doesNotContain("Kavya").doesNotContain("Qa amlo");

        sign(c, visit(c, "Outside Only"), new Object[]{"Qa outside", 1}, new Object[]{"Unknown brand", 1});
        deliver();
        assertThat(entries(c)).as("an all-external Rx creates no entry").isEqualTo(1);
    }

    // W-API-03 / 06 (AC2): ten pharmacists press Start at once
    @Test
    void wApi06_tenConcurrentClaimsHaveExactlyOneWinner() throws Exception {
        Clinic c = clinic("in_house");
        Object id = oneEntry(c, "Qa race", "Race Patient", 1).get("id");
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 10; i++) tokens.add(pharmacist(c, "Pharm " + i));
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> fs = new ArrayList<>();
        try {
            for (String t : tokens) fs.add(pool.submit(() -> { go.await(); return claim(t, id); }));
            go.countDown();
            List<ResponseEntity<Map>> rs = new ArrayList<>();
            for (var f : fs) rs.add(f.get());
            assertThat(rs.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count()).isEqualTo(1);
            String winner = (String) rs.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).findFirst().orElseThrow().getBody().get("claimedByName");
            for (var r : rs) {
                if (r.getStatusCode() == HttpStatus.OK) continue;
                assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(slug(r)).isEqualTo("already-claimed");
                assertThat(r.getBody()).containsEntry("claimedByName", winner).containsEntry("detail", "Taken by " + winner + ".");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(events(c, "pharmacy.worklist.status_changed.v1")).hasSize(1);
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.worklist_claimed'", Integer.class, c.t())).isEqualTo(1);
    }

    // W-API-05 / 20: who holds pharmacy:dispense
    @Test
    void wApi05_withoutPharmacyEditThereIsNoDispenseGrant() {
        Clinic c = clinic("in_house");
        Object id = oneEntry(c, "Qa perm", "Perm Patient", 1).get("id");
        String viewCreate = staffToken(c, "View Create", new ModuleGrant("pharmacy", true, true, false, false, true, false, false),
                new ModuleGrant("billing", true, true, true, false, true, true, false));
        assertThat(worklist(viewCreate, "").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(claim(viewCreate, id).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String noAuth = exchange("/v1/pharmacy/worklist/" + id + "/claim", HttpMethod.POST, jsonBody(Map.of()), Map.class).getStatusCode().toString();
        assertThat(noAuth).startsWith("401");
        assertThat(su().queryForObject("SELECT status FROM pharmacy_worklist_entries WHERE id = ?::uuid", String.class, id)).isEqualTo("new");
        String ph = pharmacist(c, "Edit Holder");
        assertThat(claim(ph, id).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // W-API-16: cross-tenant reads, claims and RLS
    @Test
    void wApi16_anotherClinicCanNeitherSeeNorClaimAnEntry() {
        Clinic a = clinic("in_house");
        Object id = oneEntry(a, "Qa iso", "Iso Patient", 1).get("id");
        Clinic b = clinic("in_house");
        oneEntry(b, "Qa iso b", "Other Patient", 1);
        assertThat(items(b.token(), "?status=new,in_progress,partially_dispensed,dispensed,cancelled,expired"))
                .extracting(e -> e.get("id")).doesNotContain(id);
        assertThat(claim(b.token(), id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(su().queryForObject("SELECT status FROM pharmacy_worklist_entries WHERE id = ?::uuid", String.class, id)).isEqualTo("new");
        Integer seen = inTenantTx(b.t(), () -> jdbc.queryForObject("SELECT count(*) FROM pharmacy_worklist_entries WHERE id = ?::uuid", Integer.class, id));
        assertThat(seen).as("RLS hides tenant A's row from tenant B").isZero();
    }

    // W-API-11 / 13 (AC5)
    @Test
    void wApi11_aClinicSwitchedToHybridGetsNoNewEntriesAndCannotWorkTheOldOnes() {
        Clinic c = clinic("in_house");
        Object id = oneEntry(c, "Qa switch", "Switch Patient", 1).get("id");
        setMode(c.token(), "hybrid");
        receive(c, item(c, "Qa hy", "OTC", true), 5);
        sign(c, visit(c, "Hybrid Later"), new Object[]{"Qa hy", 1});
        deliver();
        assertThat(entries(c)).as("no entry after the switch").isEqualTo(1);
        assertThat(slug(worklist(c.token(), ""))).isEqualTo("feature-requires-inhouse");
        assertThat(claim(c.token(), id).getStatusCode()).isIn(HttpStatus.CONFLICT, HttpStatus.FORBIDDEN);
        assertThat(su().queryForObject("SELECT status FROM pharmacy_worklist_entries WHERE id = ?::uuid", String.class, id)).isEqualTo("new");
    }

    // W-API-14 / 15 (AC6): concurrent duplicate deliveries and a re-published event with a new id
    @Test
    void wApi15_concurrentAndRepublishedDeliveriesGiveOneEntry() throws Exception {
        Clinic c = clinic("in_house");
        receive(c, item(c, "Qa dup", "OTC", true), 10);
        sign(c, visit(c, "Dup Patient"), new Object[]{"Qa dup", 1});
        Map<String, Object> row = su().queryForMap("SELECT id, payload::text AS payload FROM outbox_events WHERE tenant_id = ? AND event_type = 'clinical.rx.signed.v1'", c.t());
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (long fake : new long[]{(Long) row.get("id"), (Long) row.get("id"), 900_000_000L + SEQ.incrementAndGet()}) {
            OutboxEvent ev = new OutboxEvent(fake, c.t(), "clinical.rx.signed.v1", (String) row.get("payload"), 1);
            fs.add(pool.submit(() -> { go.await(); consumer.handle(ev); return null; }));
        }
        go.countDown();
        for (var f : fs) f.get();
        pool.shutdown();
        deliver();
        assertThat(entries(c)).isEqualTo(1);
        assertThat(events(c, "pharmacy.worklist.entry_created.v1")).hasSize(1);
    }

    // W-API-09 (AC4): an expired entry cannot be dispensed at the counter either, and an in-progress entry also expires
    @Test
    void wApi09_expiredEntriesCannotBeDispensedAtTheCounterAndClaimedOnesExpireToo() {
        Clinic c = clinic("in_house");
        su().update("UPDATE pharmacy_schedule_rules SET rx_validity_days = 3 WHERE market = 'IN' AND schedule_class = 'OTC'"); // test data only
        try {
            UUID it = item(c, "Qa lapse", "OTC", true);
            receive(c, it, 10);
            String qe = visit(c, "Lapse Patient");
            List<String> rx = sign(c, qe, new Object[]{"Qa lapse", 2});
            deliver();
            Map<String, Object> e = items(c.token(), "").get(0);
            assertThat(e.get("validUntil")).isNotNull();
            assertThat(claim(c.token(), e.get("id")).getStatusCode()).isEqualTo(HttpStatus.OK);
            su().update("UPDATE pharmacy_worklist_entries SET valid_until = now() - interval '1 second' WHERE id = ?::uuid", e.get("id"));
            assertThat(items(c.token(), "?status=in_progress")).isEmpty();
            assertThat(slug(claim(c.token(), e.get("id")))).isEqualTo("rx-expired");
            ResponseEntity<Map> counter = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", List.of(
                    Map.of("chargeCode", code(it), "quantity", 2, "prescriptionItemId", rx.get(0))))), Map.class);
            assertThat(counter.getStatusCode()).as("%s", counter.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(slug(counter)).isEqualTo("rx-expired");
            assertThat(su().queryForObject("SELECT stock_qty FROM charge_catalogue WHERE id = ?", Integer.class, it)).isEqualTo(10);
            assertThat(expiryJob.runOnce()).isGreaterThanOrEqualTo(1);
            assertThat(su().queryForObject("SELECT status FROM pharmacy_worklist_entries WHERE id = ?::uuid", String.class, e.get("id"))).isEqualTo("expired");
        } finally {
            su().update("UPDATE pharmacy_schedule_rules SET rx_validity_days = NULL WHERE market = 'IN' AND schedule_class = 'OTC'");
        }
    }

    // W-API-10: no clinical validity value is invented by the migrations
    @Test
    void wApi10_noRxValidityValueIsShipped() {
        assertThat(su().queryForObject("SELECT count(*) FROM pharmacy_schedule_rules WHERE rx_validity_days IS NOT NULL", Integer.class)).isZero();
        Clinic c = clinic("in_house");
        assertThat(oneEntry(c, "Qa nov", "Novalid Patient", 1).get("validUntil")).isNull();
    }

    // W-API-08 / 19: partial then full dispense; a dispensed entry cannot be claimed
    @Test
    void wApi19_partialThenFullDispenseAndADispensedEntryIsNotClaimable() {
        Clinic c = clinic("in_house");
        UUID a = item(c, "Qa pa", "OTC", true), b = item(c, "Qa pb", "OTC", true);
        receive(c, a, 10);
        String qe = visit(c, "Partial Two");
        List<String> rx = sign(c, qe, new Object[]{"Qa pa", 2}, new Object[]{"Qa pb", 1});
        deliver();
        Object id = items(c.token(), "").get(0).get("id");
        toCheckout(c, qe);
        ResponseEntity<Map> bill = exchange("/v1/billing/checkout/" + qe, HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", List.of(
                new HashMap<>(Map.of("chargeCode", code(a), "quantity", 2, "prescriptionItemId", rx.get(0)))))), Map.class);
        assertThat(bill.getStatusCode()).as("%s", bill.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(items(c.token(), "").get(0)).containsEntry("status", "partially_dispensed");
        assertThat(slug(claim(c.token(), id))).isEqualTo("not-claimable");
        receive(c, b, 3);
        ResponseEntity<Map> rest = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", List.of(
                Map.of("chargeCode", code(b), "quantity", 1, "prescriptionItemId", rx.get(1))))), Map.class);
        assertThat(rest.getStatusCode()).as("%s", rest.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(items(c.token(), "?status=dispensed")).extracting(e -> e.get("id")).containsExactly(id);
        assertThat(slug(claim(c.token(), id))).isEqualTo("not-claimable");
    }

    // W-API-17 / 18: validation and paging
    @Test
    void wApi18_validationAndPagingWithoutRepeats() {
        Clinic c = clinic("in_house");
        receive(c, item(c, "Qa pg", "OTC", true), 50);
        for (int i = 0; i < 3; i++) sign(c, visit(c, "Pg " + i), new Object[]{"Qa pg", 1});
        deliver();
        assertThat(worklist(c.token(), "?limit=101").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(worklist(c.token(), "?status=new,bogus").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Set<Object> seen = new HashSet<>();
        String cursor = "";
        int pages = 0;
        do {
            Map body = worklist(c.token(), "?limit=1" + (cursor.isEmpty() ? "" : "&cursor=" + cursor)).getBody();
            for (Map<String, Object> e : (List<Map<String, Object>>) body.get("items")) assertThat(seen.add(e.get("id"))).isTrue();
            cursor = body.get("nextCursor") == null ? "" : (String) body.get("nextCursor");
            pages++;
        } while (!cursor.isEmpty() && pages < 10);
        assertThat(seen).hasSize(3);
    }
}

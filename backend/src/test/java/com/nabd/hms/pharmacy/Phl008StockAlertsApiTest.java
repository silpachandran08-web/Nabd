package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.messaging.NotificationClaim;
import com.nabd.hms.messaging.NotificationService;
import com.nabd.hms.messaging.TemplateLint;
import com.nabd.hms.pharmacy.api.StockCommands;
import com.nabd.hms.pharmacy.api.StockCommands.BatchReceipt;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHL-008: stock alerts (one per batch per expiry threshold, low stock the same day, dead stock with value), acknowledge, the
 * expiry report (sorted, value at risk, CSV) and the 07:00 clinic-time digest to one named person (counts and a link only).
 * The job's clock is frozen per step (MutableClock); stock and API calls run on the real clock so receipts and tokens stay valid.
 */
@Import(Phl008StockAlertsApiTest.FrozenClock.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl008StockAlertsApiTest extends ApiTestBase {

    private static final AtomicInteger PHONE = new AtomicInteger(5100000);
    private static final String IST = "Asia/Kolkata";
    private static final String AST = "Asia/Riyadh";

    @Autowired MutableClock clock;
    @Autowired StockCommands stock;
    @Autowired StockAlertJob job;
    @Autowired NotificationService notifications;
    @Autowired TemplateLint lint;

    @AfterEach
    void realTime() {
        clock.reset();
    }

    private record Clinic(SeededTenant tenant, String token, UUID staffId, String phone, String zone) {
        UUID t() {
            return tenant.id();
        }
    }

    private static String phone() {
        return "+9195" + String.format("%08d", PHONE.incrementAndGet());
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    /** An In-house clinic in {@code zone}; its full-access staff member is the named alert recipient and has an approved digest template. */
    private Clinic clinic(String zone) {
        SeededTenant tenant = seedTenant();
        jdbc.update("UPDATE tenants SET timezone = ? WHERE id = ?", zone, tenant.id());
        String phone = phone();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), UUID.randomUUID().toString().substring(0, 8) + "@p8.test", phone, false);
        String token = loginAndGetAccessToken(staff);
        ResponseEntity<Map> r = call(token, HttpMethod.PATCH, "/v1/pharmacy/settings",
                Map.of("mode", "in_house", "reason", "PHL-008 test", "alertRecipientStaffId", staff.id().toString()));
        assertThat(r.getStatusCode()).as("settings %s", r.getBody()).isEqualTo(HttpStatus.OK);
        inTenantTx(tenant.id(), () -> jdbc.update("""
                INSERT INTO whatsapp_templates (tenant_id, name, meta_name, language, category, header_text, body, param_count, status)
                VALUES (?, 'stock_alerts_digest', ?, 'en', 'UTILITY', 'Nabd', ?, 7, 'approved')""",
                tenant.id(), "sad_" + tenant.id().toString().substring(0, 8), StockAlertService.DIGEST_TEMPLATE_EN));
        return new Clinic(tenant, token, staff.id(), phone, zone);
    }

    private ResponseEntity<Map> call(String token, HttpMethod m, String path, Object body) {
        return exchange(path, m, authedJsonBody(token, body), Map.class);
    }

    private UUID item(Clinic c, String name, Integer reorderLevel) {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "isRx", false, "price", 100.00, "taxRatePercent", 12.00, "scheduleClass", "OTC"));
        if (reorderLevel != null) {
            body.put("reorderLevel", reorderLevel);
        }
        ResponseEntity<Map> r = call(c.token(), HttpMethod.POST, "/v1/pharmacy/items", body);
        assertThat(r.getStatusCode()).as("item %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("reorderLevel")).isEqualTo(reorderLevel);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private UUID receive(Clinic c, UUID item, String no, LocalDate expiry, int qty, String rate) {
        return inTenantTx(c.t(), () -> stock.receiveBatch(item, new BatchReceipt(no, expiry, new BigDecimal("20.00"), new BigDecimal(rate), qty,
                "A1", null, null), c.staffId(), "GRN " + no).batchId());
    }

    private void sell(Clinic c, UUID item, int qty) {
        inTenantTx(c.t(), () -> stock.issue(item, qty, null, "sale"));
    }

    private LocalDate localToday(Clinic c) {
        return LocalDate.now(ZoneId.of(c.zone()));
    }

    /** The job's pass for one clinic at {@code time} on {@code day}, clinic wall clock. */
    private boolean runAt(Clinic c, LocalDate day, String time) {
        clock.setLocal(day + "T" + time, c.zone());
        try {
            return job.runFor(c.t());
        } finally {
            clock.reset();
        }
    }

    private List<Map<String, Object>> alerts(Clinic c) {
        return inTenantTx(c.t(), () -> jdbc.queryForList("SELECT id, type, item_id, batch_id, value_at_risk, status, action, created_on FROM stock_alerts WHERE tenant_id = ? ORDER BY id", c.t()));
    }

    private List<Map<String, Object>> open(Clinic c, String type) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/alerts" + (type == null ? "" : "?type=" + type), HttpMethod.GET, authed(c.token()), Map.class);
        assertThat(r.getStatusCode()).as("alerts %s", r.getBody()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) r.getBody().get("items");
    }

    private Map<String, Object> counts(Clinic c) {
        return (Map<String, Object>) exchange("/v1/pharmacy/alerts", HttpMethod.GET, authed(c.token()), Map.class).getBody().get("counts");
    }

    private List<UUID> digestRequests(Clinic c) {
        return inTenantTx(c.t(), () -> jdbc.queryForList("SELECT id FROM notification_requests WHERE purpose = 'stock_alerts_digest' ORDER BY created_at", UUID.class));
    }

    private List<String> digestEvents(Clinic c) {
        return inTenantTx(c.t(), () -> jdbc.queryForList("SELECT payload::text FROM outbox_events WHERE tenant_id = ? AND event_type = 'pharmacy.stock.alerts_digest_ready.v1' ORDER BY id",
                String.class, c.t()));
    }

    @Test
    void expiryCrossing90Days_createsExactlyOneThreshold90Alert_thenTheTighterOneSupersedesIt() {
        Clinic c = clinic(IST);
        LocalDate d = localToday(c).plusDays(1);
        UUID item = item(c, "Zinc Syrup PHL8", null);
        UUID batch = receive(c, item, "B-90", d.plusDays(91), 10, "7.5000");

        runAt(c, d, "06:00");
        assertThat(alerts(c)).as("91 days out: no alert yet").isEmpty();

        runAt(c, d.plusDays(1), "06:00"); // 90 days out: crosses the threshold
        runAt(c, d.plusDays(1), "06:30"); // the same day again
        runAt(c, d.plusDays(2), "06:00"); // still inside 90: no second row
        List<Map<String, Object>> rows = alerts(c);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("type", "expiry_90").containsEntry("batch_id", batch).containsEntry("status", "open")
                .containsEntry("created_on", java.sql.Date.valueOf(d.plusDays(1)));
        assertThat((BigDecimal) rows.get(0).get("value_at_risk")).isEqualByComparingTo("75.00"); // 10 x 7.50

        runAt(c, d.plusDays(31), "06:00"); // 60 days out
        assertThat(alerts(c)).extracting(r -> r.get("type")).containsExactly("expiry_90", "expiry_60");
        List<Map<String, Object>> shown = open(c, "expiry");
        assertThat(shown).as("one row per batch: the tighter threshold").hasSize(1);
        assertThat(shown.get(0)).containsEntry("type", "expiry_60").containsEntry("batchNo", "B-90").containsEntry("itemName", "Zinc Syrup PHL8");
        assertThat(counts(c)).containsEntry("expiry", 1).containsEntry("low", 0).containsEntry("dead", 0);

        // acknowledging the shown alert also closes the superseded one; replays keep the first action
        String id = (String) shown.get(0).get("id");
        ResponseEntity<Map> ack = call(c.token(), HttpMethod.POST, "/v1/pharmacy/alerts/" + id + "/acknowledge", Map.of("action", "discounted", "note", "front shelf"));
        assertThat(ack.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ack.getBody()).containsEntry("status", "acknowledged").containsEntry("action", "discounted");
        ResponseEntity<Map> again = call(c.token(), HttpMethod.POST, "/v1/pharmacy/alerts/" + id + "/acknowledge", Map.of("action", "none"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).containsEntry("action", "discounted");
        assertThat(alerts(c)).allSatisfy(r -> assertThat(r).containsEntry("status", "acknowledged").containsEntry("action", "discounted"));
        assertThat(open(c, null)).isEmpty();
        assertThat(inTenantTx(c.t(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.stock_alert_acknowledged'",
                Integer.class, c.t()))).isEqualTo(1);
    }

    @Test
    void lowStock_isRaisedTheSameDayAfterASale_andAgainOnlyAfterARestock() {
        Clinic c = clinic(IST);
        LocalDate today = localToday(c);
        UUID item = item(c, "Saline Drops PHL8", 10);
        receive(c, item, "L-1", today.plusDays(400), 12, "3.0000");
        runAt(c, today, "11:00");
        assertThat(alerts(c)).isEmpty();

        sell(c, item, 3); // 9 < 10
        runAt(c, today, "11:15");
        List<Map<String, Object>> rows = alerts(c);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("type", "low_stock").containsEntry("item_id", item).containsEntry("created_on", java.sql.Date.valueOf(today));
        assertThat(rows.get(0).get("batch_id")).isNull();
        runAt(c, today.plusDays(1), "09:00");
        assertThat(alerts(c)).as("still low, nothing came in: no repeat").hasSize(1);
        List<Map<String, Object>> shown = open(c, "low");
        assertThat(shown).hasSize(1);
        assertThat(shown.get(0)).containsEntry("onHand", 9).containsEntry("reorderLevel", 10);

        receive(c, item, "L-2", today.plusDays(400), 5, "3.0000"); // 14
        sell(c, item, 6);                                           // 8
        runAt(c, today.plusDays(1), "12:00");
        assertThat(alerts(c)).extracting(r -> r.get("type")).containsExactly("low_stock", "low_stock");
    }

    @Test
    void deadStock_noOutwardMovementFor90DaysWithStock_isListedWithValue() {
        Clinic c = clinic(IST);
        LocalDate today = localToday(c);
        UUID dead = item(c, "Calamine PHL8", null);
        UUID moving = item(c, "Glycerin PHL8", null);
        UUID fresh = item(c, "Talc PHL8", null);
        UUID b1 = receive(c, dead, "D-1", today.plusDays(500), 4, "12.5000");
        UUID b2 = receive(c, moving, "M-1", today.plusDays(500), 4, "12.5000");
        receive(c, fresh, "F-1", today.plusDays(500), 4, "12.5000");
        su().update("UPDATE stock_batches SET created_at = now() - interval '120 days' WHERE id IN (?, ?)", b1, b2); // held since 120 days
        sell(c, moving, 1); // moved today

        runAt(c, today, "10:00");
        List<Map<String, Object>> rows = alerts(c);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("type", "dead_stock").containsEntry("item_id", dead);
        assertThat((BigDecimal) rows.get(0).get("value_at_risk")).isEqualByComparingTo("50.00");
        runAt(c, today.plusDays(1), "10:00");
        assertThat(alerts(c)).as("not moved since the alert: no repeat").hasSize(1);
        List<Map<String, Object>> shown = open(c, "dead");
        assertThat(shown).hasSize(1);
        assertThat(shown.get(0)).containsEntry("itemName", "Calamine PHL8").containsEntry("onHand", 4);
    }

    @Test
    void expiryReport_next60Days_sortedByDaysToExpiry_withTotalValueAtRisk_andCsv() {
        Clinic c = clinic(IST);
        LocalDate today = localToday(c);
        UUID a = item(c, "Item A PHL8", null);
        UUID b = item(c, "=Item B PHL8", null);
        UUID x = item(c, "Item X PHL8", null);
        receive(c, a, "A-45", today.plusDays(45), 2, "10.0000");  // 20.00
        receive(c, b, "B-10", today.plusDays(10), 3, "5.0000");   // 15.00
        receive(c, x, "X-100", today.plusDays(100), 9, "1.0000"); // outside 60 days
        UUID gone = receive(c, x, "X-OLD", today.plusDays(300), 1, "4.0000");
        su().update("UPDATE stock_batches SET expiry_date = ?::date WHERE id = ?", today.minusDays(5).toString(), gone); // already expired: 4.00

        ResponseEntity<Map> r = exchange("/v1/pharmacy/reports/expiry?withinDays=60", HttpMethod.GET, authed(c.token()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> lines = (List<Map<String, Object>>) r.getBody().get("lines");
        assertThat(lines).extracting(l -> l.get("batchNo")).containsExactly("X-OLD", "B-10", "A-45");
        assertThat(lines).extracting(l -> l.get("daysToExpiry")).containsExactly(-5, 10, 45);
        assertThat(lines).extracting(l -> l.get("suggestedAction")).containsExactly("quarantine", "discount", "return_to_supplier");
        assertThat(new BigDecimal(String.valueOf(r.getBody().get("totalValueAtRisk")))).isEqualByComparingTo("39.00");

        ResponseEntity<String> csv = exchange("/v1/pharmacy/reports/expiry?withinDays=60&format=csv", HttpMethod.GET, authed(c.token()), String.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getHeaders().getContentType().toString()).startsWith("text/csv");
        String[] csvLines = csv.getBody().split("\r\n");
        assertThat(csvLines[0]).isEqualTo("Item,Batch,Expiry date,Days to expiry,Qty on hand,Purchase rate,Value at risk,Suggested action");
        assertThat(csvLines[2]).startsWith("\"'=Item B PHL8\",\"B-10\","); // a formula-looking name is neutralised
        assertThat(csvLines[csvLines.length - 1]).isEqualTo("Total,,,,,,39.00,");
        assertThat(inTenantTx(c.t(), () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.expiry_report_exported'",
                Integer.class, c.t()))).isEqualTo(1);
    }

    @Test
    void digest_at0700ClinicTime_toTheNamedPerson_countsAndLinkOnly_oncePerDay() {
        Clinic c = clinic(IST);
        LocalDate d = localToday(c).plusDays(1);
        UUID item = item(c, "Paracetamol Syrup PHL8", 5);
        receive(c, item, "P-30", d.plusDays(20), 4, "2.0000"); // on hand 4 < 5: low; 20 days: expiry_30

        assertThat(runAt(c, d, "06:59")).as("before 07:00 clinic time").isFalse();
        assertThat(digestRequests(c)).isEmpty();
        assertThat(runAt(c, d, "07:00")).isTrue();
        assertThat(runAt(c, d, "07:15")).as("once per clinic day").isFalse();

        List<UUID> ids = digestRequests(c);
        assertThat(ids).hasSize(1);
        NotificationClaim claim = notifications.claim(ids.get(0));
        assertThat(claim.recipient().phoneE164()).isEqualTo(c.phone());
        List<String> p = claim.params();
        assertThat(p).hasSize(7);
        assertThat(p.subList(1, 6)).containsExactly("1", "0", "0", "1", "0"); // within 30, 31-60, 61-90, low, dead
        assertThat(p.get(6)).endsWith("/pharmacy/insights");
        assertThat(String.join(" ", p)).doesNotContain("Paracetamol").doesNotContain("P-30");

        List<String> evs = digestEvents(c);
        assertThat(evs).hasSize(1);
        assertThat(evs.get(0)).contains(c.staffId().toString()).contains("\"expiry30\": 1").contains("\"low\": 1").doesNotContain("Paracetamol");

        assertThat(runAt(c, d.plusDays(1), "07:05")).as("the next day: a new digest").isTrue();
        assertThat(digestRequests(c)).hasSize(2);
    }

    @Test
    void digest_followsEachClinicsOwnTimezone_ISTandAST() {
        Clinic india = clinic(IST);
        Clinic saudi = clinic(AST);
        LocalDate d = localToday(india).plusDays(2);
        for (Clinic c : List.of(india, saudi)) {
            UUID item = item(c, "Antacid Gel PHL8", 5);
            receive(c, item, "T-1", d.plusDays(400), 1, "2.0000");
        }
        // 07:00 IST = 04:30 AST
        clock.setLocal(d + "T07:00", IST);
        boolean indiaSent = job.runFor(india.t());
        boolean saudiSent = job.runFor(saudi.t());
        clock.reset();
        assertThat(indiaSent).isTrue();
        assertThat(saudiSent).isFalse();
        assertThat(runAt(saudi, d, "06:59")).isFalse();
        assertThat(runAt(saudi, d, "07:00")).isTrue();
    }

    @Test
    void noRecipientOrNothingOpen_sendsNothing() {
        Clinic c = clinic(IST);
        LocalDate d = localToday(c).plusDays(1);
        assertThat(runAt(c, d, "08:00")).as("nothing open: alerts, not noise").isFalse();
        UUID item = item(c, "Cough Lozenge PHL8", 5);
        receive(c, item, "N-1", d.plusDays(400), 1, "2.0000");
        su().update("UPDATE pharmacy_settings SET alert_recipient_staff_id = NULL WHERE tenant_id = ?", c.t());
        assertThat(runAt(c, d.plusDays(1), "08:00")).as("no named person").isFalse();
        assertThat(digestRequests(c)).isEmpty();
        assertThat(digestEvents(c)).isEmpty();
    }

    @Test
    void acknowledge_needsPharmacyEdit_validatesTheAction_andIsTenantScoped() {
        Clinic a = clinic(IST);
        Clinic b = clinic(IST);
        UUID item = item(a, "Bandage PHL8", 5);
        receive(a, item, "K-1", localToday(a).plusDays(400), 1, "2.0000");
        runAt(a, localToday(a), "10:00");
        String id = (String) open(a, "low").get(0).get("id");

        assertThat(open(b, null)).as("another clinic sees none of them").isEmpty();
        assertThat(call(b.token(), HttpMethod.POST, "/v1/pharmacy/alerts/" + id + "/acknowledge", Map.of("action", "none")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(a.token(), HttpMethod.POST, "/v1/pharmacy/alerts/" + UUID.randomUUID() + "/acknowledge", Map.of("action", "none")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(a.token(), HttpMethod.POST, "/v1/pharmacy/alerts/" + id + "/acknowledge", Map.of("action", "sold")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/pharmacy/alerts?type=bogus", HttpMethod.GET, authed(a.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        UUID viewOnly = seedRole(a.t(), "Viewer " + UUID.randomUUID().toString().substring(0, 6), false,
                new ModuleGrant("pharmacy", true, false, false, false, false, false, false));
        String viewer = loginAndGetAccessToken(seedStaff(a.tenant(), viewOnly, UUID.randomUUID().toString().substring(0, 8) + "@p8.test", phone(), false));
        assertThat(open(new Clinic(a.tenant(), viewer, null, null, IST), "low")).hasSize(1); // pharmacy:view reads
        assertThat(call(viewer, HttpMethod.POST, "/v1/pharmacy/alerts/" + id + "/acknowledge", Map.of("action", "none")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(alerts(a).get(0)).containsEntry("status", "open");
    }

    @Test
    void digestTemplate_passesThePhiLint_withTypedSlots() {
        List<TemplateLint.Slot> slots = new ArrayList<>();
        for (int i = 0; i < StockAlertService.DIGEST_SLOT_TYPES.size(); i++) {
            slots.add(new TemplateLint.Slot(i + 1, StockAlertService.DIGEST_SLOT_TYPES.get(i)));
        }
        assertThat(lint.lint(StockAlertService.DIGEST_TEMPLATE_EN, slots)).isEmpty();
        // a drug name in the body would be refused: the lint is live, not a no-op
        assertThat(lint.lint(StockAlertService.DIGEST_TEMPLATE_EN + " Tablets expiring.", slots)).isNotEmpty();
    }

    static class FrozenClock {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}

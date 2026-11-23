package com.nabd.hms.pharmacy;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.Hazard;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHL-023 (hazard H-04): Schedule H1 and X at the counter. An H1 line is sold only with the seller's second confirmation and a
 * reason; the Schedule X duplicate copy is kept two years from the sale; a sale of H1 or X with no registered pharmacist on duty
 * goes through and is audited; an OTC or plain Schedule H line needs nothing extra. Test tenants run in UTC, so the clinic's
 * today is the UTC date. Item and prescriber names are test fixtures, not clinical content.
 */
@Hazard("H-04")
class Phl023ScheduleCounterApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000));
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 1, 2, 3, 4};
    private static final String PAPER = "/v1/pharmacy/paper-prescriptions";
    private static final String ACKED = "pharmacy.schedule_dispense_acknowledged";
    private static final String NO_PHARMACIST = "pharmacy.no_pharmacist_on_duty";

    private record Ctx(SeededTenant tenant, SeededStaff staff, String token) {
    }

    private static String phone() {
        return "+9196" + String.format("%08d", SEQ.incrementAndGet());
    }

    private static JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    private Ctx ctx() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "p23" + SEQ.incrementAndGet() + "@a.com", phone(), false);
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        return new Ctx(tenant, staff, token);
    }

    /** [id, code] of a stocked item of the given schedule class. */
    private String[] item(Ctx c, String name, String scheduleClass) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", name,
                "isRx", !"OTC".equals(scheduleClass), "hsnCode", "3004", "price", 10.00, "taxRatePercent", 0, "stockQty", 50, "scheduleClass", scheduleClass)), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("item create %s", r.getBody()).isTrue();
        return new String[]{(String) r.getBody().get("id"), (String) r.getBody().get("code")};
    }

    private String photo(String token) throws InterruptedException {
        ResponseEntity<Map> slot = exchange(PAPER + "/uploads", HttpMethod.POST, authedJsonBody(token, Map.of("contentType", "image/png", "sizeBytes", PNG.length)), Map.class);
        assertThat(slot.getStatusCode()).as("slot %s", slot.getBody()).isEqualTo(HttpStatus.CREATED);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        http.exchange(URI.create(url((String) slot.getBody().get("putUrl"))), HttpMethod.PUT, new HttpEntity<>(PNG, h), Void.class);
        String fileId = (String) exchange(PAPER + "/uploads/" + slot.getBody().get("uploadId") + "/complete", HttpMethod.POST, authed(token), Map.class).getBody().get("fileId");
        for (int i = 0; i < 300; i++) {
            if ("available".equals(exchange(PAPER + "/uploads/" + fileId, HttpMethod.GET, authed(token), Map.class).getBody().get("status"))) return fileId;
            Thread.sleep(100);
        }
        throw new AssertionError("scan never finished for " + fileId);
    }

    private ResponseEntity<Map> record(Ctx c, String itemId, int qty, String documentId, boolean copy) {
        Map<String, Object> m = new HashMap<>(Map.of("prescriberName", "Dr Test Prescriber", "prescriberRegNo", "REG-" + SEQ.incrementAndGet(),
                "rxDate", today().toString(), "lines", List.of(Map.of("itemId", itemId, "qty", qty)), "duplicateCopyRetained", copy));
        if (documentId != null) m.put("documentId", documentId);
        return exchange(PAPER, HttpMethod.POST, authedJsonBody(c.token(), m), Map.class);
    }

    /** Records a paper Rx with one line for the item and returns [paperRxId, paperRxLineId]. */
    private String[] paper(Ctx c, String itemId, String documentId, boolean copy) {
        ResponseEntity<Map> r = record(c, itemId, 40, documentId, copy);
        assertThat(r.getStatusCode()).as("paper rx %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return new String[]{(String) r.getBody().get("paperRxId"), (String) ((Map<?, ?>) ((List<?>) r.getBody().get("lines")).get(0)).get("paperRxLineId")};
    }

    private static Map<String, Object> line(String code, String paperRxLineId, String ack) {
        Map<String, Object> line = new HashMap<>(Map.of("chargeCode", code, "quantity", 1));
        if (paperRxLineId != null) line.put("paperRxLineId", paperRxLineId);
        if (ack != null) line.put("scheduleAckReason", ack);
        return line;
    }

    @SafeVarargs
    private ResponseEntity<Map> sell(String token, Map<String, Object>... lines) {
        return exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(token, Map.of("lineItems", List.of(lines))), Map.class);
    }

    private static void assertProblem(ResponseEntity<Map> r, String type) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) r.getBody().get("type")).endsWith("/" + type);
    }

    private static void assertSold(ResponseEntity<Map> r) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    private static int audits(Ctx c, String action) {
        return su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?", Integer.class, c.tenant().id(), action);
    }

    private static int invoices(Ctx c) {
        return su().queryForObject("SELECT count(*) FROM invoices WHERE tenant_id = ?", Integer.class, c.tenant().id());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rules(String token) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/counter-rules", HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return (Map<String, Object>) r.getBody();
    }

    private String tokenFor(Ctx c, ModuleGrant... grants) {
        UUID role = seedRole(c.tenant().id(), "Role " + SEQ.incrementAndGet(), false, grants);
        return loginAndGetAccessToken(seedStaff(c.tenant(), role, "p23" + SEQ.incrementAndGet() + "@a.com", phone(), false));
    }

    /** Names the clinic's pharmacist in charge and gives the seller an in-date pharmacist entry in the licence register; returns the entry id. */
    private String pharmacistOnDuty(Ctx c) {
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("pharmacistInChargeStaffId", c.staff().id().toString())), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> r = exchange("/v1/setup/licences", HttpMethod.POST, authedJsonBody(c.token(), Map.of("licenceType", "pharmacist",
                "holderId", c.staff().id().toString(), "holderName", "Test Pharmacist", "number", "PH-" + SEQ.incrementAndGet(),
                "expiryDate", today().plusYears(1).toString(), "region", "IN")), Map.class);
        assertThat(r.getStatusCode()).as("licence %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    // ---- AC1: an IN_H1 line needs the second confirmation with a reason before it can be paid ------------------------------

    @Test
    void ac1_h1LineIsRefusedWithoutAConfirmationReasonAndSoldAndAuditedWithOne() {
        Ctx c = ctx();
        String[] h1 = item(c, "Counter Item H1", "IN_H1");
        String[] otc = item(c, "Counter Item Plain", "OTC");
        String rx = paper(c, h1[0], null, false)[1];

        ResponseEntity<Map> bare = sell(c.token(), line(h1[1], rx, null));
        assertProblem(bare, "schedule-ack-required");
        assertThat(bare.getBody()).containsEntry("chargeCode", h1[1]).containsEntry("scheduleClass", "IN_H1");
        assertThat((String) bare.getBody().get("detail")).contains("Schedule H1").contains("reason");
        assertProblem(sell(c.token(), line(h1[1], rx, "   ")), "schedule-ack-required");
        assertProblem(sell(c.token(), line(h1[1], rx, "ok")), "schedule-ack-required"); // boundary: two characters is not a reason
        // a confirmation is bound to its own item: one given on another line of the bill does not cover the H1 line
        assertProblem(sell(c.token(), line(otc[1], null, "Checked the prescription"), line(h1[1], rx, null)), "schedule-ack-required");
        assertThat(invoices(c)).as("nothing was sold").isZero();
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.rx_gate_blocked' " +
                "AND after->>'reason' = 'schedule-ack-required'", Integer.class, c.tenant().id())).isEqualTo(4);
        assertThat(audits(c, ACKED)).isZero();
        // the confirmation does not replace the prescription
        assertProblem(sell(c.token(), line(h1[1], null, "Checked the prescription")), "rx-required");

        ResponseEntity<Map> sale = sell(c.token(), line(h1[1], rx, "  Checked the prescription  ")); // boundary below: three characters
        assertSold(sale);
        assertSold(sell(c.token(), line(h1[1], rx, "yes")));
        Map<String, Object> row = su().queryForMap("SELECT actor_id, entity_id, after->>'reason' AS reason, after->>'scheduleClass' AS cls, " +
                "after->>'itemCode' AS code FROM audit_log WHERE tenant_id = ? AND action = ? AND after->>'invoiceId' = ?", c.tenant().id(), ACKED, sale.getBody().get("id"));
        assertThat(row).containsEntry("actor_id", c.staff().id()).containsEntry("entity_id", UUID.fromString(h1[0]))
                .containsEntry("reason", "Checked the prescription").containsEntry("cls", "IN_H1").containsEntry("code", h1[1]);
        assertThat(audits(c, ACKED)).isEqualTo(2);
    }

    @Test
    void ac1_aSellerWithoutPharmacyRightsCanStillGiveTheConfirmation() {
        Ctx c = ctx();
        String[] h1 = item(c, "Counter Item H1 Desk", "IN_H1");
        String rx = paper(c, h1[0], null, false)[1];
        String desk = tokenFor(c, fullGrant("billing")); // the full counter sale screen needs billing:create only
        assertProblem(sell(desk, line(h1[1], rx, null)), "schedule-ack-required");
        assertSold(sell(desk, line(h1[1], rx, "Checked with the pharmacist")));
        // ... but the counter rules themselves are a pharmacy read
        assertThat(exchange("/v1/pharmacy/counter-rules", HttpMethod.GET, authed(desk), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- AC2: the Schedule X duplicate copy is mandatory and kept two years from the sale ----------------------------------

    @Test
    void ac2_scheduleXCopyIsMandatoryAndRetainedTwoYearsFromTheSale() throws Exception {
        Ctx c = ctx();
        String[] x = item(c, "Counter Item X", "IN_X");
        String[] h = item(c, "Counter Item H", "IN_H");
        String[] h1 = item(c, "Counter Item H1 Copy", "IN_H1");
        String h1Rx = paper(c, h1[0], null, false)[1];
        String photo = photo(c.token());

        assertProblem(record(c, x[0], 40, null, true), "rx-copy-required"); // no photo
        assertProblem(record(c, x[0], 40, photo, false), "rx-copy-required"); // no tick
        String[] rx = paper(c, x[0], photo, true);
        String sql = "SELECT retain_until FROM paper_prescriptions WHERE tenant_id = ? AND id = ?::uuid";
        assertThat(su().queryForObject(sql, Date.class, c.tenant().id(), rx[0]).toLocalDate()).isEqualTo(today().plusYears(2));

        // a later sale against the same record moves the date to that sale + 2 years ...
        su().update("UPDATE paper_prescriptions SET retain_until = ? WHERE id = ?::uuid", Date.valueOf(today().plusYears(1)), rx[0]);
        assertSold(sell(c.token(), line(x[1], rx[1], null))); // Schedule X asks for the copy, not for the H1 confirmation
        assertThat(su().queryForObject(sql, Date.class, c.tenant().id(), rx[0]).toLocalDate()).isEqualTo(today().plusYears(2));
        // ... and never to an earlier one
        su().update("UPDATE paper_prescriptions SET retain_until = ? WHERE id = ?::uuid", Date.valueOf(today().plusYears(5)), rx[0]);
        assertSold(sell(c.token(), line(x[1], rx[1], null)));
        assertThat(su().queryForObject(sql, Date.class, c.tenant().id(), rx[0]).toLocalDate()).isEqualTo(today().plusYears(5));
        // a bill refused after the prescription check leaves the date alone (the whole bill rolls back)
        su().update("UPDATE paper_prescriptions SET retain_until = ? WHERE id = ?::uuid", Date.valueOf(today().plusYears(1)), rx[0]);
        assertProblem(sell(c.token(), line(x[1], rx[1], null), line(h1[1], h1Rx, null)), "schedule-ack-required");
        assertThat(su().queryForObject(sql, Date.class, c.tenant().id(), rx[0]).toLocalDate()).isEqualTo(today().plusYears(1));

        // a class without a retention rule keeps no date
        String[] plain = paper(c, h[0], null, false);
        assertSold(sell(c.token(), line(h[1], plain[1], null)));
        assertThat(su().queryForObject(sql, Date.class, c.tenant().id(), plain[0])).isNull();
    }

    // ---- AC3: no registered pharmacist on duty = a warning and an audit row on H1 / X sales, never a block ------------------

    @Test
    void ac3_h1AndXSalesWithoutARegisteredPharmacistAreSoldAndAudited() throws Exception {
        Ctx c = ctx();
        String[] h1 = item(c, "Counter Item H1 Duty", "IN_H1");
        String[] x = item(c, "Counter Item X Duty", "IN_X");
        String[] otc = item(c, "Counter Item Plain Duty", "OTC");
        String h1Rx = paper(c, h1[0], null, false)[1];
        String xRx = paper(c, x[0], photo(c.token()), true)[1];

        // 1. no pharmacist in charge
        assertThat(rules(c.token())).containsEntry("pharmacistOnDuty", false);
        ResponseEntity<Map> first = sell(c.token(), line(h1[1], h1Rx, "Checked the prescription"), line(otc[1], null, null));
        assertSold(first); // CEO-27: a warning, never a hard block
        Map<String, Object> row = su().queryForMap("SELECT actor_id, entity_type, entity_id, after->>'reason' AS reason, after->'lines' #>> '{}' AS lines " +
                "FROM audit_log WHERE tenant_id = ? AND action = ?", c.tenant().id(), NO_PHARMACIST);
        assertThat(row).containsEntry("actor_id", c.staff().id()).containsEntry("entity_type", "invoice")
                .containsEntry("entity_id", UUID.fromString((String) first.getBody().get("id"))).containsEntry("reason", "no_pharmacist_in_charge");
        assertThat((String) row.get("lines")).contains(h1[1]).contains("IN_H1").doesNotContain(otc[1]);

        // 2. a pharmacist in charge is named, but the seller holds no pharmacist registration
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("pharmacistInChargeStaffId", c.staff().id().toString())), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rules(c.token())).containsEntry("pharmacistOnDuty", false);
        assertSold(sell(c.token(), line(x[1], xRx, null)));
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ? AND after->>'reason' = 'seller_not_registered_pharmacist' " +
                "AND after->'lines' #>> '{}' LIKE '%IN_X%'", Integer.class, c.tenant().id(), NO_PHARMACIST)).isEqualTo(1);

        // 3. the seller is a registered pharmacist: no warning, no new row
        String licence = pharmacistOnDuty(c);
        assertThat(rules(c.token())).containsEntry("pharmacistOnDuty", true);
        assertSold(sell(c.token(), line(h1[1], h1Rx, "Checked the prescription"), line(x[1], xRx, null)));
        assertThat(audits(c, NO_PHARMACIST)).isEqualTo(2);
        // another seller of the same clinic is not covered by a colleague's registration
        String other = tokenFor(c, fullGrant("billing"), fullGrant("pharmacy"));
        assertThat(rules(other)).containsEntry("pharmacistOnDuty", false);

        // 4. boundary: the registration is good on its expiry date and lapsed the day after
        su().update("UPDATE licence_registry SET expiry_date = ? WHERE id = ?::uuid", Date.valueOf(today()), licence);
        assertThat(rules(c.token())).containsEntry("pharmacistOnDuty", true);
        su().update("UPDATE licence_registry SET expiry_date = ? WHERE id = ?::uuid", Date.valueOf(today().minusDays(1)), licence);
        assertThat(rules(c.token())).containsEntry("pharmacistOnDuty", false);
        // 5. a registration of another profession is not a pharmacist registration
        su().update("UPDATE licence_registry SET expiry_date = ?, licence_type = 'nurse' WHERE id = ?::uuid", Date.valueOf(today().plusYears(1)), licence);
        assertThat(rules(c.token())).containsEntry("pharmacistOnDuty", false);
        assertSold(sell(c.token(), line(h1[1], h1Rx, "Checked the prescription")));
        assertThat(audits(c, NO_PHARMACIST)).isEqualTo(3);
    }

    @Test
    void ac3_aRegistrationInAnotherClinicDoesNotCount() {
        Ctx a = ctx();
        Ctx b = ctx();
        pharmacistOnDuty(a);
        assertThat(rules(a.token())).containsEntry("pharmacistOnDuty", true);
        // clinic B names its own pharmacist in charge; the only pharmacist entry in the register belongs to clinic A
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(b.token(), Map.of("pharmacistInChargeStaffId", b.staff().id().toString())), Map.class);
        assertThat(rules(b.token())).containsEntry("pharmacistOnDuty", false);
    }

    // ---- AC4: an OTC item (and a plain Schedule H item) needs nothing extra ------------------------------------------------

    @Test
    void ac4_otcAndPlainScheduleHSalesNeedNoConfirmationAndRaiseNoWarning() {
        Ctx c = ctx(); // no pharmacist in charge: the warning would fire if the class asked for it
        String[] otc = item(c, "Counter Item OTC", "OTC");
        String[] h = item(c, "Counter Item H Plain", "IN_H");
        assertSold(sell(c.token(), line(otc[1], null, null)));
        assertSold(sell(c.token(), line(h[1], paper(c, h[0], null, false)[1], null)));
        assertThat(audits(c, ACKED)).isZero();
        assertThat(audits(c, NO_PHARMACIST)).isZero();
        assertThat(audits(c, "pharmacy.rx_gate_blocked")).isZero();
    }

    // ---- the rule matrix is data: one row per class and market ---------------------------------------------------------------

    @Test
    void ruleMatrix_onlyH1AsksForTheConfirmationAndOnlyH1AndXWarnAndOnlyXKeepsTheCopyTwoYears() {
        List<String> flagged = new ArrayList<>();
        su().query("SELECT market, schedule_class, dispense_ack_required, pharmacist_warning, duplicate_rx_copy, retention_years FROM pharmacy_schedule_rules " +
                        "WHERE dispense_ack_required OR pharmacist_warning OR retention_years IS NOT NULL ORDER BY schedule_class",
                rs -> { flagged.add(rs.getString(1) + " " + rs.getString(2) + " ack=" + rs.getBoolean(3) + " warn=" + rs.getBoolean(4) + " copy=" + rs.getBoolean(5) + " years=" + rs.getObject(6)); });
        assertThat(flagged).containsExactly("IN IN_H1 ack=true warn=true copy=false years=null", "IN IN_X ack=false warn=true copy=true years=2");

        Ctx c = ctx();
        Map<?, ?> rules = rules(c.token());
        assertThat((List<?>) rules.get("ackClasses")).isEqualTo(List.of("IN_H1"));
        assertThat(new java.util.HashSet<>((List<?>) rules.get("pharmacistWarningClasses"))).isEqualTo(java.util.Set.of("IN_H1", "IN_X"));
    }
}

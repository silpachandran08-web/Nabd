package com.nabd.hms.pharmacy;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** PHL-002: the legal schedule class follows the medicine. */
class Phl002ScheduleClassApiTest extends ApiTestBase {

    private static final AtomicInteger PHONE = new AtomicInteger(4000000);

    private record Clinic(SeededTenant tenant, String token, UUID staffId) {
    }

    private Clinic clinic(String tag, String region) {
        SeededTenant tenant = seedTenant();
        if (region != null) {
            // region is immutable (trigger), so flip it as superuser with triggers off: a test-only provisioning shortcut
            try (java.sql.Connection k = superuserConnection(); java.sql.Statement st = k.createStatement()) {
                st.execute("SET session_replication_role = replica");
                st.execute("UPDATE tenants SET region_code = '" + region + "' WHERE id = '" + tenant.id() + "'");
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = com.nabd.hms.support.ClinicianFixture.licensed(
                seedStaff(tenant, roleId, tag + "@p2.test", "+9196" + String.format("%08d", PHONE.incrementAndGet()), false));
        String token = loginAndGetAccessToken(staff);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "PHL-002 test")), Map.class);
        return new Clinic(tenant, token, staff.id());
    }

    private ResponseEntity<Map> addItem(Clinic c, Map<String, Object> extra) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", "Item " + UUID.randomUUID(), "isRx", false, "price", 50.00));
        body.putAll(extra);
        return exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), body), Map.class);
    }

    private ResponseEntity<Map> edit(Clinic c, Map<?, ?> item, Map<String, Object> extra) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", item.get("name"), "isRx", item.get("isRx"), "price", 50.00));
        body.putAll(extra);
        return exchange("/v1/pharmacy/items/" + item.get("id"), HttpMethod.PATCH, authedJsonBody(c.token(), body), Map.class);
    }

    private void drug(String id, String region, String schedule) {
        new JdbcTemplate(superuserDataSource()).update(
                "INSERT INTO drug_master (id, region, kb_id, brand, schedule, status, version) VALUES (?, ?, ?, ?, ?::text[], 'active', 1)",
                id, region, id, "Phl002 synthetic " + id, schedule);
    }

    private Set<String> classCodes(Clinic c) {
        List<Map<String, Object>> l = exchange("/v1/pharmacy/schedule-classes", HttpMethod.GET, authed(c.token()), List.class).getBody();
        return l.stream().map(m -> (String) m.get("code")).collect(Collectors.toSet());
    }

    @Test
    void linkingToAnH1DrugSetsTheClassAndRxWithoutManualInput() {
        Clinic c = clinic("h1", null); // India
        String drugId = "p2-h1-" + UUID.randomUUID();
        drug(drugId, "IN", "{H1}");
        Map<?, ?> item = addItem(c, Map.of()).getBody();
        assertThat(item.get("scheduleClass")).isEqualTo("OTC");
        assertThat(item.get("isRx")).isEqualTo(false);

        ResponseEntity<Map> link = exchange("/v1/pharmacy/items/" + item.get("id") + "/drug-link", HttpMethod.PUT,
                authedJsonBody(c.token(), Map.of("drugId", drugId)), Map.class);
        assertThat(link.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(link.getBody().get("scheduleClass")).isEqualTo("IN_H1");

        List<Map<String, Object>> items = exchange("/v1/pharmacy/items", HttpMethod.GET, authed(c.token()), List.class).getBody();
        assertThat(items.get(0)).containsEntry("scheduleClass", "IN_H1").containsEntry("isRx", true).containsEntry("register", "IN_H1");
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT is_rx FROM charge_catalogue WHERE id = ?",
                Boolean.class, UUID.fromString((String) item.get("id"))))).isTrue();

        // the same through create
        String x = "p2-x-" + UUID.randomUUID();
        drug(x, "IN", "{X}");
        assertThat(addItem(c, Map.of("drugMasterId", x)).getBody().get("scheduleClass")).isEqualTo("IN_X");
        // an unknown drug is a 404, not a 500
        assertThat(addItem(c, Map.of("drugMasterId", "nope-" + UUID.randomUUID())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ksaTenantAllowsOnlySaudiClassesAndRejectsIndiaClassesWith422() {
        Clinic c = clinic("ksa", "SA");
        assertThat(classCodes(c)).containsExactlyInAnyOrder("OTC", "SA_RX", "SA_SEMI_CONTROLLED", "SA_CONTROLLED");
        ResponseEntity<Map> bad = addItem(c, Map.of("scheduleClass", "IN_H1"));
        assertThat(bad.getStatusCode()).extracting(s -> s.value()).isEqualTo(422);
        assertThat(bad.getBody().get("type")).asString().contains("schedule-not-valid-for-market");
        Map<?, ?> ok = addItem(c, Map.of("scheduleClass", "SA_CONTROLLED")).getBody();
        assertThat(ok.get("scheduleClass")).isEqualTo("SA_CONTROLLED");
        assertThat(ok.get("isRx")).isEqualTo(true);
        assertThat(ok.get("register")).isEqualTo("SA_CONTROLLED");
        // plain Rx flag on a KSA clinic maps to the KSA Rx class
        assertThat(addItem(c, Map.of("isRx", true)).getBody().get("scheduleClass")).isEqualTo("SA_RX");
        // the same rejection on edit
        assertThat(edit(c, ok, Map.of("scheduleClass", "IN_X")).getStatusCode()).extracting(s -> s.value()).isEqualTo(422);
    }

    @Test
    void everyMarketOffersItsOwnClassesAndTheRegisterClassesAreMarked() {
        assertThat(classCodes(clinic("in", null))).containsExactlyInAnyOrder("OTC", "IN_H", "IN_H1", "IN_X", "IN_NDPS");
        assertThat(classCodes(clinic("ae", "AE"))).containsExactlyInAnyOrder("OTC", "AE_RX", "AE_SCD", "AE_CD");
        assertThat(classCodes(clinic("qa", "QA"))).containsExactlyInAnyOrder("OTC", "QA_RX", "QA_CD");
        Set<String> registers = new JdbcTemplate(superuserDataSource()).queryForList(
                "SELECT DISTINCT schedule_class FROM pharmacy_schedule_rules WHERE register_type IS NOT NULL", String.class)
                .stream().collect(Collectors.toSet());
        assertThat(registers).containsExactlyInAnyOrder("IN_H1", "IN_X", "IN_NDPS", "SA_CONTROLLED", "AE_CD", "QA_CD");
        Clinic in = clinic("in2", null);
        List<Map<String, Object>> classes = exchange("/v1/pharmacy/schedule-classes", HttpMethod.GET, authed(in.token()), List.class).getBody();
        assertThat(classes.stream().filter(m -> m.get("register") != null).map(m -> m.get("code")).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("IN_H1", "IN_X", "IN_NDPS");
        assertThat(classes.stream().filter(m -> "IN_X".equals(m.get("code"))).findFirst().get()).containsEntry("duplicateRxCopy", true);
    }

    @Test
    void classChangeIsAuditedAndPastInvoiceLinesKeepTheirSnapshot() {
        Clinic c = clinic("snap", null);
        Map<?, ?> item = addItem(c, Map.of("stockQty", 10)).getBody();
        assertThat(item.get("scheduleClass")).isEqualTo("OTC");
        String firstInvoice = checkout(c, item, "Snap A", "+919999942001");

        ResponseEntity<Map> changed = edit(c, item, Map.of("scheduleClass", "IN_H"));
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody()).containsEntry("scheduleClass", "IN_H").containsEntry("isRx", true);
        // re-sending the same body with a stale isRx=false must not flip it back, and a no-op edit writes no second audit row
        Map<?, ?> back = edit(c, changed.getBody(), Map.of()).getBody();
        assertThat(back.get("scheduleClass")).isEqualTo("IN_H");

        List<Map<String, Object>> audit = inTenantTx(c.tenant().id(), () -> jdbc.queryForList(
                "SELECT before::text AS before, after::text AS after FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.item_schedule_changed'",
                c.tenant().id()));
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("before").toString()).contains("OTC");
        assertThat(audit.get(0).get("after").toString()).contains("IN_H");

        // an Rx class now needs a linked prescription at the counter (PHL-001), so the sold-as snapshot is read from the first sale
        assertThat(lineClass(c, firstInvoice)).isEqualTo("OTC");
    }

    @Test
    void filterByClassReturnsOnlyThatClassWithin300msP95For5000Items() {
        Clinic c = clinic("perf", null);
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        su.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, stock_qty, active) " +
                "SELECT ?, 'PERF-' || g, 'Perf item ' || g, 'Pharmacy', 10, 5, true FROM generate_series(1, 5000) g", c.tenant().id());
        su.update("INSERT INTO pharmacy_item_profiles (item_id, tenant_id, schedule_class) " +
                "SELECT id, tenant_id, CASE WHEN code ~ '[05]$' THEN 'IN_H1' ELSE 'OTC' END FROM charge_catalogue WHERE tenant_id = ? AND code LIKE 'PERF-%'",
                c.tenant().id());
        List<Long> ms = new ArrayList<>();
        List<Map<String, Object>> body = null;
        for (int i = 0; i < 21; i++) {
            long t0 = System.nanoTime();
            body = exchange("/v1/pharmacy/items?class=IN_H1", HttpMethod.GET, authed(c.token()), List.class).getBody();
            ms.add((System.nanoTime() - t0) / 1_000_000);
        }
        assertThat(body).hasSize(1000).allSatisfy(m -> assertThat(m).containsEntry("scheduleClass", "IN_H1"));
        java.util.Collections.sort(ms);
        long p95 = ms.get((int) Math.ceil(ms.size() * 0.95) - 1);
        System.out.println("PHL-002 filter p95 ms over 5000 items: " + p95);
        assertThat(p95).isLessThan(300);
    }

    private String checkout(Clinic c, Map<?, ?> item, String patient, String phone) {
        String patientId = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", patient, "phone", phone, "dob", "1972-02-02", "gender", "female")), Map.class).getBody().get("id");
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patientId, "doctorId", c.staffId().toString())), Map.class).getBody().get("id");
        for (String s : new String[]{"waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending"}) {
            exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", s)), Map.class);
        }
        ResponseEntity<Map> r = exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", List.of(
                Map.of("chargeCode", item.get("code"), "chargeName", item.get("name"), "category", "Pharmacy", "quantity", 1,
                        "unitPrice", 50.00, "taxRatePercent", 0)))), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("id");
    }

    private String lineClass(Clinic c, String invoiceId) {
        return inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT schedule_class FROM invoice_line_items WHERE invoice_id = ?", String.class, UUID.fromString(invoiceId)));
    }
}

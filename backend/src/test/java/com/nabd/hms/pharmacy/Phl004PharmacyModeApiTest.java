package com.nabd.hms.pharmacy;

import com.nabd.hms.pharmacy.api.StockCommands;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** PHL-004: External / Hybrid / In-house is a capability gate; a mode change never touches history. */
@SuppressWarnings("unchecked")
class Phl004PharmacyModeApiTest extends ApiTestBase {

    private static final AtomicInteger PHONE = new AtomicInteger(5000000);

    @Autowired
    private RequestMappingHandlerMapping mappings;
    @Autowired
    private StockCommands stock;

    private record Clinic(SeededTenant tenant, String token, UUID staffId) {
    }

    private Clinic clinic(String tag) {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff staff = com.nabd.hms.support.ClinicianFixture.licensed(
                seedStaff(tenant, roleId, tag + "@p4.test", "+9196" + String.format("%08d", PHONE.incrementAndGet()), false));
        return new Clinic(tenant, loginAndGetAccessToken(staff), staff.id());
    }

    private ResponseEntity<Map> setMode(Clinic c, String mode) {
        return exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("mode", mode, "reason", "PHL-004 test")), Map.class);
    }

    private ResponseEntity<Map> addItem(Clinic c, String name, int qty, Map<String, Object> extra) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("name", name, "isRx", false, "price", 20.00, "stockQty", qty));
        body.putAll(extra);
        return exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), body), Map.class);
    }

    private static RequiresPharmacyMode annotation(HandlerMethod hm) {
        RequiresPharmacyMode a = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RequiresPharmacyMode.class);
        return a != null ? a : AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), RequiresPharmacyMode.class);
    }

    @Test
    void everyPharmacyEndpointDeclaresItsMinimumModeAndExternalIsRefusedOnTheGatedOnes() {
        List<String> missing = new ArrayList<>();
        List<String[]> gated = new ArrayList<>(); // {method, path} that need more than EXTERNAL
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            var patterns = e.getKey().getPathPatternsCondition();
            if (patterns == null) continue;
            for (String p : patterns.getPatternValues()) {
                if (!p.startsWith("/v1/pharmacy")) continue;
                RequiresPharmacyMode a = annotation(e.getValue());
                if (a == null) { missing.add(e.getKey() + " " + e.getValue()); continue; }
                if (a.value() != PharmacyMode.EXTERNAL) {
                    for (var m : e.getKey().getMethodsCondition().getMethods()) {
                        gated.add(new String[]{m.name(), p.replaceAll("\\{[^}]+}", UUID.randomUUID().toString()), a.value().name()});
                    }
                }
            }
        }
        assertThat(missing).as("pharmacy handlers without @RequiresPharmacyMode").isEmpty();
        assertThat(gated.size()).isGreaterThanOrEqualTo(9);

        Clinic c = clinic("matrix"); // a new clinic is External
        for (String[] g : gated) {
            ResponseEntity<String> r = exchange(g[1], HttpMethod.valueOf(g[0]), "GET".equals(g[0]) ? authed(c.token()) : authedJsonBody(c.token(), Map.of()), String.class);
            assertThat(r.getStatusCode()).as("External " + g[0] + " " + g[1]).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(r.getBody()).contains("pharmacy-mode-external");
        }
        // Hybrid and In-house: never the mode refusals (a plain 404/400 for a random id is fine)
        for (String mode : List.of("hybrid", "in_house")) {
            assertThat(setMode(c, mode).getStatusCode()).isEqualTo(HttpStatus.OK);
            for (String[] g : gated) {
                ResponseEntity<String> r = exchange(g[1], HttpMethod.valueOf(g[0]), "GET".equals(g[0]) ? authed(c.token()) : authedJsonBody(c.token(), Map.of()), String.class);
                if ("hybrid".equals(mode) && "IN_HOUSE".equals(g[2])) { // PHL-012: the worklist is In-house only
                    assertThat(r.getStatusCode()).as(mode + " " + g[0] + " " + g[1]).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(r.getBody()).contains("feature-requires-inhouse");
                    continue;
                }
                assertThat(r.getStatusCode()).as(mode + " " + g[0] + " " + g[1]).isNotIn(HttpStatus.FORBIDDEN, HttpStatus.CONFLICT);
            }
        }
    }

    @Test
    void capabilitiesFollowTheMode() {
        Clinic c = clinic("caps");
        assertThat(exchange("/v1/pharmacy/capabilities", HttpMethod.GET, authed(c.token()), Map.class).getBody())
                .containsEntry("mode", "external").containsEntry("features", List.of());
        setMode(c, "hybrid");
        assertThat(exchange("/v1/pharmacy/capabilities", HttpMethod.GET, authed(c.token()), Map.class).getBody())
                .containsEntry("mode", "hybrid").containsEntry("features", List.of("items"));
        setMode(c, "in_house");
        Map<?, ?> inh = exchange("/v1/pharmacy/capabilities", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat((List<String>) inh.get("features")).containsExactly("items", "batches", "worklist", "registers", "procurement", "offline");
        // a mode change is effective for the very next request on this replica
        setMode(c, "external");
        assertThat(exchange("/v1/pharmacy/items", HttpMethod.GET, authed(c.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anotherReplicasChangeReachesTheGuardWithinFiveSeconds() throws Exception {
        Clinic c = clinic("ttl");
        setMode(c, "hybrid");
        assertThat(exchange("/v1/pharmacy/items", HttpMethod.GET, authed(c.token()), List.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        // written behind this replica's back (as another replica would)
        new JdbcTemplate(superuserDataSource()).update("UPDATE pharmacy_settings SET mode = 'external' WHERE tenant_id = ?", c.tenant().id());
        Thread.sleep(PharmacyModes.TTL_MILLIS + 300);
        assertThat(exchange("/v1/pharmacy/items", HttpMethod.GET, authed(c.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void inHouseNeedsThePlanEntitlementAndTheRefusalNamesThePlanThatUnlocksIt() {
        String op = platformLoginAndGetAccessToken(seedOperator("sa-" + UUID.randomUUID() + "@nabd.internal", "super_admin", false));
        UUID without = plan(op, "NoInhouse-" + UUID.randomUUID(), List.of("billing", "pharmacy_external"));
        UUID with = plan(op, "A-Inhouse-" + UUID.randomUUID(), List.of("billing", "pharmacy_external", "pharmacy_inhouse"));
        Clinic c = clinic("ent");
        subscribe(op, c.tenant().id(), without);

        ResponseEntity<Map> refused = setMode(c, "in_house");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(refused.getBody().get("type")).asString().contains("entitlement-required");
        String planName = (String) refused.getBody().get("plan");
        assertThat(planName).isNotBlank();
        assertThat(refused.getBody().get("detail")).asString().contains(planName);
        assertThat(new JdbcTemplate(superuserDataSource()).queryForList("SELECT p.name FROM master.plans p JOIN master.plan_entitlements e " +
                "ON e.plan_id = p.id AND e.plan_version = p.version WHERE p.active AND e.module_code = 'pharmacy_inhouse'", String.class)).contains(planName);
        assertThat(exchange("/v1/pharmacy/capabilities", HttpMethod.GET, authed(c.token()), Map.class).getBody()).containsEntry("mode", "external");

        subscribe(op, c.tenant().id(), with);
        assertThat(setMode(c, "in_house").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void switchingToExternalAndBackKeepsItemsStockAndDispenseRecords() {
        Clinic c = clinic("roundtrip");
        setMode(c, "hybrid");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add(UUID.fromString((String) addItem(c, "Round " + i, 30, Map.of()).getBody().get("id")));
        }
        for (int i = 0; i < 12; i++) { // 12 dispenses
            int k = i;
            inTenantTx(c.tenant().id(), () -> stock.issue(ids.get(k), 2, null, "dispense " + k));
        }
        List<Map<String, Object>> before = exchange("/v1/pharmacy/items", HttpMethod.GET, authed(c.token()), List.class).getBody();
        int movementsBefore = movements(c);

        assertThat(setMode(c, "external").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/pharmacy/items", HttpMethod.GET, authed(c.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(setMode(c, "hybrid").getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> after = exchange("/v1/pharmacy/items", HttpMethod.GET, authed(c.token()), List.class).getBody();
        assertThat(after).hasSize(40).isEqualTo(before);
        assertThat(movements(c)).isEqualTo(movementsBefore).isEqualTo(40 + 12);
    }

    private int movements(Clinic c) {
        return inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM pharmacy_stock_movements", Integer.class));
    }

    @Test
    void anItemBoughtOutsideIsListedForTheRxAndNeverOfferedAtCheckout() {
        Clinic c = clinic("outside");
        setMode(c, "hybrid");
        Map<?, ?> here = addItem(c, "Dispensed Here", 5, Map.of()).getBody();
        Map<?, ?> away = addItem(c, "Buy Elsewhere", 5, Map.of("inHouse", false)).getBody();
        assertThat(away.get("inHouse")).isEqualTo(false);
        assertThat((List<String>) exchange("/v1/pharmacy/buy-outside", HttpMethod.GET, authed(c.token()), List.class).getBody()).containsExactly("buy elsewhere");

        String patientId = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", "Outside Patient", "phone", "+919999943001", "dob", "1975-05-05", "gender", "male")), Map.class).getBody().get("id");
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patientId, "doctorId", c.staffId().toString())), Map.class).getBody().get("id");
        for (String s : new String[]{"waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending"}) {
            exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("status", s)), Map.class);
        }
        List<?> charges = (List<?>) exchange("/v1/billing/checkout/" + entry, HttpMethod.GET, authed(c.token()), Map.class).getBody().get("charges");
        assertThat(charges.stream().anyMatch(x -> here.get("code").equals(((Map<?, ?>) x).get("code")))).isTrue();
        assertThat(charges.stream().anyMatch(x -> away.get("code").equals(((Map<?, ?>) x).get("code")))).isFalse();

        // flipping it back puts it on the counter again
        Map<String, Object> flip = new java.util.HashMap<>(Map.of("name", away.get("name"), "isRx", false, "price", 20.00, "inHouse", true));
        exchange("/v1/pharmacy/items/" + away.get("id"), HttpMethod.PATCH, authedJsonBody(c.token(), flip), Map.class);
        assertThat((List<String>) exchange("/v1/pharmacy/buy-outside", HttpMethod.GET, authed(c.token()), List.class).getBody()).isEmpty();
    }

    @Test
    void settingsValidationAuditAndExtras() {
        Clinic c = clinic("settings");
        ResponseEntity<Map> bad = setMode(c, "nonsense");
        assertThat(bad.getStatusCode()).extracting(s -> s.value()).isEqualTo(422);
        assertThat(bad.getBody().get("type")).asString().contains("invalid-mode");
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH,
                authedJsonBody(c.token(), Map.of("mode", "hybrid")), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST); // reason missing

        ResponseEntity<Map> ghost = exchange("/v1/pharmacy/settings", HttpMethod.PATCH,
                authedJsonBody(c.token(), Map.of("alertRecipientStaffId", UUID.randomUUID().toString())), Map.class);
        assertThat(ghost.getStatusCode()).extracting(s -> s.value()).isEqualTo(422);

        ResponseEntity<Map> ok = exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of(
                "mode", "hybrid", "reason", "Opening counter", "alertRecipientStaffId", c.staffId().toString(),
                "pharmacistInChargeStaffId", c.staffId().toString(), "licenceNumbers", Map.of("IN_20B", "SYN-20B-1"))), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("mode", "hybrid").containsEntry("alertRecipientStaffId", c.staffId().toString());
        assertThat(ok.getBody().get("modeChangedAt")).isNotNull();
        assertThat((Map<String, String>) ok.getBody().get("licenceNumbers")).containsEntry("IN_20B", "SYN-20B-1");

        Map<String, Object> row = inTenantTx(c.tenant().id(), () -> jdbc.queryForMap(
                "SELECT mode_changed_by, mode_changed_at FROM pharmacy_settings WHERE tenant_id = ?", c.tenant().id()));
        assertThat(row.get("mode_changed_by")).hasToString(c.staffId().toString());
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.mode_changed'", Integer.class, c.tenant().id()))).isEqualTo(1);
        // extras alone keep the mode and do not add a mode audit row
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("licenceNumbers", Map.of("IN_21B", "SYN-21B-1"))), Map.class);
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.mode_changed'", Integer.class, c.tenant().id()))).isEqualTo(1);
    }

    private UUID plan(String token, String name, List<String> modules) {
        ResponseEntity<Map> r = exchange("/v1/platform/plans", HttpMethod.POST, authedJsonBody(token, Map.of(
                "code", "p-" + UUID.randomUUID(), "name", name, "monthlyPriceCents", 1, "currency", "INR",
                "active", true, "modules", modules, "limits", Map.of("seats", 5, "branches", 2))), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) r.getBody().get("id"));
    }

    private void subscribe(String token, UUID tenant, UUID plan) {
        ResponseEntity<Map> r = exchange("/v1/platform/billing/subscriptions/" + tenant, HttpMethod.POST, authedJsonBody(token,
                Map.of("planId", plan.toString(), "mrrCents", 100000, "renewalDate", LocalDate.now().plusDays(30).toString())), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}

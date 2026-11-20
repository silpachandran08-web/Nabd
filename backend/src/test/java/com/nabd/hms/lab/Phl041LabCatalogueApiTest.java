package com.nabd.hms.lab;

import com.nabd.hms.common.GrantsFlattener;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.platform.entitlements.PlanEntitlements;
import com.nabd.hms.staff.presets.RolePresets;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** PHL-041: lab catalogue behind the lab entitlement, starter pack import, panels, price change log, lab permissions. */
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl041LabCatalogueApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000) + 100_000);

    @Autowired
    private PlanEntitlements planEntitlements;
    @Autowired
    private RequestMappingHandlerMapping mappings;

    private record Clinic(SeededTenant t, SeededStaff manager, String token) {
    }

    private String phone() {
        return "+9193" + (10000000 + SEQ.incrementAndGet());
    }

    /** The clinic's Owner with exactly these modules: lab, billing, pharmacy (counter-sale mode) and setup (price change list). */
    private Clinic clinic() {
        SeededTenant t = seedTenant();
        UUID role = seedRole(t.id(), "Owner", true, fullGrant("lab"), fullGrant("billing"), fullGrant("pharmacy"), fullGrant("setup"));
        SeededStaff m = seedStaff(t, role, "m" + SEQ.incrementAndGet() + "@p41.test", phone(), false);
        return new Clinic(t, m, loginAndGetAccessToken(m));
    }

    private String tokenWith(SeededTenant t, String roleName, List<ModuleGrant> grants) {
        UUID role = seedRole(t.id(), roleName + " " + SEQ.incrementAndGet(), false, grants.toArray(ModuleGrant[]::new));
        return loginAndGetAccessToken(seedStaff(t, role, "s" + SEQ.incrementAndGet() + "@p41.test", phone(), false));
    }

    private List<String> permissions(String token) throws Exception {
        return (List<String>) objectMapper.readValue(Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class).get("permissions");
    }

    /** The clinic's plan now lists only these modules (a clinic with no subscription is unrestricted, PLT-059). */
    private void subscribe(UUID tenant, List<String> modules) {
        String op = platformLoginAndGetAccessToken(seedOperator("sa-" + UUID.randomUUID() + "@nabd.internal", "super_admin", false));
        ResponseEntity<Map> p = exchange("/v1/platform/plans", HttpMethod.POST, authedJsonBody(op, Map.of(
                "code", "p41-" + UUID.randomUUID(), "name", "P", "monthlyPriceCents", 100000, "currency", "INR",
                "active", true, "modules", modules, "limits", Map.of("seats", 20, "branches", 2))), Map.class);
        assertThat(p.getStatusCode()).as("%s", p.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> s = exchange("/v1/platform/billing/subscriptions/" + tenant, HttpMethod.POST, authedJsonBody(op,
                Map.of("planId", p.getBody().get("id"), "mrrCents", 100000, "renewalDate", LocalDate.now().plusDays(30).toString())), Map.class);
        assertThat(s.getStatusCode()).as("%s", s.getBody()).isEqualTo(HttpStatus.OK);
        planEntitlements.evict(tenant);
    }

    private static Map<String, Object> testBody(String code, double price) {
        return new HashMap<>(Map.of("code", code, "name", "Test " + code, "loinc", "2345-7", "sampleType", "serum", "container", "sst_gold",
                "volumeMl", 3, "unit", "mg/dL", "tatMinutes", 240, "price", price));
    }

    private ResponseEntity<Map> createTest(String token, Map<String, Object> body) {
        return exchange("/v1/lab/tests", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private String testId(String token, String code, double price) {
        ResponseEntity<Map> r = createTest(token, testBody(code, price));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private List<Map<String, Object>> allTests(String token) {
        List<Map<String, Object>> out = new ArrayList<>();
        String cursor = null;
        do {
            Map body = exchange("/v1/lab/tests?limit=60" + (cursor == null ? "" : "&cursor=" + cursor), HttpMethod.GET, authed(token), Map.class).getBody();
            out.addAll((List<Map<String, Object>>) body.get("items"));
            cursor = (String) body.get("nextCursor");
        } while (cursor != null);
        return out;
    }

    private ResponseEntity<Map> quote(String token, String chargeCode) {
        return exchange("/v1/billing/quotes", HttpMethod.POST, authedJsonBody(token,
                Map.of("lines", List.of(Map.of("chargeCode", chargeCode, "quantity", 1)))), Map.class);
    }

    private static long subtotalMinor(ResponseEntity<Map> q) {
        return ((Number) ((Map<?, ?>) q.getBody().get("totals")).get("subtotalMinor")).longValue();
    }

    private int count(UUID tenant, String sql, Object... args) {
        return inTenantTx(tenant, () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    // ---- AC1: without the entitlement every /v1/lab endpoint is 403 module-not-entitled ----
    @Test
    void ac1_withoutTheLabEntitlementEveryLabEndpointIsRefused_andWithItTheyWork() {
        Clinic c = clinic();
        String id = testId(c.token(), "GLU", 100); // unrestricted so far: no subscription
        subscribe(c.t().id(), List.of("billing"));

        int checked = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            var patterns = e.getKey().getPathPatternsCondition();
            if (patterns == null) continue;
            for (String p : patterns.getPatternValues()) {
                if (!p.startsWith("/v1/lab")) continue;
                for (var m : e.getKey().getMethodsCondition().getMethods()) {
                    String path = p.replaceAll("\\{[^}]+}", id);
                    HttpMethod method = HttpMethod.valueOf(m.name());
                    ResponseEntity<Map> r = exchange(path, method,
                            method == HttpMethod.GET ? authed(c.token()) : authedJsonBody(c.token(), testBody("X" + checked, 1)), Map.class);
                    assertThat(r.getStatusCode()).as(m + " " + p + " " + r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(String.valueOf(r.getBody().get("type"))).as(m + " " + p).endsWith("/module-not-entitled");
                    assertThat(r.getBody().get("code")).isEqualTo("MODULE_NOT_ENTITLED");
                    checked++;
                }
            }
        }
        assertThat(checked).as("lab operations found").isGreaterThanOrEqualTo(6);
        assertThat(count(c.t().id(), "SELECT count(*) FROM lab_tests WHERE tenant_id = ?", c.t().id())).as("nothing was written").isEqualTo(1);
        // the navigation's source says the module is off
        Map ent = exchange("/v1/me/entitlements", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat(((List<Map<String, Object>>) ent.get("modules")).stream().filter(x -> "lab".equals(x.get("code"))).findFirst().orElseThrow().get("enabled"))
                .isEqualTo(false);

        Clinic with = clinic();
        subscribe(with.t().id(), List.of("billing", "lab"));
        assertThat(exchange("/v1/lab/tests", HttpMethod.GET, authed(with.token()), Map.class).getStatusCode()).as("a plan with lab").isEqualTo(HttpStatus.OK);
    }

    // ---- AC2: starter pack: 150 tests, each with LOINC, sample type, container, unit, TAT and its own price-list head ----
    @Test
    void ac2_starterPackDryRunThenImportCreates150TestsEachLinkedToAChargeHead() {
        Clinic c = clinic();
        UUID t = c.t().id();
        ResponseEntity<Map> dry = exchange("/v1/lab/catalogue/imports", HttpMethod.POST, authedJsonBody(c.token(), Map.of("pack", "IN_STARTER", "dryRun", true)), Map.class);
        assertThat(dry.getStatusCode()).as("%s", dry.getBody()).isEqualTo(HttpStatus.OK);
        assertThat((List<String>) dry.getBody().get("toCreate")).hasSize(150);
        assertThat(dry.getBody().get("created")).isEqualTo(0);
        assertThat(dry.getBody().get("reviewed")).as("the pack awaits a lab reviewer").isEqualTo(false);
        assertThat(count(t, "SELECT count(*) FROM lab_tests WHERE tenant_id = ?", t)).as("a dry run stores nothing").isZero();

        testId(c.token(), "HB", 120); // the clinic already has one of the pack's codes: kept, not overwritten
        long start = System.nanoTime();
        ResponseEntity<Map> done = exchange("/v1/lab/catalogue/imports", HttpMethod.POST, authedJsonBody(c.token(), Map.of("pack", "IN_STARTER", "dryRun", false)), Map.class);
        long seconds = (System.nanoTime() - start) / 1_000_000_000L;
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("created")).isEqualTo(149);
        assertThat((List<String>) done.getBody().get("skipped")).containsExactly("HB");
        assertThat(seconds).as("import time (s)").isLessThan(60);

        List<Map<String, Object>> tests = allTests(c.token());
        assertThat(tests).hasSize(150);
        assertThat(tests).allSatisfy(x -> {
            assertThat((String) x.get("loinc")).matches("[0-9]+-[0-9]");
            assertThat((String) x.get("sampleType")).isNotBlank();
            assertThat((String) x.get("container")).isNotBlank();
            assertThat((String) x.get("unit")).isNotBlank();
            assertThat(((Number) x.get("tatMinutes")).intValue()).isPositive();
            assertThat(x.get("chargeItemId")).isNotNull();
            assertThat((String) x.get("chargeCode")).isEqualTo("LAB-" + x.get("code"));
        });
        assertThat(count(t, "SELECT count(*) FROM lab_tests l JOIN charge_catalogue c ON c.id = l.charge_item_id AND c.tenant_id = l.tenant_id "
                + "WHERE l.tenant_id = ? AND c.category = 'Lab'", t)).as("every test has its own price-list head").isEqualTo(150);
        assertThat(count(t, "SELECT count(DISTINCT charge_item_id) FROM lab_tests WHERE tenant_id = ?", t)).isEqualTo(150);
        // imported tests carry no price: they and their heads stay inactive (not billable) until the lab prices them
        assertThat(count(t, "SELECT count(*) FROM lab_tests l JOIN charge_catalogue c ON c.id = l.charge_item_id WHERE l.tenant_id = ? "
                + "AND NOT l.active AND NOT c.active AND c.base_amount = 0", t)).isEqualTo(149);
        assertThat(((Number) tests.stream().filter(x -> "HB".equals(x.get("code"))).findFirst().orElseThrow().get("price")).doubleValue()).isEqualTo(120.0);
        assertThat(count(t, "SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'lab.catalogue.changed.v1'", t)).isEqualTo(150);
        assertThat(count(t, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'lab.catalogue_imported'", t)).isEqualTo(1);

        ResponseEntity<Map> again = exchange("/v1/lab/catalogue/imports", HttpMethod.POST, authedJsonBody(c.token(), Map.of("pack", "IN_STARTER", "dryRun", false)), Map.class);
        assertThat(again.getBody().get("created")).isEqualTo(0);
        assertThat((List<String>) again.getBody().get("skipped")).hasSize(150);
        assertThat(count(t, "SELECT count(*) FROM lab_tests WHERE tenant_id = ?", t)).isEqualTo(150);

        assertThat(exchange("/v1/lab/catalogue/imports", HttpMethod.POST, authedJsonBody(c.token(), Map.of("pack", "GCC_STARTER", "dryRun", true)), Map.class)
                .getStatusCode()).as("no GCC pack is shipped").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/lab/catalogue/imports", HttpMethod.POST, authedJsonBody(c.token(), Map.of("pack", "../x", "dryRun", true)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // search and activation: a price makes an imported test billable
        Map found = exchange("/v1/lab/tests?q=hba1", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat((List<Map<String, Object>>) found.get("items")).extracting(x -> x.get("code")).containsExactly("HBA1C");
        Map byLoinc = exchange("/v1/lab/tests?q=4548-4", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat((List<Map<String, Object>>) byLoinc.get("items")).extracting(x -> x.get("code")).containsExactly("HBA1C");
        String tsh = (String) tests.stream().filter(x -> "TSH".equals(x.get("code"))).findFirst().orElseThrow().get("id");
        ResponseEntity<Map> priced = exchange("/v1/lab/tests/" + tsh, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("price", 350, "active", true)), Map.class);
        assertThat(priced.getStatusCode()).as("%s", priced.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(priced.getBody().get("active")).isEqualTo(true);
        assertThat(subtotalMinor(quote(c.token(), "LAB-TSH"))).isEqualTo(35000);
    }

    // ---- AC3: a panel of five tests is one price-list head at the panel price ----
    @Test
    void ac3_aPanelOfFiveTestsListsItsTestsAndBillsOnePanelPrice() {
        Clinic c = clinic();
        List<String> ids = new ArrayList<>();
        double[] prices = {100, 150, 200, 250, 300};
        String[] codes = {"CHOL", "TG", "HDL", "LDL", "VLDL"};
        for (int i = 0; i < 5; i++) ids.add(testId(c.token(), codes[i], prices[i]));

        ResponseEntity<Map> p = exchange("/v1/lab/panels", HttpMethod.POST, authedJsonBody(c.token(),
                Map.of("code", "lipid", "name", "Lipid profile", "testIds", ids, "price", 800)), Map.class);
        assertThat(p.getStatusCode()).as("%s", p.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(p.getBody().get("code")).isEqualTo("LIPID");
        assertThat(p.getBody().get("chargeCode")).isEqualTo("LABP-LIPID");
        assertThat((List<Map<String, Object>>) p.getBody().get("tests")).extracting(x -> x.get("code")).as("the five tests, in the order given")
                .containsExactly("CHOL", "TG", "HDL", "LDL", "VLDL");
        assertThat(((Number) p.getBody().get("price")).doubleValue()).isEqualTo(800.0);
        assertThat(((Number) p.getBody().get("testsTotal")).doubleValue()).isEqualTo(1000.0);
        assertThat(((Number) p.getBody().get("discount")).doubleValue()).isEqualTo(200.0);

        // billing the panel is ONE line at the panel price, not five test lines
        ResponseEntity<Map> q = quote(c.token(), "LABP-LIPID");
        assertThat(q.getStatusCode()).as("%s", q.getBody()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) q.getBody().get("lines")).hasSize(1);
        assertThat(subtotalMinor(q)).isEqualTo(80000);

        Map list = exchange("/v1/lab/panels", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat((List<Map<String, Object>>) list.get("items")).hasSize(1);
        assertThat(count(c.t().id(), "SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'lab.catalogue.changed.v1' "
                + "AND payload->>'panelId' IS NOT NULL", c.t().id())).isEqualTo(1);

        // refusals: same code, a test twice, an unknown test, another clinic's test
        assertThat(exchange("/v1/lab/panels", HttpMethod.POST, authedJsonBody(c.token(), Map.of("code", "LIPID", "name", "Again", "testIds", ids, "price", 1)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exchange("/v1/lab/panels", HttpMethod.POST, authedJsonBody(c.token(), Map.of("code", "DUP", "name", "Dup", "testIds", List.of(ids.get(0), ids.get(0)), "price", 1)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/lab/panels", HttpMethod.POST, authedJsonBody(c.token(), Map.of("code", "UNK", "name", "Unk", "testIds", List.of(UUID.randomUUID().toString()), "price", 1)), Map.class)
                .getStatusCode().value()).isEqualTo(422);
        Clinic other = clinic();
        String foreign = testId(other.token(), "CHOL", 90);
        ResponseEntity<Map> cross = exchange("/v1/lab/panels", HttpMethod.POST, authedJsonBody(c.token(), Map.of("code", "CROSS", "name", "Cross", "testIds", List.of(foreign), "price", 1)), Map.class);
        assertThat(cross.getStatusCode().value()).as("another clinic's test").isEqualTo(422);
        assertThat(String.valueOf(cross.getBody().get("type"))).endsWith("/unknown-test");
    }

    // ---- AC4: a price change reaches the price-list head with its change log; an issued bill keeps its price ----
    @Test
    void ac4_aPriceChangeIsLoggedOnThePriceListAndAnIssuedBillKeepsItsPrice() {
        Clinic c = clinic();
        UUID t = c.t().id();
        String id = testId(c.token(), "CRP", 500);
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("mode", "hybrid", "reason", "PHL-041 test")), Map.class)
                .getStatusCode().is2xxSuccessful()).as("counter sales on").isTrue();
        ResponseEntity<Map> bill = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(c.token(),
                Map.of("lineItems", List.of(Map.of("chargeCode", "LAB-CRP", "quantity", 1)))), Map.class);
        assertThat(bill.getStatusCode()).as("%s", bill.getBody()).isEqualTo(HttpStatus.OK);
        String invoice = (String) bill.getBody().get("id");

        ResponseEntity<Map> changed = exchange("/v1/lab/tests/" + id, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("price", 650)), Map.class);
        assertThat(changed.getStatusCode()).as("%s", changed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) changed.getBody().get("price")).doubleValue()).isEqualTo(650.0);
        assertThat(changed.getBody().get("name")).as("a partial update keeps the other fields").isEqualTo("Test CRP");
        assertThat(changed.getBody().get("tatMinutes")).isEqualTo(240);

        // the price-list head changed, with one change-log row naming the actor (FIN-016) and one audit row
        Map<String, Object> log = inTenantTx(t, () -> jdbc.queryForMap("SELECT p.field, p.old_value, p.new_value, p.actor_id, c.base_amount "
                + "FROM charge_price_changes p JOIN charge_catalogue c ON c.id = p.charge_id WHERE p.tenant_id = ? AND c.code = 'LAB-CRP'", t));
        assertThat(log.get("field")).isEqualTo("base_amount");
        assertThat(log.get("old_value")).isEqualTo("500.00");
        assertThat(log.get("new_value")).isEqualTo("650.00");
        assertThat(log.get("actor_id")).isEqualTo(c.manager().id());
        assertThat(((Number) log.get("base_amount")).doubleValue()).isEqualTo(650.0);
        Map<String, Object> audit = inTenantTx(t, () -> jdbc.queryForMap("SELECT actor_id, before::text AS b, after::text AS a FROM audit_log "
                + "WHERE tenant_id = ? AND action = 'lab.test_updated' AND entity_id = ?", t, UUID.fromString(id)));
        assertThat(audit.get("actor_id")).isEqualTo(c.manager().id());
        assertThat((String) audit.get("b")).contains("500");
        assertThat((String) audit.get("a")).contains("650");
        // the owner's price change list (Setup) shows the same entry
        Map page = exchange("/v1/setup/charges/changes", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat((List<Map<String, Object>>) page.get("items")).anySatisfy(x -> {
            assertThat(x.get("oldValue")).isEqualTo("500.00");
            assertThat(x.get("newValue")).isEqualTo("650.00");
        });

        // the bill issued before the change keeps 500; the next one is 650
        Map inv = exchange("/v1/billing/invoices/" + invoice, HttpMethod.GET, authed(c.token()), Map.class).getBody();
        Map<String, Object> line = ((List<Map<String, Object>>) inv.get("lineItems")).get(0);
        assertThat(((Number) line.get("unitPrice")).doubleValue()).isEqualTo(500.0);
        assertThat(subtotalMinor(quote(c.token(), "LAB-CRP"))).isEqualTo(65000);

        // a change that is not a price change writes no price-log row; deactivating takes the head off the price list
        exchange("/v1/lab/tests/" + id, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("tatMinutes", 120)), Map.class);
        assertThat(count(t, "SELECT count(*) FROM charge_price_changes WHERE tenant_id = ?", t)).isEqualTo(1);
        assertThat(exchange("/v1/lab/tests/" + id, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("active", false)), Map.class).getBody().get("active")).isEqualTo(false);
        assertThat(count(t, "SELECT count(*) FROM charge_catalogue WHERE tenant_id = ? AND code = 'LAB-CRP' AND NOT active", t)).isEqualTo(1);
        assertThat(exchange("/v1/lab/tests/" + UUID.randomUUID(), HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("price", 1)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- AC5: Lab technician has lab:collect and lab:result, not lab:validate ----
    @Test
    void ac5_labTechnicianCollectsAndEntersResultsButCannotValidateOrChangeTheCatalogue() throws Exception {
        Clinic c = clinic();
        RolePresets.Preset tech = RolePresets.byCode("lab_technician").orElseThrow();
        assertThat(GrantsFlattener.flatten(tech.grants())).contains("lab:view", "lab:collect", "lab:result")
                .doesNotContain("lab:validate", "lab:release", "lab:catalogue", "lab:qc", "lab:order");
        String token = tokenWith(c.t(), "Lab technician", tech.grants());
        assertThat(permissions(token)).contains("lab:view", "lab:collect", "lab:result", "patients:view")
                .doesNotContain("lab:validate", "lab:release", "lab:catalogue", "lab:qc", "lab:order", "lab:approve");

        testId(c.token(), "ALT", 80);
        assertThat(exchange("/v1/lab/tests", HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/lab/panels", HttpMethod.GET, authed(token), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(createTest(token, testBody("AST", 80)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/lab/catalogue/imports", HttpMethod.POST, authedJsonBody(token, Map.of("pack", "IN_STARTER", "dryRun", false)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(count(c.t().id(), "SELECT count(*) FROM lab_tests WHERE tenant_id = ?", c.t().id())).isEqualTo(1);

        // doctors order, owners manage; a receptionist has no lab access at all
        assertThat(GrantsFlattener.flatten(RolePresets.byCode("doctor").orElseThrow().grants())).contains("lab:view", "lab:order")
                .doesNotContain("lab:catalogue", "lab:validate", "lab:collect");
        assertThat(GrantsFlattener.flatten(RolePresets.byCode("owner").orElseThrow().grants()))
                .contains("lab:view", "lab:catalogue", "lab:validate", "lab:release", "lab:qc", "lab:collect", "lab:result", "lab:order");
        String desk = tokenWith(c.t(), "Receptionist", RolePresets.byCode("receptionist").orElseThrow().grants());
        assertThat(permissions(desk)).noneMatch(p -> p.startsWith("lab:"));
        assertThat(exchange("/v1/lab/tests", HttpMethod.GET, authed(desk), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void createValidatesRefusesDuplicatesAndStaysInsideTheClinic() {
        Clinic a = clinic(), b = clinic();
        ResponseEntity<Map> made = createTest(a.token(), testBody("glu-f", 60));
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(made.getBody().get("code")).as("codes are stored upper-case").isEqualTo("GLU-F");
        assertThat(made.getBody().get("chargeCode")).isEqualTo("LAB-GLU-F");
        assertThat(made.getBody().get("active")).isEqualTo(true);

        ResponseEntity<Map> dup = createTest(a.token(), testBody("GLU-F", 61));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(dup.getBody().get("type"))).endsWith("/duplicate-code");
        // the price list already uses the code this test would take
        inTenantTx(a.t().id(), () -> jdbc.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, tax_rate_percent) "
                + "VALUES (?, 'LAB-TAKEN', 'Old head', 'Service', 10, 0)", a.t().id()));
        assertThat(createTest(a.token(), testBody("TAKEN", 5)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(count(a.t().id(), "SELECT count(*) FROM lab_tests WHERE tenant_id = ?", a.t().id())).isEqualTo(1);

        Map<String, Object> bad = testBody("BAD", 5);
        bad.put("loinc", "not-a-loinc");
        assertThat(createTest(a.token(), bad).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> negative = testBody("NEG", -1);
        assertThat(createTest(a.token(), negative).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> partner = testBody("OUT", 5);
        partner.put("outsourced", Map.of("partnerLabId", UUID.randomUUID().toString()));
        assertThat(createTest(a.token(), partner).getStatusCode().value()).as("no partner-lab register to check the id against").isEqualTo(422);

        // the other clinic sees nothing of it, may reuse the code, and cannot change it
        assertThat((List<?>) exchange("/v1/lab/tests", HttpMethod.GET, authed(b.token()), Map.class).getBody().get("items")).isEmpty();
        assertThat(createTest(b.token(), testBody("GLU-F", 70)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(exchange("/v1/lab/tests/" + made.getBody().get("id"), HttpMethod.PATCH, authedJsonBody(b.token(), Map.of("price", 1)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(((Number) allTests(a.token()).get(0).get("price")).doubleValue()).isEqualTo(60.0);
        assertThat(exchange("/v1/lab/tests?cursor=%25%25", HttpMethod.GET, authed(a.token()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

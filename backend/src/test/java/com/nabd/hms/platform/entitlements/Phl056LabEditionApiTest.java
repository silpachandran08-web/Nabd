package com.nabd.hms.platform.entitlements;

import com.nabd.hms.staff.presets.RolePresets;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHL-056: a lab-only tenant. Provisioned with edition 'lab' (facility type, roles, the lab plan with the lab and billing modules
 * only), refused with 403 MODULE_NOT_ENTITLED on every clinical, scheduling and pharmacy endpoint, and the edition matrix: what each
 * edition blocks, over every mapped /v1 endpoint. The pharmacy edition's rule is compared with the pre-PHL-056 rule, endpoint by endpoint.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl056LabEditionApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000) + 100_000);

    @Autowired
    private PlanEntitlements planEntitlements;
    @Autowired
    private RequestMappingHandlerMapping mappings;

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private String phone() {
        return "+9194" + (10000000 + SEQ.incrementAndGet());
    }

    /** A seeded tenant of this facility type with an Owner who holds every module, lab and pharmacy included. */
    private String ownerOf(String facilityType) {
        SeededTenant t = seedTenant();
        su().update("UPDATE tenants SET facility_type = ? WHERE id = ?", facilityType, t.id());
        planEntitlements.evict(t.id());
        UUID role = seedRole(t.id(), "Owner", true, fullGrant("staff"), fullGrant("patients"), fullGrant("queue"), fullGrant("setup"),
                fullGrant("clinical"), fullGrant("billing"), fullGrant("reports"), fullGrant("pharmacy"), fullGrant("packages"),
                fullGrant("nursing"), fullGrant("departments"), fullGrant("lab"));
        return loginAndGetAccessToken(seedStaff(t, role, "o" + SEQ.incrementAndGet() + "@p56.test", phone(), false));
    }

    private static boolean refused(ResponseEntity<Map> r) {
        return r.getStatusCode().value() == 403 && String.valueOf(r.getBody()).contains("MODULE_NOT_ENTITLED");
    }

    private ResponseEntity<Map> get(String token, String path) {
        return exchange(path, HttpMethod.GET, authed(token), Map.class);
    }

    // ---- AC1: lab-only signup: facility type, roles, plan with lab and billing only ----
    @Test
    void provisioningALabEditionGivesFacilityTypeLabRolesAndOnlyTheLabAndBillingModules() {
        String op = platformLoginAndGetAccessToken(seedOperator("super-" + UUID.randomUUID() + "@nabd.health", "super_admin", false));
        String slug = "lab-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> created = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(op, Map.of(
                "tenantSlug", slug, "tenantName", "Diagnostics " + slug, "region", "IN", "ownerEmail", "owner-" + slug + "@nabd.health",
                "ownerName", "Test Owner", "ownerMobile", "+9198764" + String.format("%05d", Math.abs(slug.hashCode() % 100000)),
                "brandName", "Brand " + slug, "path", "self_serve", "edition", "lab")), Map.class);
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("edition")).isEqualTo("lab");
        Map<String, Object> body = null;
        for (int i = 0; i < 6; i++) {
            body = exchange("/v1/platform/provisioning-jobs/" + created.getBody().get("id") + "/advance", HttpMethod.POST, authed(op), Map.class).getBody();
        }
        assertThat(body.get("status")).as("%s", body).isEqualTo("done");
        UUID tenantId = UUID.fromString((String) body.get("createdTenantId"));

        assertThat(su().queryForObject("SELECT facility_type FROM tenants WHERE id = ?", String.class, tenantId)).isEqualTo("lab");
        assertThat(su().queryForObject("SELECT region_code FROM tenants WHERE id = ?", String.class, tenantId)).as("region pinned").isEqualTo("IN");
        assertThat(su().queryForList("SELECT preset_code FROM roles WHERE tenant_id = ?", String.class, tenantId))
                .containsExactlyInAnyOrder("owner", "lab_manager", "pathologist", "lab_technician", "lab_front_desk")
                .containsExactlyInAnyOrderElementsOf(RolePresets.LAB.stream().map(RolePresets.Preset::code).toList());
        assertThat(su().queryForObject("SELECT p.code FROM master.subscriptions s JOIN master.plans p ON p.id = s.plan_id WHERE s.tenant_id = ?",
                String.class, tenantId)).isEqualTo("lab_basic");
        assertThat(su().queryForList("SELECT e.module_code FROM master.subscriptions s JOIN master.plans p ON p.id = s.plan_id " +
                "JOIN master.plan_entitlements e ON e.plan_id = p.id AND e.plan_version = s.plan_version WHERE s.tenant_id = ?", String.class, tenantId))
                .containsExactlyInAnyOrder("lab", "billing");

        // the freshly provisioned lab's own Owner role (the seeded preset, not a test role)
        UUID ownerRole = su().queryForObject("SELECT id FROM roles WHERE tenant_id = ? AND built_in", UUID.class, tenantId);
        String token = loginAndGetAccessToken(seedStaff(tenantHandle(tenantId, slug), ownerRole, "o" + SEQ.incrementAndGet() + "@p56.test", phone(), false));
        Map ent = get(token, "/v1/me/entitlements").getBody();
        assertThat(ent.get("edition")).isEqualTo("lab");
        assertThat(((List<Map<String, Object>>) ent.get("modules")).stream().filter(m -> Boolean.TRUE.equals(m.get("enabled"))).map(m -> m.get("code")))
                .as("enabled modules (whatsapp_bot is a kill switch, not a plan module)").containsExactlyInAnyOrder("lab", "billing", "whatsapp_bot");
        assertThat(get(token, "/v1/lab/tests").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(token, "/v1/lab/setup").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(token, "/v1/reports/overview").getStatusCode()).as("reports stay open").isEqualTo(HttpStatus.OK);
        for (String path : List.of("/v1/appointments", "/v1/schedule/day", "/v1/clinical/encounters", "/v1/pharmacy/items")) {
            assertThat(refused(get(token, path))).as(path).isTrue();
        }
    }

    @Test
    void theLabPlansAreInactivePlaceholdersAndAnUnknownEditionIsRefused() {
        List<Map<String, Object>> plans = su().queryForList("SELECT code, active, monthly_price_cents FROM master.plans WHERE code IN ('lab_basic', 'lab_pro')");
        assertThat(plans).extracting(p -> p.get("code")).containsExactlyInAnyOrder("lab_basic", "lab_pro");
        assertThat(plans).as("NEEDS PRICING: not offered until priced").allSatisfy(p -> assertThat(p.get("active")).isEqualTo(false));
        assertThat(su().queryForList("SELECT DISTINCT e.module_code FROM master.plans p JOIN master.plan_entitlements e ON e.plan_id = p.id " +
                "WHERE p.code = 'lab_pro'", String.class)).containsExactlyInAnyOrder("lab", "billing");

        String op = platformLoginAndGetAccessToken(seedOperator("super-" + UUID.randomUUID() + "@nabd.health", "super_admin", false));
        ResponseEntity<Map> bad = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(op, Map.of(
                "tenantSlug", "nope-" + UUID.randomUUID().toString().substring(0, 6), "tenantName", "X", "region", "IN", "ownerEmail", "o@nabd.health",
                "ownerName", "O", "ownerMobile", "+919876500002", "brandName", "B", "path", "self_serve", "edition", "laboratory")), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- AC4: every clinical, scheduling and pharmacy endpoint answers 403 module-not-entitled ----
    @Test
    void everyClinicalSchedulingAndPharmacyAreaIs403ForALabAndLabBillingAndReportsStayOpen() {
        String lab = ownerOf("lab");
        List<String> slipped = new ArrayList<>();
        for (String path : List.of("/v1/appointments", "/v1/queue", "/v1/queue/eta", "/v1/schedule/day", "/v1/clinical/encounters", "/v1/clinical/drugs",
                "/v1/nursing/procedure-orders/today", "/v1/packages", "/v1/waitlist", "/v1/booking-requests", "/v1/abha/status",
                "/v1/doctors/" + UUID.randomUUID() + "/working-hours", "/v1/recall-orders/vaccines-due-today",
                "/v1/reports/eta-accuracy", // a queue controller under /v1/reports: caught by package
                "/v1/pharmacy/items", "/v1/pharmacy/settings", "/v1/pharmacy/setup", "/v1/pharmacy/worklist")) {
            ResponseEntity<Map> r = get(lab, path);
            if (!refused(r)) slipped.add("GET " + path + " -> " + r.getStatusCode().value());
        }
        for (String path : List.of("/v1/appointments", "/v1/pharmacy/items", "/v1/pharmacy/returns", "/v1/clinical/prescriptions/" + UUID.randomUUID())) {
            HttpMethod m = path.startsWith("/v1/clinical/prescriptions") ? HttpMethod.PATCH : HttpMethod.POST;
            ResponseEntity<Map> r = exchange(path, m, authedJsonBody(lab, Map.of()), Map.class);
            if (!refused(r)) slipped.add(m + " " + path + " -> " + r.getStatusCode().value());
        }
        assertThat(slipped).as("endpoints of a lab tenant not refused with 403 MODULE_NOT_ENTITLED").isEmpty();
        assertThat(String.valueOf(get(lab, "/v1/appointments").getBody())).contains("This is a lab account");

        // lab, billing, reports and the entitlement answer stay open
        ResponseEntity<Map> test = exchange("/v1/lab/tests", HttpMethod.POST, authedJsonBody(lab, Map.of("code", "GLU", "name", "Glucose", "sampleType", "serum",
                "container", "sst_gold", "volumeMl", 3, "unit", "mg/dL", "tatMinutes", 240, "price", 150)), Map.class);
        assertThat(test.getStatusCode()).as("%s", test.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> quote = exchange("/v1/billing/quotes", HttpMethod.POST, authedJsonBody(lab,
                Map.of("lines", List.of(Map.of("chargeCode", "LAB-GLU", "quantity", 1)))), Map.class);
        assertThat(quote.getStatusCode()).as("billing prices a lab test: %s", quote.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(get(lab, "/v1/reports/overview").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(lab, "/v1/me/entitlements").getBody().get("edition")).isEqualTo("lab");

        // the other editions are untouched: a pharmacy keeps its pharmacy and the drug master, a clinic keeps everything
        String pharmacy = ownerOf("pharmacy");
        assertThat(get(pharmacy, "/v1/me/entitlements").getBody().get("edition")).isEqualTo("pharmacy");
        assertThat(refused(get(pharmacy, "/v1/pharmacy/settings"))).isFalse();
        assertThat(refused(get(pharmacy, "/v1/clinical/drugs"))).isFalse();
        assertThat(refused(get(pharmacy, "/v1/appointments"))).isTrue();
        assertThat(String.valueOf(get(pharmacy, "/v1/appointments").getBody())).contains("This is a pharmacy account");
        String clinic = ownerOf("clinic");
        assertThat(get(clinic, "/v1/me/entitlements").getBody().get("edition")).isEqualTo("clinic");
        for (String path : List.of("/v1/appointments", "/v1/pharmacy/settings", "/v1/clinical/drugs", "/v1/lab/tests")) {
            assertThat(refused(get(clinic, path))).as(path).isFalse();
        }
    }

    // ---- the edition matrix over every mapped /v1 endpoint ----

    /** The pharmacy rule exactly as PHL-032 shipped it (EntitlementGuard at 85e1a176), kept here to prove the generalised guard did not move it. */
    private static final List<String> PHL032_BLOCKED = List.of("/v1/appointments", "/v1/booking-requests", "/v1/booking-policy",
            "/v1/clinical", "/v1/queue", "/v1/schedule", "/v1/waitlist", "/v1/nursing", "/v1/specialty", "/v1/packages", "/v1/doctors",
            "/v1/recall-orders", "/v1/front-desk", "/v1/display", "/v1/display-devices", "/v1/facility",
            "/v1/queue-settings", "/v1/clinic", "/v1/abha");
    private static final List<String> PHL032_PACKAGES = List.of("com.nabd.hms.queue", "com.nabd.hms.nursing", "com.nabd.hms.specialty", "com.nabd.hms.packages");
    private static final List<String> PHL032_ALLOWED = List.of("/v1/clinical/drug", "/v1/reason-codes");

    private static boolean phl032Blocked(String path, Class<?> controller) {
        if (PHL032_ALLOWED.stream().anyMatch(path::startsWith)) return false;
        return PHL032_BLOCKED.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/"))
                || PHL032_PACKAGES.stream().anyMatch(controller.getPackageName()::startsWith);
    }

    /** Every mapped /v1 path (path variables filled) with its controller. Public endpoints carry no clinic session, so no edition applies. */
    private int forEachClinicEndpoint(BiConsumer<String, Class<?>> check) {
        int n = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            var patterns = e.getKey().getPathPatternsCondition();
            if (patterns == null) continue;
            for (String p : patterns.getPatternValues()) {
                if (!p.startsWith("/v1/") || p.startsWith("/v1/public/")) continue;
                check.accept(p.replaceAll("\\{[^}]+}", "x"), e.getValue().getBeanType());
                n++;
            }
        }
        return n;
    }

    private static boolean in(Class<?> controller, String... modules) {
        return List.of(modules).stream().anyMatch(m -> controller.getPackageName().equals("com.nabd.hms." + m)
                || controller.getPackageName().startsWith("com.nabd.hms." + m + "."));
    }

    @Test
    void editionMatrix_thePharmacyRuleIsUnchangedAndTheLabRuleCoversEveryClinicalSchedulingAndPharmacyController() {
        EntitlementGuard.EditionRule pharmacy = EntitlementGuard.EDITIONS.get("pharmacy");
        EntitlementGuard.EditionRule lab = EntitlementGuard.EDITIONS.get("lab");
        assertThat(EntitlementGuard.EDITIONS.keySet()).containsExactlyInAnyOrder("pharmacy", "lab");
        List<String> pharmacyMoved = new ArrayList<>(), labOpen = new ArrayList<>(), labShut = new ArrayList<>(), labNarrower = new ArrayList<>();
        int checked = forEachClinicEndpoint((path, c) -> {
            if (pharmacy.blocks(path, c) != phl032Blocked(path, c)) pharmacyMoved.add(path);
            if (pharmacy.blocks(path, c) && !lab.blocks(path, c)) labNarrower.add(path);
            // a controller of a clinical, scheduling or pharmacy module is refused whatever its path (the shared reason-code picker aside)
            if (in(c, "queue", "nursing", "specialty", "packages", "clinical", "engagement", "pharmacy") && !path.startsWith("/v1/reason-codes")
                    && !lab.blocks(path, c)) labOpen.add(path + " (" + c.getSimpleName() + ")");
            // what a lab is sold: lab, billing and reports (billing's pharmacy returns go with the pharmacy)
            if (in(c, "lab", "billing", "reports") && !path.startsWith("/v1/pharmacy") && lab.blocks(path, c)) labShut.add(path + " (" + c.getSimpleName() + ")");
        });
        assertThat(checked).as("mapped /v1 endpoints").isGreaterThan(300);
        assertThat(pharmacyMoved).as("pharmacy edition: endpoints whose answer changed with PHL-056").isEmpty();
        assertThat(labNarrower).as("blocked for a pharmacy but open for a lab").isEmpty();
        assertThat(labOpen).as("clinical, scheduling or pharmacy endpoints a lab could reach").isEmpty();
        assertThat(labShut).as("lab, billing or reports endpoints a lab is refused").isEmpty();
        assertThat(lab.blocks("/v1/clinical/drugs", Object.class)).as("the drug master is the pharmacy's, not the lab's").isTrue();
        assertThat(pharmacy.blocks("/v1/clinical/drugs", Object.class)).isFalse();
        assertThat(pharmacy.blocks("/v1/pharmacy/items", Object.class)).isFalse();
        assertThat(EntitlementGuard.editionOf("multi_specialty")).isEqualTo("clinic");
    }

    /** T1-BUG-1 again: V3650 re-creates roles_preset_code_check after V3540 and V3590, so it must list every preset of every edition. */
    @Test
    void presetCodeCheckAllowsEveryPresetOfEveryEdition() {
        String def = jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'roles_preset_code_check'", String.class);
        for (List<RolePresets.Preset> edition : List.of(RolePresets.ALL, RolePresets.PHARMACY, RolePresets.LAB)) {
            edition.forEach(p -> assertThat(def).as(p.code()).contains("'" + p.code() + "'"));
        }
        assertThat(RolePresets.forEdition("lab")).isSameAs(RolePresets.LAB);
        assertThat(RolePresets.forEdition("pharmacy")).isSameAs(RolePresets.PHARMACY);
        assertThat(RolePresets.forEdition("clinic")).isSameAs(RolePresets.ALL);
        assertThat(RolePresets.byCode("pathologist")).isPresent();
    }
}

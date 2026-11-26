package com.nabd.hms.lab;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.platform.entitlements.PlanEntitlements;
import com.nabd.hms.staff.presets.RolePresets;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHL-056: the lab setup wizard's record (accreditation, pathologist, letterhead lines, printer test) and the price list import that
 * prices the starter catalogue with a dry run, in a lab-only tenant.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl056LabSetupApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000) + 100_000);

    @Autowired
    private PlanEntitlements planEntitlements;

    private record Lab(SeededTenant t, SeededStaff manager, String token) {
    }

    private String phone() {
        return "+9195" + (10000000 + SEQ.incrementAndGet());
    }

    /** A lab-only tenant (what provisioning does with edition 'lab') whose manager holds the Lab manager preset's grants. */
    private Lab lab() {
        SeededTenant t = seedTenant();
        new JdbcTemplate(superuserDataSource()).update("UPDATE tenants SET facility_type = 'lab' WHERE id = ?", t.id());
        planEntitlements.evict(t.id());
        SeededStaff m = staff(t, "lab_manager");
        return new Lab(t, m, loginAndGetAccessToken(m));
    }

    private SeededStaff staff(SeededTenant t, String preset) {
        UUID role = seedRole(t.id(), preset + " " + SEQ.incrementAndGet(), false,
                RolePresets.byCode(preset).orElseThrow().grants().toArray(ModuleGrant[]::new));
        return seedStaff(t, role, "s" + SEQ.incrementAndGet() + "@p56.test", phone(), false);
    }

    private int count(UUID tenant, String sql, Object... args) {
        return inTenantTx(tenant, () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    private ResponseEntity<Map> importPrices(String token, String csv, boolean dryRun) {
        return exchange("/v1/lab/catalogue/price-imports", HttpMethod.POST, authedJsonBody(token, Map.of("csv", csv, "dryRun", dryRun)), Map.class);
    }

    private ResponseEntity<Map> put(String token, Map<String, Object> body) {
        return exchange("/v1/lab/setup", HttpMethod.PUT, authedJsonBody(token, body), Map.class);
    }

    private List<String> codes(String token) {
        List<String> out = new ArrayList<>();
        String cursor = null;
        do {
            Map body = exchange("/v1/lab/tests?limit=200" + (cursor == null ? "" : "&cursor=" + cursor), HttpMethod.GET, authed(token), Map.class).getBody();
            ((List<Map<String, Object>>) body.get("items")).forEach(x -> out.add((String) x.get("code")));
            cursor = (String) body.get("nextCursor");
        } while (cursor != null);
        return out;
    }

    // ---- AC2: the starter catalogue and a price CSV: tests are priced in under two minutes, after a dry run ----
    @Test
    void theStarterCatalogueIsPricedFromACsvWithADryRunInUnderTwoMinutes() {
        Lab lab = lab();
        UUID t = lab.t().id();
        long t0 = System.nanoTime();
        ResponseEntity<Map> pack = exchange("/v1/lab/catalogue/imports", HttpMethod.POST, authedJsonBody(lab.token(), Map.of("pack", "IN_STARTER", "dryRun", false)), Map.class);
        assertThat(pack.getStatusCode()).as("%s", pack.getBody()).isEqualTo(HttpStatus.OK);
        List<String> codes = codes(lab.token());
        assertThat(codes).hasSize(150);
        assertThat(count(t, "SELECT count(*) FROM lab_tests WHERE tenant_id = ? AND active", t)).as("nothing is billable before it is priced").isZero();

        // the lab's own price list: a header, a note, a quoted amount with a thousands comma, lower-case codes,
        // and four lines that change nothing (unknown code, no price yet, zero, listed twice)
        StringBuilder csv = new StringBuilder("Code,Price\r\n# our 2026 price list\r\n");
        for (int i = 0; i < codes.size(); i++) {
            if (i == 2 || i == 3) continue; // these two only have a line without a usable price, below
            String code = i == 1 ? codes.get(i).toLowerCase() : codes.get(i);
            csv.append(code).append(',').append(i == 0 ? "\"1,250.00\"" : String.valueOf(100 + i)).append("\r\n");
        }
        csv.append("NOT-IN-CATALOGUE,300\r\n").append(codes.get(2)).append(",\r\n").append(codes.get(3)).append(",0\r\n").append(codes.get(4)).append(",999\r\n");

        ResponseEntity<Map> dry = importPrices(lab.token(), csv.toString(), true);
        assertThat(dry.getStatusCode()).as("%s", dry.getBody()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> toPrice = (List<Map<String, Object>>) dry.getBody().get("toPrice");
        assertThat(toPrice).hasSize(148); // 150 less the two whose only line has no usable price
        assertThat(toPrice.get(0)).containsEntry("code", codes.get(0)).containsKey("name");
        assertThat(((Number) toPrice.get(0).get("newPrice")).doubleValue()).isEqualTo(1250.0);
        assertThat(((Number) toPrice.get(0).get("currentPrice")).doubleValue()).isZero();
        assertThat((List<String>) dry.getBody().get("unknown")).containsExactly("NOT-IN-CATALOGUE");
        List<Map<String, Object>> problems = (List<Map<String, Object>>) dry.getBody().get("problems");
        assertThat(problems).as("a line without a price is not a problem: it is simply not priced").hasSize(2);
        assertThat(problems).extracting(p -> (String) p.get("message")).allSatisfy(m -> assertThat(m).startsWith("Line ").endsWith("."));
        assertThat((String) problems.get(1).get("message")).contains(codes.get(4)).contains("listed twice");
        assertThat(dry.getBody().get("updated")).isEqualTo(0);
        assertThat(count(t, "SELECT count(*) FROM lab_tests WHERE tenant_id = ? AND active", t)).as("a dry run writes nothing").isZero();
        assertThat(count(t, "SELECT count(*) FROM charge_catalogue WHERE tenant_id = ? AND category = 'Lab' AND base_amount > 0", t)).isZero();
        assertThat(count(t, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'lab.prices_imported'", t)).isZero();

        ResponseEntity<Map> done = importPrices(lab.token(), csv.toString(), false);
        long seconds = Duration.ofNanos(System.nanoTime() - t0).toSeconds();
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("updated")).isEqualTo(148);
        assertThat(count(t, "SELECT count(*) FROM lab_tests l JOIN charge_catalogue c ON c.id = l.charge_item_id AND c.tenant_id = l.tenant_id "
                + "WHERE l.tenant_id = ? AND l.active AND c.active AND c.base_amount > 0", t)).as("priced and billable").isEqualTo(148);
        assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT c.base_amount FROM lab_tests l JOIN charge_catalogue c ON c.id = l.charge_item_id "
                + "WHERE l.tenant_id = ? AND l.code = ?", java.math.BigDecimal.class, t, codes.get(0)))).isEqualByComparingTo("1250.00");
        assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT c.base_amount FROM lab_tests l JOIN charge_catalogue c ON c.id = l.charge_item_id "
                + "WHERE l.tenant_id = ? AND l.code = ?", java.math.BigDecimal.class, t, codes.get(4)))).as("listed twice: the first price").isEqualByComparingTo("104");
        assertThat(count(t, "SELECT count(*) FROM lab_tests WHERE tenant_id = ? AND NOT active", t)).as("the unpriced and the zero-priced test are left alone").isEqualTo(2);
        assertThat(count(t, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'lab.prices_imported'", t)).isEqualTo(1);
        assertThat(count(t, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'lab.test_updated'", t)).as("each price change is audited").isEqualTo(148);
        assertThat(seconds).as("starter pack import, dry run and commit of the price list, seconds").isLessThan(120);

        // billing sells the priced test at the imported price
        ResponseEntity<Map> quote = exchange("/v1/billing/quotes", HttpMethod.POST, authedJsonBody(lab.token(),
                Map.of("lines", List.of(Map.of("chargeCode", "LAB-" + codes.get(5), "quantity", 1)))), Map.class);
        assertThat(quote.getStatusCode()).as("%s", quote.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) ((Map<?, ?>) quote.getBody().get("totals")).get("subtotalMinor")).longValue()).isEqualTo(10500);

        // the same file again changes nothing
        ResponseEntity<Map> again = importPrices(lab.token(), csv.toString(), false);
        assertThat(again.getBody().get("updated")).isEqualTo(0);
        assertThat(again.getBody().get("unchanged")).isEqualTo(148);
        assertThat(count(t, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'lab.prices_imported'", t)).isEqualTo(1);
        assertThat(exchange("/v1/lab/setup", HttpMethod.GET, authed(lab.token()), Map.class).getBody())
                .containsEntry("catalogueTests", 150).containsEntry("activeTests", 148);
    }

    @Test
    void aPriceListThatIsNotALineOfCodeAndPriceIsExplainedInSentencesAndAnotherLabsTestsAreNeverPriced() {
        Lab a = lab();
        Lab b = lab();
        for (Lab l : List.of(a, b)) {
            assertThat(exchange("/v1/lab/tests", HttpMethod.POST, authedJsonBody(l.token(), Map.of("code", "GLU", "name", "Glucose", "sampleType", "serum",
                    "container", "sst_gold", "volumeMl", 3, "unit", "mg/dL", "tatMinutes", 240, "price", 150)), Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        ResponseEntity<Map> r = importPrices(a.token(), "GLU,175.5\n???,10\nGLU;200\nGLU,12.345\nGLU,-4\n", false);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("updated")).isEqualTo(1);
        assertThat((List<Map<String, Object>>) r.getBody().get("problems")).extracting(p -> p.get("line")).containsExactly(2, 3, 4, 5);
        assertThat(inTenantTx(a.t().id(), () -> jdbc.queryForObject("SELECT c.base_amount FROM lab_tests l JOIN charge_catalogue c ON c.id = l.charge_item_id "
                + "WHERE l.tenant_id = ? AND l.code = 'GLU'", java.math.BigDecimal.class, a.t().id()))).isEqualByComparingTo("175.50");
        assertThat(inTenantTx(b.t().id(), () -> jdbc.queryForObject("SELECT c.base_amount FROM lab_tests l JOIN charge_catalogue c ON c.id = l.charge_item_id "
                + "WHERE l.tenant_id = ? AND l.code = 'GLU'", java.math.BigDecimal.class, b.t().id()))).as("the other lab's price").isEqualByComparingTo("150");

        assertThat(importPrices(a.token(), "   ", false).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i <= LabSetupService.MAX_LINES; i++) big.append('T').append(i).append(",10\n");
        ResponseEntity<Map> tooMany = importPrices(a.token(), big.toString(), true);
        assertThat(tooMany.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(tooMany.getBody())).contains("up to " + LabSetupService.MAX_LINES + " lines");
    }

    // ---- the wizard's record ----
    @Test
    void theWizardSavesAccreditationPathologistAndLetterheadAndRecordsThePrinterTest() {
        Lab lab = lab();
        UUID t = lab.t().id();
        Map<String, Object> empty = exchange("/v1/lab/setup", HttpMethod.GET, authed(lab.token()), Map.class).getBody();
        assertThat(empty).containsEntry("catalogueTests", 0).containsEntry("activeTests", 0);
        assertThat(empty.get("pathologistStaffId")).isNull();
        assertThat(empty.get("printerTestedAt")).isNull();

        SeededStaff pathologist = staff(lab.t(), "pathologist");
        Map<String, Object> body = new HashMap<>(Map.of("accreditationNo", " MC-0000 ", "pathologistStaffId", pathologist.id().toString(),
                "pathologistRegistrationNo", "SYN-REG-1", "letterheadLines", "12 Example Road\nExample City 560001\nPhone 080 0000 0000"));
        ResponseEntity<Map> saved = put(lab.token(), body);
        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody()).containsEntry("accreditationNo", "MC-0000").containsEntry("pathologistName", "Test Staff")
                .containsEntry("pathologistRegistrationNo", "SYN-REG-1").containsEntry("letterheadLines", body.get("letterheadLines"));
        assertThat(exchange("/v1/lab/setup", HttpMethod.GET, authed(lab.token()), Map.class).getBody())
                .containsEntry("pathologistStaffId", pathologist.id().toString()).containsEntry("accreditationNo", "MC-0000");
        assertThat(count(t, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'lab.setup_updated'", t)).isEqualTo(1);
        assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT after::text FROM audit_log WHERE tenant_id = ? AND action = 'lab.setup_updated'", String.class, t)))
                .as("the registration number is not copied into the audit log").doesNotContain("SYN-REG-1");

        // accreditation is optional: leaving it out clears it and keeps the rest
        body.remove("accreditationNo");
        assertThat(put(lab.token(), body).getBody().get("accreditationNo")).isNull();

        // a staff member of another lab is not a pathologist here; seven letterhead lines do not fit
        Lab other = lab();
        body.put("pathologistStaffId", other.manager().id().toString());
        ResponseEntity<Map> stranger = put(lab.token(), body);
        assertThat(stranger.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(String.valueOf(stranger.getBody())).contains("unknown-staff");
        body.put("pathologistStaffId", pathologist.id().toString());
        body.put("letterheadLines", "1\n2\n3\n4\n5\n6\n7");
        assertThat(put(lab.token(), body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/lab/setup", HttpMethod.GET, authed(lab.token()), Map.class).getBody())
                .as("refused saves change nothing").containsEntry("pathologistStaffId", pathologist.id().toString()).containsEntry("pathologistRegistrationNo", "SYN-REG-1");
        assertThat(exchange("/v1/lab/setup", HttpMethod.GET, authed(other.token()), Map.class).getBody().get("pathologistRegistrationNo"))
                .as("another lab sees none of it").isNull();

        ResponseEntity<Map> printed = exchange("/v1/lab/setup/printer-test", HttpMethod.POST, authedJsonBody(lab.token(), Map.of()), Map.class);
        assertThat(printed.getStatusCode()).as("%s", printed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(printed.getBody().get("printerTestedAt")).isNotNull();
        assertThat(exchange("/v1/lab/setup", HttpMethod.GET, authed(lab.token()), Map.class).getBody())
                .as("the printer test keeps the saved details").containsEntry("pathologistRegistrationNo", "SYN-REG-1").containsKey("printerTestedAt");
        assertThat(count(t, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'lab.printer_tested'", t)).isEqualTo(1);
    }

    @Test
    void onlyTheLabManagersGrantChangesTheSetupAndAClinicWithoutTheLabModuleIsRefused() {
        Lab lab = lab();
        for (String preset : List.of("lab_technician", "pathologist", "lab_front_desk")) {
            String token = loginAndGetAccessToken(staff(lab.t(), preset));
            assertThat(exchange("/v1/lab/setup", HttpMethod.GET, authed(token), Map.class).getStatusCode()).as(preset + " reads").isEqualTo(HttpStatus.OK);
            assertThat(put(token, Map.of("accreditationNo", "X")).getStatusCode()).as(preset + " saves").isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(exchange("/v1/lab/setup/printer-test", HttpMethod.POST, authedJsonBody(token, Map.of()), Map.class).getStatusCode()).as(preset + " printer").isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(importPrices(token, "GLU,10", false).getStatusCode()).as(preset + " prices").isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(count(lab.t().id(), "SELECT count(*) FROM lab_settings WHERE tenant_id = ?", lab.t().id())).isZero();

        // a clinic whose plan has no lab module: LabEntitlementGuard covers the new endpoints too
        SeededTenant clinic = seedTenant();
        String op = platformLoginAndGetAccessToken(seedOperator("sa-" + UUID.randomUUID() + "@nabd.internal", "super_admin", false));
        ResponseEntity<Map> plan = exchange("/v1/platform/plans", HttpMethod.POST, authedJsonBody(op, Map.of(
                "code", "p56-" + UUID.randomUUID(), "name", "P", "monthlyPriceCents", 100000, "currency", "INR",
                "active", true, "modules", List.of("billing"), "limits", Map.of("seats", 20, "branches", 2))), Map.class);
        assertThat(plan.getStatusCode()).as("%s", plan.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(exchange("/v1/platform/billing/subscriptions/" + clinic.id(), HttpMethod.POST, authedJsonBody(op,
                Map.of("planId", plan.getBody().get("id"), "mrrCents", 100000, "renewalDate", java.time.LocalDate.now().plusDays(30).toString())), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        planEntitlements.evict(clinic.id());
        String token = loginAndGetAccessToken(staff(clinic, "lab_manager"));
        for (ResponseEntity<Map> r : List.of(exchange("/v1/lab/setup", HttpMethod.GET, authed(token), Map.class), put(token, Map.of()),
                importPrices(token, "GLU,10", false), exchange("/v1/lab/setup/printer-test", HttpMethod.POST, authedJsonBody(token, Map.of()), Map.class))) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(r.getBody().get("code")).isEqualTo("MODULE_NOT_ENTITLED");
        }
    }
}

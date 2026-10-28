package com.nabd.hms.organization;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** S17 QA (independent): FIN-017 starter price lists and the add-only bulk create, against the real API and Postgres. */
@SuppressWarnings({"rawtypes", "unchecked"})
class S17QaFin017PriceTemplateApiTest extends ApiTestBase {

    private static final JdbcTemplate SU = new JdbcTemplate(superuserDataSource());

    private record Fx(SeededTenant tenant, SeededStaff owner, String token) {
    }

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String phone() {
        return "+9195" + (10000000 + (int) (Math.random() * 89999999));
    }

    private Fx clinic(String region) {
        SeededTenant t = seedTenant();
        if (!"IN".equals(region)) { // region is immutable for the API role; move the fresh clinic before any request reads it
            SU.execute("SET session_replication_role = replica; UPDATE tenants SET region_code = '" + region + "' WHERE id = '" + t.id() + "'; SET session_replication_role = origin");
        }
        SeededStaff o = seedStaff(t, seedFullAccessRole(t.id()), "qa17o" + uid() + "@a.com", phone(), false);
        return new Fx(t, o, loginAndGetAccessToken(o));
    }

    private String tokenWith(Fx f, ModuleGrant... grants) {
        return loginAndGetAccessToken(seedStaff(f.tenant(), seedRole(f.tenant().id(), "R" + uid(), false, grants), "qa17s" + uid() + "@a.com", phone(), false));
    }

    private ResponseEntity<Map> template(String token, String query) {
        return exchange("/v1/setup/price-templates" + query, HttpMethod.GET, authed(token), Map.class);
    }

    private ResponseEntity<Map> bulk(String token, Object templateId, List<Map<String, Object>> heads) {
        Map<String, Object> body = new HashMap<>();
        body.put("templateId", templateId);
        body.put("heads", heads);
        return exchange("/v1/setup/charges/bulk", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private static List<Map<String, Object>> heads(ResponseEntity<Map> t) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object h : (List<?>) t.getBody().get("heads")) out.add(new HashMap<>((Map<String, Object>) h));
        return out;
    }

    private static Map<String, Object> head(List<Map<String, Object>> hs, String code) {
        return hs.stream().filter(h -> code.equals(h.get("code"))).findFirst().orElseThrow();
    }

    private long count(UUID tenant, String sql) {
        return inTenantTx(tenant, () -> jdbc.queryForObject(sql, Long.class, tenant));
    }

    private void createCharge(Fx f, String code, String name, int amount, boolean active) {
        ResponseEntity<Map> r = exchange("/v1/setup/charges", HttpMethod.POST, authedJsonBody(f.token(), Map.of(
                "code", code, "name", name, "category", "Consultation", "baseAmount", amount, "active", active,
                "effectiveFrom", "2026-01-01", "displayOrder", 1)), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    // F-API-01: AC1, the clinic's own market by default
    @Test
    void s17qa_F_API_01_dermatologyIndiaDefaultMarketHasCategoriesSacAndGst() {
        Fx f = clinic("IN");
        ResponseEntity<Map> t = template(f.token(), "?specialty=derma");
        assertThat(t.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(t.getBody()).containsEntry("jurisdiction", "IN").containsEntry("currency", "INR").containsEntry("reviewed", false);
        List<Map<String, Object>> hs = heads(t);
        assertThat(hs).hasSizeBetween(8, 15);
        assertThat(hs).allSatisfy(h -> {
            assertThat((String) h.get("category")).isNotBlank();
            assertThat((String) h.get("sacHsn")).matches("[0-9]{4,8}");
            assertThat(h.get("taxCategory")).isIn("exempt", "taxable");
            assertThat(((Number) h.get("baseAmount")).doubleValue()).isGreaterThanOrEqualTo(0);
        });
        assertThat(hs.stream().map(h -> ((String) h.get("code")).toUpperCase()).distinct().count()).isEqualTo(hs.size());
        for (Map<String, Object> h : hs) {
            if ("Consultation".equals(h.get("category"))) {
                assertThat(h.get("taxCategory")).as("%s", h.get("code")).isEqualTo("exempt");
            }
        }
        for (String cosmetic : List.of("LASER", "PEEL", "PRP")) {
            assertThat(head(hs, cosmetic)).containsEntry("taxCategory", "taxable");
            assertThat(((Number) head(hs, cosmetic).get("taxRatePercent")).doubleValue()).isEqualTo(18.0);
        }
    }

    // F-API-02: AC2, a KSA clinic gets VAT by default
    @Test
    void s17qa_F_API_02_ksaClinicGetsVatNotGstByDefault() {
        Fx f = clinic("SA");
        ResponseEntity<Map> t = template(f.token(), "?specialty=derma");
        assertThat(t.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(t.getBody()).containsEntry("jurisdiction", "SA").containsEntry("currency", "SAR");
        assertThat(heads(t)).hasSizeBetween(8, 15).allSatisfy(h -> {
            assertThat(h.get("taxCategory")).isEqualTo("taxable");
            assertThat(((Number) h.get("taxRatePercent")).doubleValue()).isEqualTo(15.0);
            assertThat(h.get("sacHsn")).isNull();
        });
        assertThat(template(f.token(), "?specialty=derma&jurisdiction=sa").getBody()).containsEntry("jurisdiction", "SA");
    }

    // F-API-03: AC4 one transaction, one change-log entry per head with the saved amount
    @Test
    void s17qa_F_API_03_saveWritesEveryHeadWithItsTaxDataAndOneChangeLogEntryEach() {
        Fx f = clinic("IN");
        ResponseEntity<Map> t = template(f.token(), "?specialty=derma");
        List<Map<String, Object>> hs = heads(t);
        head(hs, "LASER").put("baseAmount", 4321.5);
        head(hs, "PEEL").put("name", "Glycolic peel");
        ResponseEntity<Map> r = bulk(f.token(), t.getBody().get("templateId"), hs);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((List<String>) r.getBody().get("created")).containsExactlyElementsOf(hs.stream().map(h -> (String) h.get("code")).toList());
        UUID tid = f.tenant().id();
        assertThat(count(tid, "SELECT count(*) FROM charge_catalogue WHERE tenant_id = ?")).isEqualTo(hs.size());
        assertThat(count(tid, "SELECT count(DISTINCT charge_id) FROM charge_price_changes WHERE tenant_id = ?")).isEqualTo(hs.size());
        assertThat(count(tid, "SELECT count(*) FROM charge_price_changes WHERE tenant_id = ?")).isEqualTo(hs.size());
        Map<String, Object> laserLog = inTenantTx(tid, () -> jdbc.queryForMap(
                "SELECT x.new_value, x.actor_id FROM charge_price_changes x JOIN charge_catalogue c ON c.id = x.charge_id WHERE c.tenant_id = ? AND c.code = 'LASER'", tid));
        assertThat(new BigDecimal((String) laserLog.get("new_value"))).isEqualByComparingTo("4321.5");
        assertThat(laserLog.get("actor_id")).isEqualTo(f.owner().id());
        Map<String, Object> peel = inTenantTx(tid, () -> jdbc.queryForMap(
                "SELECT name, tax_category, sac_hsn, tax_rate_percent, active FROM charge_catalogue WHERE tenant_id = ? AND code = 'PEEL'", tid));
        assertThat(peel).containsEntry("name", "Glycolic peel").containsEntry("tax_category", "taxable").containsEntry("active", true);
        assertThat((BigDecimal) peel.get("tax_rate_percent")).isEqualByComparingTo("18");
        assertThat(count(tid, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'setup.price_template_applied'")).isEqualTo(1);
    }

    // F-API-04: AC4 all or nothing, whichever head is bad
    @Test
    void s17qa_F_API_04_anInvalidLastHeadWritesNothing() {
        Fx f = clinic("IN");
        ResponseEntity<Map> t = template(f.token(), "?specialty=gp");
        Object id = t.getBody().get("templateId");
        List<Map<String, Object>> bad;
        bad = heads(t); bad.get(bad.size() - 1).put("baseAmount", 10.555);
        assertThat(bulk(f.token(), id, bad).getStatusCode()).as("3 decimals").isEqualTo(HttpStatus.BAD_REQUEST);
        bad = heads(t); bad.get(bad.size() - 1).put("displayOrder", -1);
        assertThat(bulk(f.token(), id, bad).getStatusCode()).as("negative order").isEqualTo(HttpStatus.BAD_REQUEST);
        bad = heads(t); bad.get(bad.size() - 1).put("taxCategory", "out_of_scope");
        assertThat(bulk(f.token(), id, bad).getStatusCode()).as("unknown tax category").isEqualTo(HttpStatus.BAD_REQUEST);
        bad = heads(t); bad.get(bad.size() - 1).put("category", " ");
        assertThat(bulk(f.token(), id, bad).getStatusCode()).as("blank category").isEqualTo(HttpStatus.BAD_REQUEST);
        bad = heads(t); bad.get(bad.size() - 1).put("code", "BAD CODE");
        assertThat(bulk(f.token(), id, bad).getStatusCode()).as("space in code").isEqualTo(HttpStatus.BAD_REQUEST);
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            Map<String, Object> h = new HashMap<>(heads(t).get(0));
            h.put("code", "QA" + i);
            many.add(h);
        }
        assertThat(bulk(f.token(), id, many).getStatusCode()).as("51 heads").isEqualTo(HttpStatus.BAD_REQUEST);
        bad = heads(t); bad.get(bad.size() - 1).put("taxRatePercent", 5);
        ResponseEntity<Map> rate = bulk(f.token(), id, bad);
        assertThat(rate.getStatusCode()).as("rate on a non-taxable head").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(bulk(f.token(), id, null).getStatusCode()).as("no heads").isEqualTo(HttpStatus.BAD_REQUEST);
        UUID tid = f.tenant().id();
        assertThat(count(tid, "SELECT count(*) FROM charge_catalogue WHERE tenant_id = ?")).isZero();
        assertThat(count(tid, "SELECT count(*) FROM charge_price_changes WHERE tenant_id = ?")).isZero();
        assertThat(count(tid, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'setup.price_template_applied'")).isZero();
        // a 0 amount is valid
        List<Map<String, Object>> zero = heads(t);
        zero.get(0).put("baseAmount", 0);
        assertThat(bulk(f.token(), id, zero).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // F-API-05: AC5 never overwrites, matching case-insensitively and counting inactive heads
    @Test
    void s17qa_F_API_05_existingHeadsIncludingInactiveAndOtherCaseAreSkippedAndUntouched() {
        Fx f = clinic("IN");
        UUID tid = f.tenant().id();
        createCharge(f, "cons", "House consult", 1234, true);
        createCharge(f, "LASER", "Retired laser", 1, false);
        long logBefore = count(tid, "SELECT count(*) FROM charge_price_changes WHERE tenant_id = ?");
        Map<String, Object> before = inTenantTx(tid, () -> jdbc.queryForMap(
                "SELECT name, base_amount, active, tax_category, sac_hsn, display_order, updated_at FROM charge_catalogue WHERE tenant_id = ? AND code = 'LASER'", tid));
        ResponseEntity<Map> t = template(f.token(), "?specialty=derma");
        List<Map<String, Object>> hs = heads(t);
        ResponseEntity<Map> r = bulk(f.token(), t.getBody().get("templateId"), hs);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat((List<String>) r.getBody().get("skipped")).containsExactlyInAnyOrder("CONS", "LASER");
        assertThat((List<?>) r.getBody().get("created")).hasSize(hs.size() - 2);
        Map<String, Object> cons = inTenantTx(tid, () -> jdbc.queryForMap("SELECT code, name, base_amount, active FROM charge_catalogue WHERE tenant_id = ? AND upper(code) = 'CONS'", tid));
        assertThat(cons).containsEntry("code", "cons").containsEntry("name", "House consult").containsEntry("active", true);
        assertThat((BigDecimal) cons.get("base_amount")).isEqualByComparingTo("1234");
        Map<String, Object> after = inTenantTx(tid, () -> jdbc.queryForMap(
                "SELECT name, base_amount, active, tax_category, sac_hsn, display_order, updated_at FROM charge_catalogue WHERE tenant_id = ? AND code = 'LASER'", tid));
        assertThat(after).isEqualTo(before);
        assertThat(count(tid, "SELECT count(*) FROM charge_catalogue WHERE tenant_id = ?")).isEqualTo(hs.size());
        assertThat(count(tid, "SELECT count(*) FROM charge_price_changes WHERE tenant_id = ?") - logBefore).isEqualTo(hs.size() - 2);
    }

    // F-API-06: Idempotency-Key replay, reuse with another body, missing key
    @Test
    void s17qa_F_API_06_idempotencyReplayReuseAndMissingKey() {
        Fx f = clinic("IN");
        ResponseEntity<Map> t = template(f.token(), "?specialty=physio");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(f.token());
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        List<Map<String, Object>> hs = heads(t);
        String body = objectMapper.writeValueAsString(Map.of("templateId", t.getBody().get("templateId"), "heads", hs));
        ResponseEntity<Map> first = exchange("/v1/setup/charges/bulk", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        ResponseEntity<Map> replay = exchange("/v1/setup/charges/bulk", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        hs.get(0).put("baseAmount", 1);
        String other = objectMapper.writeValueAsString(Map.of("templateId", t.getBody().get("templateId"), "heads", hs));
        assertThat(exchange("/v1/setup/charges/bulk", HttpMethod.POST, new HttpEntity<>(other, h), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        HttpHeaders none = new HttpHeaders();
        none.setContentType(MediaType.APPLICATION_JSON);
        none.setBearerAuth(f.token());
        assertThat(exchange("/v1/setup/charges/bulk", HttpMethod.POST, new HttpEntity<>(body, none), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        UUID tid = f.tenant().id();
        assertThat(count(tid, "SELECT count(*) FROM charge_catalogue WHERE tenant_id = ?")).isEqualTo(hs.size());
        assertThat(count(tid, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'setup.price_template_applied'")).isEqualTo(1);
    }

    // F-API-07: permissions
    @Test
    void s17qa_F_API_07_permissionsForReadAndSave() {
        Fx f = clinic("IN");
        Object id = template(f.token(), "?specialty=gp").getBody().get("templateId");
        List<Map<String, Object>> hs = heads(template(f.token(), "?specialty=gp"));
        String noSetup = tokenWith(f, new ModuleGrant("queue", true, true, true, true, true, true, true));
        assertThat(template(noSetup, "?specialty=gp").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bulk(noSetup, id, hs).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String viewer = tokenWith(f, new ModuleGrant("setup", true, false, false, false, false, false, false));
        assertThat(template(viewer, "?specialty=gp").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bulk(viewer, id, hs).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String manager = tokenWith(f, new ModuleGrant("setup", true, true, true, true, true, true, true));
        ResponseEntity<Map> m = bulk(manager, id, hs);
        assertThat(m.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(m.getBody().get("type")).asString().contains("NOT_OWNER");
        assertThat(exchange("/v1/setup/price-templates?specialty=gp", HttpMethod.GET, HttpEntity.EMPTY, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(count(f.tenant().id(), "SELECT count(*) FROM charge_catalogue WHERE tenant_id = ?")).isZero();
    }

    // F-API-08: market and lookup errors
    @Test
    void s17qa_F_API_08_marketMismatchUnknownSpecialtyJurisdictionAndTemplate() {
        Fx f = clinic("IN");
        ResponseEntity<Map> ae = template(f.token(), "?specialty=dental&jurisdiction=AE");
        ResponseEntity<Map> wrong = bulk(f.token(), ae.getBody().get("templateId"), heads(ae));
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(wrong.getBody().get("type")).asString().contains("template-market-mismatch");
        assertThat(template(f.token(), "?specialty=cardio").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(template(f.token(), "").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(template(f.token(), "?specialty=gp&jurisdiction=ZZ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> in = template(f.token(), "?specialty=dental");
        assertThat(bulk(f.token(), UUID.randomUUID(), heads(in)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(count(f.tenant().id(), "SELECT count(*) FROM charge_catalogue WHERE tenant_id = ?")).isZero();
    }

    // F-API-09: tenancy
    @Test
    void s17qa_F_API_09_oneClinicsApplyNeverReachesAnother() {
        Fx a = clinic("IN");
        Fx b = clinic("IN");
        ResponseEntity<Map> t = template(a.token(), "?specialty=paediatric");
        assertThat(bulk(a.token(), t.getBody().get("templateId"), heads(t)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count(b.tenant().id(), "SELECT count(*) FROM charge_catalogue WHERE tenant_id = ?")).isZero();
        assertThat(count(b.tenant().id(), "SELECT count(*) FROM charge_price_changes WHERE tenant_id = ?")).isZero();
        assertThat((List<?>) exchange("/v1/setup/charges", HttpMethod.GET, authed(b.token()), List.class).getBody()).isEmpty();
        ResponseEntity<Map> rb = bulk(b.token(), t.getBody().get("templateId"), heads(t));
        assertThat(rb.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((List<?>) rb.getBody().get("skipped")).isEmpty();
    }

    // F-API-10: two parallel applies never duplicate a head
    @Test
    void s17qa_F_API_10_parallelAppliesNeverDuplicateAHead() {
        Fx f = clinic("IN");
        ResponseEntity<Map> t = template(f.token(), "?specialty=dental");
        List<Map<String, Object>> hs = heads(t);
        Object id = t.getBody().get("templateId");
        var one = CompletableFuture.supplyAsync(() -> bulk(f.token(), id, hs));
        var two = CompletableFuture.supplyAsync(() -> bulk(f.token(), id, hs));
        for (ResponseEntity<Map> r : List.of(one.join(), two.join())) {
            assertThat(r.getStatusCode()).as("%s", r.getBody()).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);
            if (r.getStatusCode() == HttpStatus.CONFLICT) assertThat(r.getBody().toString()).contains("CODE_EXISTS");
        }
        UUID tid = f.tenant().id();
        assertThat(count(tid, "SELECT count(*) FROM charge_catalogue WHERE tenant_id = ?")).isEqualTo(hs.size());
        assertThat(count(tid, "SELECT count(*) FROM charge_price_changes WHERE tenant_id = ?")).isEqualTo(hs.size());
    }

    // F-API-11: the saved heads bill with their tax (cosmetic 18 %, consult exempt)
    @Test
    void s17qa_F_API_11_savedHeadsBillWithTheirTax() {
        Fx f = clinic("IN");
        ResponseEntity<Map> t = template(f.token(), "?specialty=derma");
        List<Map<String, Object>> hs = heads(t);
        head(hs, "LASER").put("baseAmount", 4000);
        head(hs, "CONS").put("baseAmount", 700);
        assertThat(bulk(f.token(), t.getBody().get("templateId"), hs).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> p = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(f.token(), Map.of(
                "name", "Qa Taxpatient", "phone", phone(), "dob", LocalDate.of(1981, 3, 3).toString(), "gender", "female")), Map.class);
        assertThat(p.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> q = exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(f.token(), Map.of(
                "patientId", p.getBody().get("id"), "doctorId", f.owner().id().toString())), Map.class);
        String qe = (String) q.getBody().get("id");
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + qe + "/status", HttpMethod.PATCH, authedJsonBody(f.token(), Map.of("status", s)), Map.class);
        }
        ResponseEntity<Map> inv = exchange("/v1/billing/checkout/" + qe, HttpMethod.POST, authedJsonBody(f.token(), Map.of(
                "lineItems", List.of(Map.of("chargeCode", "LASER", "quantity", 1), Map.of("chargeCode", "CONS", "quantity", 1, "tier", "normal")))), Map.class);
        assertThat(inv.getStatusCode().is2xxSuccessful()).as("%s", inv.getBody()).isTrue();
        assertThat(((Number) inv.getBody().get("subtotal")).doubleValue()).isEqualTo(4700.0);
        assertThat(((Number) inv.getBody().get("tax")).doubleValue()).isEqualTo(720.0);
        List<Map<String, Object>> lines = (List<Map<String, Object>>) inv.getBody().get("lineItems");
        Map<String, Object> cons = lines.stream().filter(l -> "CONS".equals(l.get("chargeCode"))).findFirst().orElseThrow();
        assertThat(((Number) cons.get("taxRatePercent")).doubleValue()).isZero();
        assertThat(((Number) inv.getBody().get("total")).doubleValue()).isEqualTo(5420.0); // subtotal 4700 + tax 720
    }
}

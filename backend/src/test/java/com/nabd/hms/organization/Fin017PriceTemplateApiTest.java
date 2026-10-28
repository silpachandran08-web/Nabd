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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FIN-017: specialty starter price lists (reference data) and the add-only bulk create with one change-log entry per head. */
@SuppressWarnings({"rawtypes", "unchecked"})
class Fin017PriceTemplateApiTest extends ApiTestBase {

    private static final JdbcTemplate SU = new JdbcTemplate(superuserDataSource());

    private record Fx(SeededTenant tenant, SeededStaff owner, String token) {
    }

    private Fx clinic(String region) {
        SeededTenant t = seedTenant();
        if (!"IN".equals(region)) { // region is immutable for the API role; a test moves a fresh clinic as superuser before any request reads it
            SU.execute("SET session_replication_role = replica; UPDATE tenants SET region_code = '" + region + "' WHERE id = '" + t.id() + "'; SET session_replication_role = origin");
        }
        SeededStaff o = seedStaff(t, seedFullAccessRole(t.id()), "o" + UUID.randomUUID().toString().substring(0, 8) + "@a.com", phone(), false);
        return new Fx(t, o, loginAndGetAccessToken(o));
    }

    private static String phone() {
        return "+9197" + (10000000 + (int) (Math.random() * 89999999));
    }

    private String tokenWith(Fx f, String role, ModuleGrant setup) {
        SeededStaff s = seedStaff(f.tenant(), seedRole(f.tenant().id(), role + UUID.randomUUID(), false, setup), "s" + UUID.randomUUID().toString().substring(0, 8) + "@a.com", phone(), false);
        return loginAndGetAccessToken(s);
    }

    private ResponseEntity<Map> get(String token, String query) {
        return exchange("/v1/setup/price-templates" + query, HttpMethod.GET, authed(token), Map.class);
    }

    private ResponseEntity<Map> bulk(String token, Object templateId, List<Map<String, Object>> heads) {
        Map<String, Object> body = new HashMap<>();
        body.put("templateId", templateId);
        body.put("heads", heads);
        return exchange("/v1/setup/charges/bulk", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private static List<Map<String, Object>> heads(ResponseEntity<Map> template) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object h : (List<?>) template.getBody().get("heads")) out.add(new HashMap<>((Map<String, Object>) h));
        return out;
    }

    private static Map<String, Object> head(List<Map<String, Object>> heads, String code) {
        return heads.stream().filter(h -> code.equals(h.get("code"))).findFirst().orElseThrow();
    }

    private long count(UUID tenant, String sql, Object... args) {
        return inTenantTx(tenant, () -> jdbc.queryForObject(sql, Long.class, args));
    }

    // AC1
    @Test
    void dermatologyInIndiaPrefillsHeadsWithCategoriesSacAndGst() {
        Fx f = clinic("IN");
        ResponseEntity<Map> r = get(f.token(), "?specialty=derma&jurisdiction=IN");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("currency")).isEqualTo("INR");
        assertThat(r.getBody().get("reviewed")).isEqualTo(false); // pending billing-SME / CA review (open decision 64)
        assertThat((String) r.getBody().get("reviewNote")).contains("pending");
        List<Map<String, Object>> hs = heads(r);
        assertThat(hs).hasSizeBetween(8, 15);
        assertThat(hs).allSatisfy(h -> {
            assertThat((String) h.get("category")).isNotBlank();
            assertThat((String) h.get("sacHsn")).matches("[0-9]{6}");
        });
        Map<String, Object> cons = head(hs, "CONS");
        assertThat(cons.get("taxCategory")).isEqualTo("exempt");
        assertThat(((Number) cons.get("taxRatePercent")).doubleValue()).isZero();
        for (String cosmetic : List.of("LASER", "PEEL", "PRP")) {
            assertThat(head(hs, cosmetic).get("taxCategory")).isEqualTo("taxable");
            assertThat(((Number) head(hs, cosmetic).get("taxRatePercent")).doubleValue()).isEqualTo(18.0);
        }
        List<Integer> order = hs.stream().map(h -> (Integer) h.get("displayOrder")).toList();
        assertThat(order).isSorted();
        // without a jurisdiction the clinic's own market is used
        assertThat(get(f.token(), "?specialty=derma").getBody().get("jurisdiction")).isEqualTo("IN");
    }

    // AC2
    @Test
    void theSamePickInKsaPrefillsVatInsteadOfGstAndUaeHasItsOwnVat() {
        Fx f = clinic("IN");
        ResponseEntity<Map> sa = get(f.token(), "?specialty=derma&jurisdiction=SA");
        assertThat(sa.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sa.getBody().get("currency")).isEqualTo("SAR");
        assertThat(heads(sa)).hasSizeBetween(8, 15).allSatisfy(h -> {
            assertThat(h.get("sacHsn")).isNull();
            assertThat(h.get("taxCategory")).isEqualTo("taxable");
            assertThat(((Number) h.get("taxRatePercent")).doubleValue()).isEqualTo(15.0);
        });
        List<Map<String, Object>> ae = heads(get(f.token(), "?specialty=derma&jurisdiction=AE"));
        assertThat(head(ae, "LASER").get("taxCategory")).isEqualTo("taxable");
        assertThat(((Number) head(ae, "LASER").get("taxRatePercent")).doubleValue()).isEqualTo(5.0);
        assertThat(head(ae, "CONS").get("taxCategory")).isEqualTo("zero_rated");
        // every specialty x market is there, each with 8-15 heads
        for (String sp : List.of("dental", "derma", "gp", "paediatric", "physio", "other")) {
            for (String j : List.of("IN", "SA", "AE")) {
                assertThat(heads(get(f.token(), "?specialty=" + sp + "&jurisdiction=" + j))).hasSizeBetween(8, 15);
            }
        }
        assertThat(get(f.token(), "?specialty=cardio").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(f.token(), "?specialty=gp&jurisdiction=XX").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(f.token(), "?specialty=gp&jurisdiction=QA").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // AC4
    @Test
    void saveCreatesEveryHeadInOneTransactionWithOneChangeLogEntryEach() {
        Fx f = clinic("IN");
        ResponseEntity<Map> t = get(f.token(), "?specialty=derma");
        List<Map<String, Object>> hs = heads(t);
        head(hs, "CONS").put("baseAmount", 650); // the owner's edit
        head(hs, "CONS").put("name", "Skin consultation");
        ResponseEntity<Map> r = bulk(f.token(), t.getBody().get("templateId"), hs);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((List<?>) r.getBody().get("created")).hasSize(hs.size());
        assertThat((List<?>) r.getBody().get("skipped")).isEmpty();

        UUID tid = f.tenant().id();
        assertThat(count(tid, "SELECT count(*) FROM charge_catalogue WHERE tenant_id = ?", tid)).isEqualTo(hs.size());
        assertThat(count(tid, "SELECT count(*) FROM charge_price_changes WHERE tenant_id = ? AND field = 'base_amount' AND old_value IS NULL", tid)).isEqualTo(hs.size());
        assertThat(count(tid, "SELECT count(*) FROM charge_price_changes WHERE tenant_id = ?", tid)).isEqualTo(hs.size());
        assertThat(count(tid, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'setup.price_template_applied'", tid)).isEqualTo(1);
        Map<String, Object> cons = inTenantTx(tid, () -> jdbc.queryForMap(
                "SELECT name, base_amount, tax_category, sac_hsn, tax_rate_percent FROM charge_catalogue WHERE tenant_id = ? AND code = 'CONS'", tid));
        assertThat(cons.get("name")).isEqualTo("Skin consultation");
        assertThat((BigDecimal) cons.get("base_amount")).isEqualByComparingTo("650");
        assertThat(cons.get("tax_category")).isEqualTo("exempt");
        assertThat(cons.get("sac_hsn")).isEqualTo("999312");
        Map<String, Object> laser = inTenantTx(tid, () -> jdbc.queryForMap(
                "SELECT tax_category, tax_rate_percent FROM charge_catalogue WHERE tenant_id = ? AND code = 'LASER'", tid));
        assertThat(laser.get("tax_category")).isEqualTo("taxable");
        assertThat((BigDecimal) laser.get("tax_rate_percent")).isEqualByComparingTo("18");
        // the price master lists them in the order the owner saved (what the billing screen shows)
        List<Map> list = exchange("/v1/setup/charges", HttpMethod.GET, authed(f.token()), List.class).getBody();
        assertThat(list.stream().map(m -> m.get("code")).toList()).isEqualTo(hs.stream().map(h -> h.get("code")).toList());
        // the change log endpoint shows them
        Map page = exchange("/v1/setup/charges/changes?limit=100", HttpMethod.GET, authed(f.token()), Map.class).getBody();
        assertThat((List<?>) page.get("items")).hasSize(hs.size());
        // another clinic sees none of it
        Fx other = clinic("IN");
        assertThat(count(other.tenant().id(), "SELECT count(*) FROM charge_catalogue")).isZero();
        assertThat(count(other.tenant().id(), "SELECT count(*) FROM charge_price_changes")).isZero();
    }

    // AC4: all or nothing
    @Test
    void oneBadHeadWritesNothing() {
        Fx f = clinic("IN");
        ResponseEntity<Map> t = get(f.token(), "?specialty=dental");
        List<Map<String, Object>> hs = heads(t);
        hs.get(hs.size() - 1).put("baseAmount", -5);
        assertThat(bulk(f.token(), t.getBody().get("templateId"), hs).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        hs = heads(t);
        hs.get(hs.size() - 1).put("code", hs.get(0).get("code").toString().toLowerCase()); // the same code twice
        assertThat(bulk(f.token(), t.getBody().get("templateId"), hs).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        hs = heads(t);
        hs.get(hs.size() - 1).put("sacHsn", "12");
        assertThat(bulk(f.token(), t.getBody().get("templateId"), hs).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        hs = heads(t);
        hs.get(hs.size() - 1).put("name", "Bad\u0000name");
        assertThat(bulk(f.token(), t.getBody().get("templateId"), hs).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        UUID tid = f.tenant().id();
        assertThat(count(tid, "SELECT count(*) FROM charge_catalogue WHERE tenant_id = ?", tid)).isZero();
        assertThat(count(tid, "SELECT count(*) FROM charge_price_changes WHERE tenant_id = ?", tid)).isZero();
    }

    // AC5
    @Test
    void anExistingPriceMasterOnlyGetsTheMissingHeadsAndIsNeverOverwritten() {
        Fx f = clinic("IN");
        UUID tid = f.tenant().id();
        ResponseEntity<Map> own = exchange("/v1/setup/charges", HttpMethod.POST, authedJsonBody(f.token(), Map.of(
                "code", "CONS", "name", "Our consult", "category", "Consultation", "baseAmount", 999, "active", true,
                "effectiveFrom", "2026-01-01", "displayOrder", 1)), Map.class);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        exchange("/v1/setup/charges", HttpMethod.POST, authedJsonBody(f.token(), Map.of(
                "code", "laser", "name", "Our laser", "category", "Procedure", "baseAmount", 1, "active", true,
                "effectiveFrom", "2026-01-01", "displayOrder", 2)), Map.class);
        ResponseEntity<Map> t = get(f.token(), "?specialty=derma");
        List<Map<String, Object>> hs = heads(t);
        ResponseEntity<Map> r = bulk(f.token(), t.getBody().get("templateId"), hs);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((List<String>) r.getBody().get("skipped")).containsExactlyInAnyOrder("CONS", "LASER");
        assertThat((List<?>) r.getBody().get("created")).hasSize(hs.size() - 2);
        Map<String, Object> cons = inTenantTx(tid, () -> jdbc.queryForMap("SELECT name, base_amount, tax_category FROM charge_catalogue WHERE tenant_id = ? AND code = 'CONS'", tid));
        assertThat(cons.get("name")).isEqualTo("Our consult");
        assertThat((BigDecimal) cons.get("base_amount")).isEqualByComparingTo("999");
        assertThat(cons.get("tax_category")).isNull();
        assertThat(count(tid, "SELECT count(*) FROM charge_catalogue WHERE tenant_id = ? AND upper(code) = 'LASER'", tid)).isEqualTo(1);
        assertThat(count(tid, "SELECT count(*) FROM charge_price_changes WHERE tenant_id = ?", tid)).isEqualTo(hs.size() - 2);

        // applying again (a new intent) adds nothing and changes nothing
        ResponseEntity<Map> again = bulk(f.token(), t.getBody().get("templateId"), hs);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((List<?>) again.getBody().get("created")).isEmpty();
        assertThat((List<?>) again.getBody().get("skipped")).hasSize(hs.size());
        assertThat(count(tid, "SELECT count(*) FROM charge_catalogue WHERE tenant_id = ?", tid)).isEqualTo(hs.size());
        assertThat(count(tid, "SELECT count(*) FROM charge_price_changes WHERE tenant_id = ?", tid)).isEqualTo(hs.size() - 2);
    }

    @Test
    void aRetryWithTheSameIdempotencyKeyReplaysTheFirstAnswer() {
        Fx f = clinic("IN");
        ResponseEntity<Map> t = get(f.token(), "?specialty=gp");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(f.token());
        h.set("Idempotency-Key", UUID.randomUUID().toString());
        String body = objectMapper.writeValueAsString(Map.of("templateId", t.getBody().get("templateId"), "heads", heads(t)));
        ResponseEntity<Map> first = exchange("/v1/setup/charges/bulk", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        ResponseEntity<Map> retry = exchange("/v1/setup/charges/bulk", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getBody().get("created")).isEqualTo(first.getBody().get("created"));
        UUID tid = f.tenant().id();
        assertThat(count(tid, "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'setup.price_template_applied'", tid)).isEqualTo(1);
        HttpHeaders none = new HttpHeaders();
        none.setContentType(MediaType.APPLICATION_JSON);
        none.setBearerAuth(f.token());
        assertThat(exchange("/v1/setup/charges/bulk", HttpMethod.POST, new HttpEntity<>(body, none), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void ksaClinicSavesVatHeads() {
        Fx f = clinic("SA");
        ResponseEntity<Map> t = get(f.token(), "?specialty=physio");
        assertThat(t.getBody().get("jurisdiction")).isEqualTo("SA");
        assertThat(bulk(f.token(), t.getBody().get("templateId"), heads(t)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tid = f.tenant().id();
        assertThat(count(tid, "SELECT count(*) FROM charge_catalogue WHERE tenant_id = ? AND tax_category = 'taxable' AND tax_rate_percent = 15 AND sac_hsn IS NULL", tid))
                .isEqualTo(heads(t).size());
    }

    @Test
    void marketTaxAndOwnerRulesAreEnforced() {
        Fx f = clinic("IN");
        ResponseEntity<Map> in = get(f.token(), "?specialty=derma");
        Object id = in.getBody().get("templateId");
        // a KSA list cannot be applied to an Indian clinic
        ResponseEntity<Map> sa = get(f.token(), "?specialty=derma&jurisdiction=SA");
        ResponseEntity<Map> wrong = bulk(f.token(), sa.getBody().get("templateId"), heads(sa));
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(wrong.getBody().get("type")).asString().contains("template-market-mismatch");
        // a rate billing does not have for a starter list, and a rate on an exempt head, are refused
        List<Map<String, Object>> hs = heads(in);
        head(hs, "LASER").put("taxRatePercent", 7);
        assertThat(bulk(f.token(), id, hs).getBody().get("type")).asString().contains("rate-not-allowed");
        hs = heads(in);
        head(hs, "CONS").put("taxRatePercent", 18);
        assertThat(bulk(f.token(), id, hs).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(bulk(f.token(), UUID.randomUUID(), heads(in)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(bulk(f.token(), id, List.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // setup:edit without being the owner: refused; setup:view only: may read the lists, may not save
        String manager = tokenWith(f, "Manager", new ModuleGrant("setup", true, true, true, true, true, true, true));
        ResponseEntity<Map> notOwner = bulk(manager, id, heads(in));
        assertThat(notOwner.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(notOwner.getBody().get("type")).asString().contains("NOT_OWNER");
        String viewer = tokenWith(f, "Viewer", new ModuleGrant("setup", true, false, false, false, false, false, false));
        assertThat(get(viewer, "?specialty=gp").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bulk(viewer, id, heads(in)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        UUID tid = f.tenant().id();
        assertThat(count(tid, "SELECT count(*) FROM charge_catalogue WHERE tenant_id = ?", tid)).isZero();
    }

    @Test
    void templatesAreReadOnlyReferenceDataForTheApiRole() {
        JdbcTemplate app = new JdbcTemplate(dataSourceFor(APP_ROLE, APP_ROLE_PW));
        assertThat(app.queryForObject("SELECT count(*) FROM price_templates", Long.class)).isEqualTo(18L);
        assertThatThrownBy(() -> app.update("UPDATE price_templates SET version = version + 1")).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> app.update("DELETE FROM price_templates")).rootCause().hasMessageContaining("permission denied");
        assertThat(SU.queryForObject("SELECT count(*) FROM price_templates WHERE reviewed", Long.class)).isZero();
    }
}

package com.nabd.hms.clinical;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CLN-019: the regional drug master import (dry-run diff, atomic activation, retire, failure half-way), the read endpoints
 * and the pharmacy item link. EVERY drug in this file is SYNTHETIC test data written by the test; the only real-looking
 * product is the story's own acceptance example (Augmentin 625 Duo = amoxicillin 500 mg + clavulanic acid 125 mg tablet,
 * class penicillin, schedule H). No licensed KB content is used or implied.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class DrugMasterApiTest extends ApiTestBase {

    private static final String IMPORTS = "/internal/v1/drug-master/imports";
    private static final String HEADER = "{\"kb\":\"SYNTHETIC-TEST-KB\",\"month\":\"2026-10\",\"region\":\"%s\",\"kind\":\"%s\","
            + "\"classes\":[{\"id\":\"penicillin\",\"name\":\"Penicillin\"},{\"id\":\"synthetic-class\",\"name\":\"Synthetic class\"}]}";

    @Autowired DrugMasterImportService importService;
    @Autowired DrugMasterService drugService;
    @Autowired JdbcTemplate appJdbc;

    private Path dir;
    private String operator;

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createTempDirectory("drug-feeds");
        ReflectionTestUtils.setField(target(importService), "feedDir", dir.toRealPath());
        clean();
        operator = platformLoginAndGetAccessToken(seedOperator("kbimport-" + UUID.randomUUID() + "@nabd.internal", "super_admin", false));
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private static Object target(Object bean) {
        return AopProxyUtils.getSingletonTarget(bean) != null ? AopProxyUtils.getSingletonTarget(bean) : bean;
    }

    private void clean() {
        JdbcTemplate su = new JdbcTemplate(superuserDataSource());
        su.update("UPDATE charge_catalogue SET drug_id = NULL WHERE drug_id IS NOT NULL");
        su.update("DELETE FROM drug_ingredients");
        su.update("DELETE FROM drug_master");
        su.update("DELETE FROM drug_master_stage");
        su.update("DELETE FROM drug_master_imports");
        su.update("DELETE FROM ingredients");
        su.update("DELETE FROM drug_classes");
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    // ── feed helpers ─────────────────────────────────────────────────────

    private static String product(String kbId, String brand, String schedule, String ingredients) {
        return "{\"kbId\":\"" + kbId + "\",\"brand\":\"" + brand + "\",\"strength\":\"1 unit\",\"form\":\"tablet\",\"schedule\":["
                + schedule + "],\"ingredients\":[" + ingredients + "]}";
    }

    private static String ing(String id, String name, String amount, String classIds) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"amount\":" + amount + ",\"unit\":\"mg\",\"classIds\":[" + classIds + "]}";
    }

    private static String augmentin() {
        return product("AUG625", "Augmentin 625 Duo", "\"H\"", ing("amoxicillin", "amoxicillin", "500", "\"penicillin\"") + ","
                + ing("clavulanic-acid", "clavulanic acid", "125", ""));
    }

    private static String synthetic(int n, boolean scheduled) {
        String k = String.format("%03d", n);
        return product("SYN" + k, "Synthetic Brand " + k, scheduled ? "\"H1\"" : "", ing("syn-ing-" + k, "synthetic ingredient " + k, "" + (n * 10), "\"synthetic-class\""));
    }

    private String write(String name, String region, String kind, List<String> products) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add(HEADER.formatted(region, kind));
        lines.addAll(products);
        Files.write(dir.resolve(name), lines);
        return "file:" + name;
    }

    private ResponseEntity<Map> startImport(String uri, String kind, Boolean dryRun) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("feedUri", uri, "kind", kind));
        if (dryRun != null) {
            body.put("dryRun", dryRun);
        }
        return exchange(IMPORTS, HttpMethod.POST, authedJsonBody(operator, body), Map.class);
    }

    private Map<String, Object> awaitImport(Object importId) throws Exception {
        for (int i = 0; i < 300; i++) {
            Map<String, Object> st = exchange(IMPORTS + "/" + importId, HttpMethod.GET, authed(operator), Map.class).getBody();
            if (!"running".equals(st.get("status"))) {
                return st;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("import still running");
    }

    private String imported(String uri, String kind, boolean dryRun) throws Exception {
        ResponseEntity<Map> r = startImport(uri, kind, dryRun);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String id = (String) r.getBody().get("importId");
        assertThat(awaitImport(id).get("status")).isEqualTo("validated");
        return id;
    }

    private ResponseEntity<Map> activate(String importId, String csoAckRef) {
        Map<String, Object> body = csoAckRef == null ? Map.of() : Map.of("csoAckRef", csoAckRef);
        return exchange(IMPORTS + "/" + importId + "/activate", HttpMethod.POST, authedJsonBody(operator, body), Map.class);
    }

    private String clinicToken(SeededTenant tenant, String email, ModuleGrant... grants) {
        UUID role = seedRole(tenant.id(), "Role" + UUID.randomUUID().toString().substring(0, 6), false, grants);
        return loginAndGetAccessToken(seedStaff(tenant, role, email, "+9196" + (10000000 + (int) (Math.random() * 89999999)), false));
    }

    private static String code(ResponseEntity<Map> r) {
        Object t = r.getBody() == null ? null : r.getBody().get("type");
        return t == null ? "" : t.toString();
    }

    // ── import, diff, activation ─────────────────────────────────────────

    @Test
    void dryRunShowsTheDiffAndChangesNothing_thenARealImportActivatesInOneVersion() throws Exception {
        String uri = write("full1.jsonl", "IN", "full", List.of(augmentin(), synthetic(1, true), synthetic(2, false)));

        String dry = imported(uri, "full", true);
        Map<String, Object> st = awaitImport(dry);
        assertThat((Map) st.get("diff")).containsEntry("added", 3).containsEntry("changed", 0).containsEntry("retired", 0);
        assertThat(st).containsEntry("dryRun", true).containsEntry("kb", "SYNTHETIC-TEST-KB").containsEntry("month", "2026-10");
        assertThat(su().queryForObject("SELECT count(*) FROM drug_master", Integer.class)).isZero();
        assertThat(activate(dry, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT); // a dry run is never activatable
        assertThat(su().queryForObject("SELECT count(*) FROM drug_master_stage", Integer.class)).isZero();

        String real = imported(uri, "full", false);
        assertThat(real).isNotEqualTo(dry);
        ResponseEntity<Map> act = activate(real, null);
        assertThat(act.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(act.getBody()).containsEntry("version", 1);
        assertThat(su().queryForObject("SELECT count(*) FROM drug_master WHERE status = 'active' AND version = 1", Integer.class)).isEqualTo(3);
        assertThat(su().queryForObject("SELECT count(*) FROM drug_master_stage", Integer.class)).isZero();
        assertThat(awaitImport(real)).containsEntry("status", "active").containsEntry("version", 1);
        // classes and ingredients were loaded with the version
        assertThat(su().queryForObject("SELECT count(*) FROM drug_classes", Integer.class)).isEqualTo(2);
        assertThat(su().queryForObject("SELECT count(*) FROM ingredients", Integer.class)).isEqualTo(4);
    }

    @Test
    void augmentinResolvesToItsIngredientsClassAndSchedule_andSearchFindsIt() throws Exception {
        activate(imported(write("a.jsonl", "IN", "full", List.of(augmentin(), synthetic(1, false))), "full", false), null);
        SeededTenant t = seedTenant();
        String token = clinicToken(t, "doc-" + UUID.randomUUID() + "@a.com", fullGrant("clinical"));

        List<Map> hits = exchange("/v1/clinical/drugs?q=Augmentin 625 Duo", HttpMethod.GET, authed(token), List.class).getBody();
        assertThat(hits).hasSize(1);
        String id = (String) hits.get(0).get("id");
        ResponseEntity<Map> d = exchange("/v1/clinical/drugs/" + id, HttpMethod.GET, authed(token), Map.class);
        assertThat(d.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> b = d.getBody();
        assertThat(b).containsEntry("brand", "Augmentin 625 Duo").containsEntry("form", "tablet").containsEntry("status", "active");
        assertThat((List) b.get("generic")).containsExactly("amoxicillin", "clavulanic acid");
        assertThat((List) b.get("schedule")).containsExactly("H");
        assertThat((List<Map>) b.get("ingredients")).extracting(m -> m.get("name") + " " + ((Number) m.get("amount")).intValue() + " " + m.get("unit"))
                .containsExactly("amoxicillin 500 mg", "clavulanic acid 125 mg");
        assertThat((List<Map>) b.get("classes")).extracting(m -> m.get("id")).containsExactly("penicillin");
        assertThat((Map) b.get("source")).containsEntry("kb", "SYNTHETIC-TEST-KB").containsEntry("month", "2026-10");
        // ingredient name and a one-word prefix also find it; a generic-only word does too
        assertThat(exchange("/v1/clinical/drugs?q=amox&limit=5", HttpMethod.GET, authed(token), List.class).getBody()).hasSize(1);
        assertThat(exchange("/v1/clinical/drugs?q=zzzz", HttpMethod.GET, authed(token), List.class).getBody()).isEmpty();
    }

    @Test
    void goldenMappingOfFiftySyntheticBrands() throws Exception {
        List<String> products = new ArrayList<>();
        for (int n = 1; n <= 50; n++) {
            products.add(synthetic(n, n % 2 == 0));
        }
        activate(imported(write("g.jsonl", "IN", "full", products), "full", false), null);
        SeededTenant t = seedTenant();
        String token = clinicToken(t, "g-" + UUID.randomUUID() + "@a.com", fullGrant("clinical"));
        for (int n = 1; n <= 50; n++) {
            String k = String.format("%03d", n);
            Map<String, Object> b = exchange("/v1/clinical/drugs/in-SYN" + k, HttpMethod.GET, authed(token), Map.class).getBody();
            assertThat(b).containsEntry("brand", "Synthetic Brand " + k);
            assertThat((List) b.get("generic")).containsExactly("synthetic ingredient " + k);
            assertThat(((Number) ((Map) ((List) b.get("ingredients")).get(0)).get("amount")).intValue()).isEqualTo(n * 10);
            assertThat((List) b.get("schedule")).isEqualTo(n % 2 == 0 ? List.of("H1") : List.of());
        }
    }

    @Test
    void sameFeedTwiceIsOneImport_andActivatingTwiceIsHarmless() throws Exception {
        String uri = write("i.jsonl", "IN", "full", List.of(augmentin()));
        ResponseEntity<Map> first = startImport(uri, "full", false);
        ResponseEntity<Map> second = startImport(uri, "full", false);
        assertThat(second.getBody().get("importId")).isEqualTo(first.getBody().get("importId"));
        String id = (String) first.getBody().get("importId");
        awaitImport(id);
        assertThat(activate(id, null).getBody()).containsEntry("version", 1);
        assertThat(activate(id, null).getBody()).containsEntry("version", 1);
        assertThat(startImport(uri, "full", false).getBody().get("importId")).isEqualTo(id);
        assertThat(su().queryForObject("SELECT count(*) FROM drug_master_imports WHERE status = 'active'", Integer.class)).isEqualTo(1);
        assertThat(su().queryForObject("SELECT count(*) FROM drug_master", Integer.class)).isEqualTo(1);
    }

    @Test
    void retiredProductStaysResolvableButLeavesSearch_andSafetyRelevantChangesNeedTheCsoAck() throws Exception {
        activate(imported(write("v1.jsonl", "IN", "full", List.of(augmentin(), synthetic(1, false), synthetic(2, false))), "full", false), null);
        SeededTenant t = seedTenant();
        String token = clinicToken(t, "r-" + UUID.randomUUID() + "@a.com", fullGrant("clinical"));

        // version 2: SYN002 gone from the full feed (retired), SYN001 gains a schedule flag (safety relevant), SYN003 new
        String uri2 = write("v2.jsonl", "IN", "full", List.of(augmentin(), synthetic(1, true), synthetic(3, false)));
        String v2 = imported(uri2, "full", false);
        Map diff = (Map) awaitImport(v2).get("diff");
        assertThat(diff).containsEntry("added", 1).containsEntry("changed", 1).containsEntry("retired", 1).containsEntry("safetyRelevant", 1);
        assertThat((Map) diff.get("samples")).containsEntry("retired", List.of("SYN002")).containsEntry("added", List.of("SYN003"));

        ResponseEntity<Map> noAck = activate(v2, null);
        assertThat(noAck.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(noAck)).contains("CSO_ACK_REQUIRED");
        assertThat(su().queryForObject("SELECT count(*) FROM drug_master WHERE version = 2", Integer.class)).isZero(); // nothing half-applied
        assertThat(activate(v2, "CSO-ACK-TEST-1").getBody()).containsEntry("version", 2);
        assertThat(startImport(uri2, "full", false).getBody().get("importId")).isEqualTo(v2); // idempotent: the same feed is the active import
        assertThat(su().queryForObject("SELECT cso_ack_ref FROM drug_master_imports WHERE version = 2", String.class)).isEqualTo("CSO-ACK-TEST-1");

        // retired: resolvable by id, hidden from search, not linkable to a new item
        Map<String, Object> retired = exchange("/v1/clinical/drugs/in-SYN002", HttpMethod.GET, authed(token), Map.class).getBody();
        assertThat(retired).containsEntry("status", "retired").containsEntry("brand", "Synthetic Brand 002");
        List<Map> hits = exchange("/v1/clinical/drugs?q=Synthetic", HttpMethod.GET, authed(token), List.class).getBody();
        assertThat(hits).extracting(m -> m.get("id")).containsExactlyInAnyOrder("in-SYN001", "in-SYN003");
        assertThat((List) exchange("/v1/clinical/drugs/in-SYN001", HttpMethod.GET, authed(token), Map.class).getBody().get("schedule")).containsExactly("H1");
        // unchanged products keep their old version; the changed one moved to 2
        assertThat(su().queryForObject("SELECT version FROM drug_master WHERE id = 'in-AUG625'", Integer.class)).isEqualTo(1);
        assertThat(su().queryForObject("SELECT version FROM drug_master WHERE id = 'in-SYN001'", Integer.class)).isEqualTo(2);
    }

    @Test
    void deltaFeedOnlyTouchesListedProducts_andCanRetireOne() throws Exception {
        activate(imported(write("f.jsonl", "IN", "full", List.of(augmentin(), synthetic(1, false))), "full", false), null);
        String retire = "{\"kbId\":\"SYN001\",\"brand\":\"Synthetic Brand 001\",\"status\":\"retired\",\"ingredients\":[]}";
        String d = imported(write("d.jsonl", "IN", "delta", List.of(retire)), "delta", false);
        assertThat((Map) awaitImport(d).get("diff")).containsEntry("retired", 1).containsEntry("added", 0).containsEntry("changed", 0);
        assertThat(activate(d, null).getBody()).containsEntry("version", 2);
        assertThat(su().queryForObject("SELECT status FROM drug_master WHERE id = 'in-AUG625'", String.class)).isEqualTo("active");
        assertThat(su().queryForObject("SELECT status FROM drug_master WHERE id = 'in-SYN001'", String.class)).isEqualTo("retired");
        // the product row kept its ingredients (retiring does not erase what was prescribed)
        assertThat(su().queryForObject("SELECT count(*) FROM drug_ingredients WHERE drug_id = 'in-SYN001'", Integer.class)).isEqualTo(1);
    }

    @Test
    void anImportThatFailsHalfWayNeverActivatesAnything_andARetryWithAFixedFeedWorks() throws Exception {
        activate(imported(write("ok.jsonl", "IN", "full", List.of(augmentin())), "full", false), null);
        List<String> big = new ArrayList<>();
        for (int n = 1; n <= 1200; n++) { // >1 stage batch, so rows are already staged when the bad record is hit
            big.add(synthetic(n, false));
        }
        List<String> broken = new ArrayList<>(big);
        broken.add("{\"kbId\":\"BAD\",\"brand\":\"Synthetic Bad\",\"ingredients\":[]}"); // active product without ingredient
        String uri = write("bad.jsonl", "IN", "full", broken);
        ResponseEntity<Map> r = startImport(uri, "full", false);
        Map<String, Object> st = awaitImport(r.getBody().get("importId"));
        assertThat(st).containsEntry("status", "failed");
        assertThat((String) st.get("error")).contains("record 1202").contains("ingredient");
        assertThat(su().queryForObject("SELECT count(*) FROM drug_master_stage", Integer.class)).isZero(); // staged rows cleaned
        assertThat(su().queryForObject("SELECT count(*) FROM drug_master", Integer.class)).isEqualTo(1); // live data untouched
        assertThat(su().queryForObject("SELECT version FROM drug_master_imports WHERE status = 'active'", Integer.class)).isEqualTo(1);
        assertThat(activate((String) r.getBody().get("importId"), "x").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // retry of the SAME broken file starts a new import (a failed one is never reused) and fails the same way
        assertThat(startImport(uri, "full", false).getBody().get("importId")).isNotEqualTo(r.getBody().get("importId"));
        // the fixed feed imports and activates
        String fixed = imported(write("fixed.jsonl", "IN", "full", big), "full", false);
        assertThat(activate(fixed, "CSO-ACK-TEST-2").getBody()).containsEntry("version", 2);
        assertThat(su().queryForObject("SELECT count(*) FROM drug_master WHERE status = 'active'", Integer.class)).isEqualTo(1200);
    }

    @Test
    void anActivationThatFailsInsideTheSwitchRollsBackEverything() throws Exception {
        activate(imported(write("a1.jsonl", "IN", "full", List.of(augmentin())), "full", false), null);
        String id = imported(write("a2.jsonl", "IN", "full", List.of(augmentin(), synthetic(1, false), synthetic(2, false))), "full", false);
        // corrupt one staged record (blank brand) after validation so the switch fails after it has already written ingredients
        su().update("UPDATE drug_master_stage SET product = jsonb_set(product, '{brand}', '\"\"') WHERE import_id = ?::uuid AND kb_id = 'SYN002'", id);
        assertThat(activate(id, null).getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(su().queryForObject("SELECT count(*) FROM drug_master", Integer.class)).isEqualTo(1);
        assertThat(su().queryForObject("SELECT count(*) FROM ingredients", Integer.class)).isEqualTo(2);
        assertThat(su().queryForObject("SELECT status FROM drug_master_imports WHERE id = ?::uuid", String.class, id)).isEqualTo("validated");
        assertThat(su().queryForObject("SELECT version FROM drug_master_imports WHERE status = 'active'", Integer.class)).isEqualTo(1);
    }

    @Test
    void activationPublishesOneVersionEventPerTenantOfTheRegion() throws Exception {
        SeededTenant t = seedTenant();
        activate(imported(write("e.jsonl", "IN", "full", List.of(augmentin(), synthetic(1, false))), "full", false), null);
        List<Map<String, Object>> evs = inTenantTx(t.id(), () -> jdbc.queryForList(
                "SELECT envelope::text AS e FROM outbox_cloudevents WHERE tenant_id = ? AND envelope->>'type' = 'clinical.drug_master.version_activated.v1'", t.id()));
        assertThat(evs).hasSize(1);
        assertThat(((String) evs.get(0).get("e")).replace(" ", "")).contains("\"region\":\"IN\"").contains("\"drugMasterVersion\":1").contains("\"added\":2")
                .doesNotContain("Augmentin");
    }

    // ── guards ───────────────────────────────────────────────────────────

    @Test
    void importRefusesBadFeedUrisAndUnauthorisedCallers() throws Exception {
        write("x.jsonl", "IN", "full", List.of(augmentin()));
        assertThat(startImport("https://example.invalid/feed", "full", true).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(startImport("file:../etc-passwd", "full", true).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(startImport("file:missing.jsonl", "full", true).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(startImport("file:x.jsonl", "delta", true).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT); // header says full
        assertThat(exchange(IMPORTS, HttpMethod.POST, authedJsonBody(operator, Map.of("feedUri", "file:x.jsonl", "kind", "weekly")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Files.writeString(dir.resolve("junk.jsonl"), "not json\n");
        assertThat(startImport("file:junk.jsonl", "full", true).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        String other = platformLoginAndGetAccessToken(seedOperator("impl-" + UUID.randomUUID() + "@nabd.internal", "implementation", false));
        assertThat(exchange(IMPORTS, HttpMethod.POST, authedJsonBody(other, Map.of("feedUri", "file:x.jsonl", "kind", "full")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        SeededTenant t = seedTenant();
        String staff = clinicToken(t, "s-" + UUID.randomUUID() + "@a.com", fullGrant("clinical"), fullGrant("pharmacy"));
        assertThat(exchange(IMPORTS, HttpMethod.POST, authedJsonBody(staff, Map.of("feedUri", "file:x.jsonl", "kind", "full")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED); // a clinic token is not a platform token
        assertThat(exchange(IMPORTS + "/" + UUID.randomUUID(), HttpMethod.GET, authed(operator), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(activate(UUID.randomUUID().toString(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ReflectionTestUtils.setField(target(importService), "feedDir", null);
        assertThat(startImport("file:x.jsonl", "full", true).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void readEndpointsAreGatedAndRegional() throws Exception {
        activate(imported(write("p.jsonl", "IN", "full", List.of(augmentin())), "full", false), null);
        // an SA product (SFDA code as kb id) is stored but an IN clinic never sees it
        String sa = imported(write("sa.jsonl", "SA", "full", List.of(product("SFDA-0001", "Synthetic Saudi Brand", "\"SFDA-controlled\"",
                ing("syn-sa", "synthetic sa ingredient", "5", "")))), "full", false);
        assertThat(activate(sa, null).getBody()).containsEntry("version", 1); // versions are per region
        assertThat(su().queryForObject("SELECT schedule[1] FROM drug_master WHERE region = 'SA' AND kb_id = 'SFDA-0001'", String.class)).isEqualTo("SFDA-controlled");

        SeededTenant t = seedTenant();
        String doc = clinicToken(t, "d-" + UUID.randomUUID() + "@a.com", fullGrant("clinical"));
        assertThat(exchange("/v1/clinical/drugs/sa-SFDA-0001", HttpMethod.GET, authed(doc), Map.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> missing = exchange("/v1/clinical/drugs/in-NOPE", HttpMethod.GET, authed(doc), Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(missing)).contains("DRUG_NOT_FOUND");
        String nurseLike = clinicToken(t, "q-" + UUID.randomUUID() + "@a.com", fullGrant("queue"));
        assertThat(exchange("/v1/clinical/drugs/in-AUG625", HttpMethod.GET, authed(nurseLike), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String pharm = clinicToken(t, "ph-" + UUID.randomUUID() + "@a.com", new ModuleGrant("pharmacy", true, false, false, false, false, false, false));
        assertThat(exchange("/v1/clinical/drugs/in-AUG625", HttpMethod.GET, authed(pharm), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/clinical/drugs/in-AUG625", HttpMethod.GET, org.springframework.http.HttpEntity.EMPTY, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange("/v1/clinical/drugs?q=%20%21", HttpMethod.GET, authed(doc), List.class).getBody()).isEmpty(); // no searchable word
    }

    @Test
    void theApplicationRoleCanReadButNeverWriteTheReferenceTables() throws Exception {
        activate(imported(write("w.jsonl", "IN", "full", List.of(augmentin())), "full", false), null);
        assertThat(appJdbc.queryForObject("SELECT count(*) FROM drug_master", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> appJdbc.update("UPDATE drug_master SET brand = 'x'")).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> appJdbc.update("DELETE FROM drug_ingredients")).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> appJdbc.update("INSERT INTO drug_classes (id, name) VALUES ('x','x')")).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> appJdbc.queryForObject("SELECT count(*) FROM drug_master_stage", Integer.class)).rootCause().hasMessageContaining("permission denied");
    }

    // ── pharmacy item link ───────────────────────────────────────────────

    @Test
    void ownerMapsAPharmacyItemToADrug_isRxIsOnlySuggested_pricingUntouched() throws Exception {
        activate(imported(write("ph.jsonl", "IN", "full", List.of(augmentin(), synthetic(1, false), synthetic(2, false))), "full", false), null);
        SeededTenant t = seedTenant();
        String token = clinicToken(t, "own-" + UUID.randomUUID() + "@a.com", fullGrant("pharmacy"), fullGrant("clinical"));
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "test setup")), Map.class);
        Map item = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "House item", "isRx", false, "hsnCode", "3004", "price", 350.00, "taxRatePercent", 12.00, "stockQty", 7)), Map.class).getBody();
        String itemId = (String) item.get("id");
        assertThat(item.get("drugId")).isNull();

        ResponseEntity<Map> linked = exchange("/v1/pharmacy/items/" + itemId + "/drug-link", HttpMethod.PUT, authedJsonBody(token, Map.of("drugId", "in-AUG625")), Map.class);
        assertThat(linked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(linked.getBody()).containsEntry("itemId", itemId).containsEntry("drugId", "in-AUG625").containsEntry("suggestedIsRx", true);
        // idempotent, and an unscheduled product suggests no Rx
        assertThat(exchange("/v1/pharmacy/items/" + itemId + "/drug-link", HttpMethod.PUT, authedJsonBody(token, Map.of("drugId", "in-AUG625")), Map.class).getBody())
                .containsEntry("suggestedIsRx", true);
        assertThat(exchange("/v1/pharmacy/items/" + itemId + "/drug-link", HttpMethod.PUT, authedJsonBody(token, Map.of("drugId", "in-SYN001")), Map.class).getBody())
                .containsEntry("drugId", "in-SYN001").containsEntry("suggestedIsRx", false);

        List<Map> items = exchange("/v1/pharmacy/items", HttpMethod.GET, authed(token), List.class).getBody();
        Map after = items.stream().filter(m -> itemId.equals(m.get("id"))).findFirst().orElseThrow();
        assertThat(after).containsEntry("drugId", "in-SYN001").containsEntry("isRx", false).containsEntry("stockQty", 7);
        assertThat(((Number) after.get("price")).doubleValue()).isEqualTo(350.0);

        ResponseEntity<Map> unknown = exchange("/v1/pharmacy/items/" + itemId + "/drug-link", HttpMethod.PUT, authedJsonBody(token, Map.of("drugId", "in-NOPE")), Map.class);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(unknown)).contains("DRUG_NOT_FOUND");
        assertThat(exchange("/v1/pharmacy/items/" + UUID.randomUUID() + "/drug-link", HttpMethod.PUT, authedJsonBody(token, Map.of("drugId", "in-AUG625")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/pharmacy/items/" + itemId + "/drug-link", HttpMethod.PUT, authedJsonBody(token, Map.of("drugId", "")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String viewOnly = clinicToken(t, "v-" + UUID.randomUUID() + "@a.com", new ModuleGrant("pharmacy", true, false, false, false, false, false, false));
        assertThat(exchange("/v1/pharmacy/items/" + itemId + "/drug-link", HttpMethod.PUT, authedJsonBody(viewOnly, Map.of("drugId", "in-AUG625")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // a product retired later cannot be linked to another item, the existing link stays
        String v2 = imported(write("ph2.jsonl", "IN", "full", List.of(augmentin(), synthetic(2, false))), "full", false);
        activate(v2, "CSO-ACK-TEST-3");
        assertThat(exchange("/v1/pharmacy/items/" + itemId + "/drug-link", HttpMethod.PUT, authedJsonBody(token, Map.of("drugId", "in-SYN001")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT drug_id FROM charge_catalogue WHERE id = ?::uuid", String.class, itemId))).isEqualTo("in-SYN001");
        // another clinic cannot link this tenant's item (tenant isolation)
        SeededTenant other = seedTenant();
        String otherToken = clinicToken(other, "o-" + UUID.randomUUID() + "@a.com", fullGrant("pharmacy"));
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(otherToken, Map.of("mode", "hybrid", "reason", "test setup")), Map.class);
        assertThat(exchange("/v1/pharmacy/items/" + itemId + "/drug-link", HttpMethod.PUT, authedJsonBody(otherToken, Map.of("drugId", "in-AUG625")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}

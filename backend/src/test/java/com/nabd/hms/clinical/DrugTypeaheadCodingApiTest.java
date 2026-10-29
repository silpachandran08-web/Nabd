package com.nabd.hms.clinical;

import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLN-020 (drug typeahead over the drug master, usage ranking, client index) and CLN-021 (coded Rx lines and allergies,
 * generic-first display, coding suggestions). Every product and ingredient here is SYNTHETIC test data written by the test
 * (ids start with t13-); amoxicillin/Mox/Amoxil follow the story's own examples, Zorbexillin is invented so that no
 * dictionary text knows it.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class DrugTypeaheadCodingApiTest extends ApiTestBase {

    @Autowired PrescriptionSignedHandler signedHandler;
    @Autowired RxCodingSuggestionService suggestions;
    @Autowired ObjectMapper json;
    @Autowired com.nabd.hms.common.OutboxDispatcher dispatcher;

    // CLN-005: signing needs a registered clinician; every doctor this class seeds is one.
    @Override
    protected SeededStaff seedStaff(SeededTenant tenant, UUID roleId, String email, String mobilePhone, boolean mfaEnabled,
                                    boolean emailVerified, boolean mobileVerified) {
        return com.nabd.hms.support.ClinicianFixture.licensed(
                super.seedStaff(tenant, roleId, email, mobilePhone, mfaEnabled, emailVerified, mobileVerified));
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    @BeforeEach
    void seedMaster() {
        clean();
        JdbcTemplate su = su();
        su.update("INSERT INTO drug_classes (id, name) VALUES ('t13-penicillin', 'Penicillin (t13)') ON CONFLICT DO NOTHING");
        ingredient("t13-amoxicillin", "Amoxicillin", "t13-penicillin");
        ingredient("t13-zorbexillin", "Zorbexillin", "t13-penicillin");
        ingredient("t13-paracetamol", "Paracetamol", null);
        product("in-t13-mox", "IN", "Mox", "500 mg", "capsule", "active", "t13-amoxicillin");
        product("in-t13-amoxil", "IN", "Amoxil", "500 mg", "capsule", "active", "t13-amoxicillin");
        product("in-t13-zorbex", "IN", "Zorbex", "250 mg", "tablet", "active", "t13-zorbexillin");
        product("in-t13-para", "IN", "Parafast", "500 mg", "tablet", "active", "t13-paracetamol");
        product("in-t13-old", "IN", "Amoold", "500 mg", "capsule", "retired", "t13-amoxicillin");
        product("sa-t13-amosa", "SA", "Amosa", "500 mg", "capsule", "active", "t13-amoxicillin");
    }

    @AfterEach
    void clean() {
        JdbcTemplate su = su();
        su.update("UPDATE charge_catalogue SET drug_id = NULL WHERE drug_id LIKE '%t13-%'");
        su.update("DELETE FROM drug_ingredients WHERE drug_id LIKE '%t13-%'");
        su.update("DELETE FROM drug_master WHERE id LIKE '%t13-%'");
        su.update("DELETE FROM ingredients WHERE id LIKE 't13-%'");
        su.update("DELETE FROM drug_classes WHERE id LIKE 't13-%'");
    }

    private void ingredient(String id, String name, String classId) {
        su().update("INSERT INTO ingredients (id, name, class_ids) VALUES (?, ?, ?::text[]) ON CONFLICT DO NOTHING",
                id, name, classId == null ? "{}" : "{" + classId + "}");
    }

    private void product(String id, String region, String brand, String strength, String form, String status, String ingredientId) {
        JdbcTemplate su = su();
        su.update("INSERT INTO drug_master (id, region, kb_id, brand, strength, form, status, version) VALUES (?,?,?,?,?,?,?,1)",
                id, region, id, brand, strength, form, status);
        su.update("INSERT INTO drug_ingredients (drug_id, ingredient_id) VALUES (?, ?)", id, ingredientId);
        su.update("UPDATE drug_master m SET search_text = to_tsvector('simple', m.brand || ' ' || m.strength || ' ' || m.form || ' ' || " +
                "(SELECT string_agg(g.name, ' ') FROM drug_ingredients d JOIN ingredients g ON g.id = d.ingredient_id WHERE d.drug_id = m.id)) WHERE m.id = ?", id);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private record Doc(String token, UUID id, SeededTenant tenant) {
    }

    private Doc doctor(String tag) {
        SeededTenant tenant = seedTenant();
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, tag + "@t13.com", "+9198" + String.format("%08d", Math.abs(tag.hashCode()) % 100000000), false);
        return new Doc(loginAndGetAccessToken(doc), doc.id(), tenant);
    }

    private Doc saDoctor(String tag) {
        SeededTenant tenant = seedTenant();
        // region_code is immutable for a real clinic (REGION_IMMUTABLE trigger); the fixture bypasses it for this one tenant.
        try (java.sql.Connection c = superuserDataSource().getConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.executeUpdate("UPDATE tenants SET region_code = 'SA', region = 'SA' WHERE id = '" + tenant.id() + "'");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        UUID roleId = seedFullAccessRole(tenant.id());
        SeededStaff doc = seedStaff(tenant, roleId, tag + "@t13.com", "+9665" + String.format("%08d", Math.abs(tag.hashCode()) % 100000000), false);
        return new Doc(loginAndGetAccessToken(doc), doc.id(), tenant);
    }

    private String patient(Doc d, String name, String phone) {
        return (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(d.token(), Map.of(
                "name", name, "phone", phone, "dob", "1990-01-01", "gender", "male")), Map.class).getBody().get("id");
    }

    private String checkIn(Doc d, String patientId) {
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(d.token(), Map.of(
                "patientId", patientId, "doctorId", d.id().toString())), Map.class).getBody().get("id");
    }

    private ResponseEntity<Map> save(Doc d, String entry, Map<String, Object> item) {
        return exchange("/v1/clinical/prescriptions/" + entry, HttpMethod.PATCH, authedJsonBody(d.token(), Map.of("items", List.of(item))), Map.class);
    }

    private List<Map> search(Doc d, String q) {
        return http.exchange(java.net.URI.create(url("/v1/clinical/drugs/search?q=" + q + "&limit=8")), HttpMethod.GET,
                authed(d.token()), List.class).getBody();
    }

    private static List<Map> warnings(ResponseEntity<Map> rx, int item) {
        return (List<Map>) ((Map) ((List) rx.getBody().get("items")).get(item)).get("warnings");
    }

    private Map signedRx(Doc d, String entry, Map<String, Object> item) {
        assertThat(save(d, entry, item).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> signed = exchange("/v1/clinical/prescriptions/" + entry + "/sign", HttpMethod.POST, authed(d.token()), Map.class);
        assertThat(signed.getStatusCode()).isEqualTo(HttpStatus.OK);
        return signed.getBody();
    }

    private int usage(UUID tenantId, String drugId, boolean clinicRow, UUID doctorId) {
        return inTenantTx(tenantId, () -> jdbc.query("SELECT coalesce(sum(uses), 0) FROM drug_usage WHERE drug_id = ? AND " +
                (clinicRow ? "doctor_id IS NULL" : "doctor_id = ?"), (rs, n) -> rs.getInt(1),
                clinicRow ? new Object[]{drugId} : new Object[]{drugId, doctorId}).get(0));
    }

    // ── CLN-020 ──────────────────────────────────────────────────────────

    @Test
    void searchIsGenericFirstOnIndia_hidesRetiredAndOtherRegions_andUsageMovesTheDoctorsDrugFirst() throws Exception {
        Doc d = doctor("c20a");
        List<Map> before = search(d, "amo");
        assertThat(before).extracting(m -> m.get("drugId")).containsExactlyInAnyOrder("in-t13-amoxil", "in-t13-mox")
                .doesNotContain("in-t13-old", "sa-t13-amosa");
        assertThat(before.get(0).get("drugId")).as("no usage yet: brand prefix match first").isEqualTo("in-t13-amoxil");
        Map mox = before.stream().filter(m -> "in-t13-mox".equals(m.get("drugId"))).findFirst().orElseThrow();
        assertThat(mox.get("display")).isEqualTo("Amoxicillin 500 mg capsule (Mox)");
        assertThat(mox.get("generic")).isEqualTo("Amoxicillin");
        assertThat(mox.get("brand")).isEqualTo("Mox");
        assertThat(mox.get("source")).isEqualTo("master");

        // The doctor signs a Rx with Mox: the signed event counts it for the doctor and the clinic, and Mox moves first.
        String entry = checkIn(d, patient(d, "C20a", "+919999120001"));
        Map rx = signedRx(d, entry, Map.of("drugName", "Amoxicillin 500 mg capsule", "brandName", "Mox", "drugId", "in-t13-mox"));
        // The dispatcher is off in tests (poll interval = max); in production it polls every 2 s, so the count lands within seconds.
        for (int i = 0; i < 5 && usage(d.tenant().id(), "in-t13-mox", true, null) == 0; i++) {
            dispatcher.poll();
        }
        assertThat(usage(d.tenant().id(), "in-t13-mox", true, null)).as("counted by the outbox dispatcher after the sign").isEqualTo(1);
        assertThat(usage(d.tenant().id(), "in-t13-mox", false, d.id())).isEqualTo(1);
        assertThat(search(d, "amo").get(0).get("drugId")).isEqualTo("in-t13-mox");

        // Redelivery of the same event counts nothing twice.
        signedHandler.handle(new OutboxEvent(0, d.tenant().id(), PrescriptionService.SIGNED_EVENT,
                "{\"prescriptionId\":\"" + rx.get("id") + "\"}", 0));
        assertThat(usage(d.tenant().id(), "in-t13-mox", true, null)).isEqualTo(1);

        // Another clinic's usage does not exist for it: no boost, and RLS shows it none of ours.
        Doc other = doctor("c20b");
        assertThat(search(other, "amo").get(0).get("drugId")).isEqualTo("in-t13-amoxil");
        assertThat(usage(other.tenant().id(), "in-t13-mox", true, null)).isZero();
    }

    @Test
    void searchShowsStockDotForALinkedPharmacyItem_keepsUnlinkedItemsAndHistory_andRefusesWildcards() {
        Doc d = doctor("c20c");
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(d.token(), Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(d.token(), Map.of("name", "Mox stock item", "isRx", true, "price", 5, "stockQty", 4)), Map.class);
        exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(d.token(), Map.of("name", "Amorphous local syrup", "isRx", false, "price", 5, "stockQty", 1)), Map.class);
        su().update("UPDATE charge_catalogue SET drug_id = 'in-t13-mox' WHERE tenant_id = ? AND name = 'Mox stock item'", d.tenant().id());
        List<Map> hits = search(d, "amo");
        assertThat(hits.stream().filter(m -> "in-t13-mox".equals(m.get("drugId"))).findFirst().orElseThrow().get("inStock")).isEqualTo(true);
        assertThat(hits.stream().map(m -> (String) m.get("name")).toList()).contains("Amorphous local syrup").doesNotContain("Mox stock item");
        for (String w : new String[]{"a%25", "a_", "%25%25", "_m"}) {
            assertThat(search(d, w)).as("q=" + w).isEmpty();
        }
        assertThat(search(d, "a")).isEmpty();
    }

    @Test
    void saudiClinicSeesBrandFirstAndNeverTheIndianProducts() {
        Doc d = saDoctor("c20sa");
        List<Map> hits = search(d, "amo");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).get("display")).isEqualTo("Amosa 500 mg capsule (Amoxicillin)");
    }

    @Test
    void clientIndexIsGzipJsonWithEtag_304OnRevalidate_changesWithTheMasterVersion_andStaysSmall() throws Exception {
        Doc d = doctor("c20d");
        su().update("INSERT INTO drug_master (id, region, kb_id, brand, strength, form, status, version, search_text) " +
                "SELECT 'in-t13-bulk' || g, 'IN', 'in-t13-bulk' || g, 'T13Bulk ' || md5(g::text), '500 mg', 'tablet', 'active', 1, to_tsvector('simple', 'bulk ' || g) " +
                "FROM generate_series(1, 3200) g");
        ResponseEntity<byte[]> first = http.exchange(java.net.URI.create(url("/v1/clinical/drugs/client-index")), HttpMethod.GET, authed(d.token()), byte[].class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().length).as("compressed index < 400 KB").isLessThan(400 * 1024);
        String etag = first.getHeaders().getETag();
        assertThat(etag).isNotBlank();
        JsonNode doc = json.readTree(new GZIPInputStream(new ByteArrayInputStream(first.getBody())).readAllBytes());
        assertThat(doc.get("items").size()).isEqualTo(DrugTypeaheadService.INDEX_SIZE);
        assertThat("\"" + doc.get("rev").asString() + "\"").as("the body carries the ETag value (browsers cannot read headers here)").isEqualTo(etag);
        JsonNode row = null;
        for (JsonNode r : doc.get("items")) {
            if ("in-t13-mox".equals(r.get(0).asString())) {
                row = r;
            }
        }
        assertThat(row).as("a ranked product made the top 3000 (A-Z fill): ids start with 'in-t13-m' so Mox sorts before the bulk rows").isNotNull();
        assertThat(row.get(1).asString()).isEqualTo("Amoxicillin 500 mg capsule (Mox)");
        assertThat(first.getBody()).as("no patient or doctor identifiers in the index")
                .satisfies(b -> assertThat(new String(new GZIPInputStream(new ByteArrayInputStream(b)).readAllBytes())).doesNotContain(d.id().toString()));

        HttpHeaders h = new HttpHeaders();
        h.putAll(authed(d.token()).getHeaders());
        h.set(HttpHeaders.IF_NONE_MATCH, etag);
        ResponseEntity<byte[]> again = http.exchange(java.net.URI.create(url("/v1/clinical/drugs/client-index")), HttpMethod.GET, new HttpEntity<>(h), byte[].class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);

        su().update("UPDATE drug_master SET version = 2 WHERE id = 'in-t13-mox'");
        ResponseEntity<byte[]> changed = http.exchange(java.net.URI.create(url("/v1/clinical/drugs/client-index")), HttpMethod.GET, new HttpEntity<>(h), byte[].class);
        assertThat(changed.getStatusCode()).as("a new master version means a new index").isEqualTo(HttpStatus.OK);
        assertThat(changed.getHeaders().getETag()).isNotEqualTo(etag);
        su().update("DELETE FROM drug_master WHERE id LIKE 'in-t13-bulk%'");
    }

    @Test
    void clientIndexNeedsClinicalView() {
        SeededTenant tenant = seedTenant();
        UUID role = seedRole(tenant.id(), "NoClinical" + UUID.randomUUID().toString().substring(0, 6), false);
        String token = loginAndGetAccessToken(seedStaff(tenant, role, "c20e@t13.com", "+919800120005", false));
        assertThat(http.exchange(java.net.URI.create(url("/v1/clinical/drugs/client-index")), HttpMethod.GET, authed(token), byte[].class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── CLN-021 ──────────────────────────────────────────────────────────

    @Test
    void aPickedProductStoresDrugIdAndGenericSnapshot_anUnknownIdIs404_aFreeTextLineStaysUncoded() {
        Doc d = doctor("c21a");
        String entry = checkIn(d, patient(d, "C21a", "+919999121001"));
        ResponseEntity<Map> coded = save(d, entry, Map.of("drugName", "Amoxicillin 500 mg capsule", "brandName", "Mox", "drugId", "in-t13-mox"));
        assertThat(coded.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map line = (Map) ((List) coded.getBody().get("items")).get(0);
        assertThat(line.get("drugId")).isEqualTo("in-t13-mox");
        assertThat(line.get("coded")).isEqualTo(true);
        assertThat(line.get("genericName")).isEqualTo("Amoxicillin");
        assertThat(su().queryForObject("SELECT generic_name_snapshot FROM prescription_items WHERE drug_id = 'in-t13-mox' AND tenant_id = ?", String.class, d.tenant().id())).isEqualTo("Amoxicillin");

        ResponseEntity<Map> unknown = save(d, entry, Map.of("drugName", "Whatever", "drugId", "in-t13-nope"));
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknown.getBody().get("type").toString().toLowerCase()).contains("drug_not_found");

        ResponseEntity<Map> free = save(d, entry, Map.of("drugName", "Kadha syrup", "dosage", "5 ml"));
        assertThat(free.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map kadha = (Map) ((List) free.getBody().get("items")).get(0);
        assertThat(kadha.get("coded")).isEqualTo(false);
        assertThat(kadha.get("drugId")).isNull();
        assertThat(kadha.get("genericName")).isNull();
    }

    @Test
    void aCodedClassAllergyWarnsForAProductNoDictionaryKnows_andAnUncodedLineIsStillCheckedByText() {
        Doc d = doctor("c21b");
        String patientId = patient(d, "C21b", "+919999121002");
        ResponseEntity<Map> allergy = exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST, authedJsonBody(d.token(), Map.of(
                "substance", "Penicillin-type antibiotics", "category", "drug", "severity", "severe", "allergenClassId", "t13-penicillin")), Map.class);
        assertThat(allergy.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(allergy.getBody().get("allergenClassId")).isEqualTo("t13-penicillin");
        String entry = checkIn(d, patientId);

        // Control: the same brand typed as free text has no warning (no dictionary knows it): this is the gap coding closes.
        ResponseEntity<Map> uncoded = save(d, entry, Map.of("drugName", "Zorbexillin 250 mg tablet", "brandName", "Zorbex"));
        assertThat(warnings(uncoded, 0)).isEmpty();
        // Coded: the class id of its ingredient matches the coded allergy.
        ResponseEntity<Map> coded = save(d, entry, Map.of("drugName", "Zorbexillin 250 mg tablet", "brandName", "Zorbex", "drugId", "in-t13-zorbex"));
        assertThat(warnings(coded, 0)).hasSize(1);
        assertThat(warnings(coded, 0).get(0).get("kind")).isEqualTo("allergy");
        assertThat(warnings(coded, 0).get(0).get("requiresDecision")).isEqualTo(true);
        assertThat(coded.getBody().get("unresolvedWarnings")).isEqualTo(1);
        // A product of another class is not flagged.
        assertThat(warnings(save(d, entry, Map.of("drugName", "Paracetamol 500 mg tablet", "drugId", "in-t13-para")), 0)).isEmpty();
        // The dictionary text match keeps running for an uncoded line (existing behaviour, nothing regressed).
        exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST,
                authedJsonBody(d.token(), Map.of("substance", "Penicillin", "severity", "severe")), Map.class);
        assertThat(warnings(save(d, entry, Map.of("drugName", "Amoxicillin-Penicillin combo")), 0)).isNotEmpty();
    }

    @Test
    void codedIngredientAllergyAndTextAllergyGiveTheSameVerdictOnTheCatalogue() {
        for (String drugName : new String[]{"Amoxicillin 500 mg capsule"}) {
            Doc text = doctor("c21c1");
            String p1 = patient(text, "C21c1", "+919999121003");
            exchange("/v1/clinical/patients/" + p1 + "/allergies", HttpMethod.POST, authedJsonBody(text.token(), Map.of("substance", "Amoxicillin", "severity", "severe")), Map.class);
            Doc code = doctor("c21c2");
            String p2 = patient(code, "C21c2", "+919999121004");
            exchange("/v1/clinical/patients/" + p2 + "/allergies", HttpMethod.POST, authedJsonBody(code.token(), Map.of(
                    "substance", "Amoxicillin", "severity", "severe", "allergenIngredientId", "t13-amoxicillin")), Map.class);
            ResponseEntity<Map> byText = save(text, checkIn(text, p1), Map.of("drugName", drugName, "brandName", "Mox", "drugId", "in-t13-mox"));
            ResponseEntity<Map> byCode = save(code, checkIn(code, p2), Map.of("drugName", drugName, "brandName", "Mox", "drugId", "in-t13-mox"));
            assertThat(warnings(byText, 0)).extracting(w -> w.get("kind")).containsExactly("allergy");
            assertThat(warnings(byCode, 0)).extracting(w -> w.get("kind")).containsExactly("allergy");
        }
    }

    @Test
    void anUnknownAllergenCodeIs404() {
        Doc d = doctor("c21d");
        String patientId = patient(d, "C21d", "+919999121005");
        ResponseEntity<Map> r = exchange("/v1/clinical/patients/" + patientId + "/allergies", HttpMethod.POST, authedJsonBody(d.token(), Map.of(
                "substance", "X", "severity", "mild", "allergenClassId", "t13-no-such-class")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void printHeaderAsksForGenericFirstOnIndiaOnly() {
        Doc in = doctor("c21e");
        String entry = checkIn(in, patient(in, "C21e", "+919999121006"));
        signedRx(in, entry, Map.of("drugName", "Amoxicillin 500 mg capsule", "brandName", "Mox", "drugId", "in-t13-mox"));
        assertThat(exchange("/v1/clinical/prescriptions/" + entry + "/print-header", HttpMethod.GET, authed(in.token()), Map.class)
                .getBody().get("genericFirst")).isEqualTo(true);
        Doc sa = saDoctor("c21f");
        String e2 = checkIn(sa, patient(sa, "C21f", "+966500121007"));
        signedRx(sa, e2, Map.of("drugName", "Amoxicillin 500 mg capsule", "brandName", "Amosa", "drugId", "sa-t13-amosa"));
        assertThat(exchange("/v1/clinical/prescriptions/" + e2 + "/print-header", HttpMethod.GET, authed(sa.token()), Map.class)
                .getBody().get("genericFirst")).isEqualTo(false);
    }

    @Test
    void theCodingJobOnlyWritesSuggestionsAndNeverTouchesASignedRow() {
        Doc d = doctor("c21g");
        String entry = checkIn(d, patient(d, "C21g", "+919999121008"));
        signedRx(d, entry, Map.of("drugName", "Amoxil", "dosage", "500 mg"));
        UUID tenantId = d.tenant().id();
        String rowBefore = su().queryForObject("SELECT drug_name || '|' || coalesce(drug_id, '-') || '|' || coalesce(generic_name_snapshot, '-') || '|' || dosage " +
                "FROM prescription_items WHERE tenant_id = ?", String.class, tenantId);
        assertThat(inTenantTx(tenantId, () -> suggestions.run(100))).isEqualTo(1);
        assertThat(inTenantTx(tenantId, () -> suggestions.run(100))).as("second run: nothing new").isZero();
        assertThat(su().queryForObject("SELECT suggested_drug_id FROM rx_coding_suggestions WHERE tenant_id = ?", String.class, tenantId)).isEqualTo("in-t13-amoxil");
        assertThat(su().queryForObject("SELECT drug_name || '|' || coalesce(drug_id, '-') || '|' || coalesce(generic_name_snapshot, '-') || '|' || dosage " +
                "FROM prescription_items WHERE tenant_id = ?", String.class, tenantId)).isEqualTo(rowBefore);
        // RLS: another clinic sees none of it.
        Doc other = doctor("c21h");
        assertThat(inTenantTx(other.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM rx_coding_suggestions", Integer.class))).isZero();
    }

    @Test
    void favouriteSetItemsCarryCodesAndFreeTextItemsGetACodeThisSuggestion() {
        Doc d = doctor("c21i");
        ResponseEntity<Map> bad = exchange("/v1/clinical/rx-sets", HttpMethod.POST, authedJsonBody(d.token(), Map.of("name", "Bad", "items",
                List.of(Map.of("drugName", "X", "drugId", "in-t13-nope")))), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> created = exchange("/v1/clinical/rx-sets", HttpMethod.POST, authedJsonBody(d.token(), Map.of("name", "Fever", "items", List.of(
                Map.of("drugName", "Amoxil"), Map.of("drugName", "Kadha syrup"),
                Map.of("drugName", "Amoxicillin 500 mg capsule", "brandName", "Mox", "drugId", "in-t13-mox")))), Map.class);
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        List<Map> items = (List<Map>) created.getBody().get("items");
        assertThat(((Map) items.get(0).get("codeSuggestion")).get("drugId")).isEqualTo("in-t13-amoxil");
        assertThat(items.get(1).get("codeSuggestion")).isNull();
        assertThat(items.get(2).get("drugId")).isEqualTo("in-t13-mox");
        assertThat(items.get(2).get("genericName")).isEqualTo("Amoxicillin");
        assertThat(items.get(2).get("codeSuggestion")).isNull();
    }
}

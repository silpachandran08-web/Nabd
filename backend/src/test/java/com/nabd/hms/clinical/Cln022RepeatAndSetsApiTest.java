package com.nabd.hms.clinical;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLN-022: repeat last Rx and favourite sets from the Rx pad. Every drug master product here is SYNTHETIC test data written
 * by the test (ids start with t22-); none of it is clinical content.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Cln022RepeatAndSetsApiTest extends ApiTestBase {

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
        su().update("INSERT INTO ingredients (id, name) VALUES ('t22-alpha', 'Alphazine') ON CONFLICT DO NOTHING");
        product("in-t22-old", "Alphold", "10 mg", "tablet", "retired");
        product("in-t22-new", "Alphanew", "10 mg", "tablet", "active");
        product("in-t22-big", "Alphabig", "20 mg", "tablet", "active");
    }

    @AfterEach
    void clean() {
        JdbcTemplate su = su();
        su.update("UPDATE charge_catalogue SET drug_id = NULL WHERE drug_id LIKE '%t22-%'");
        su.update("DELETE FROM drug_ingredients WHERE drug_id LIKE '%t22-%'");
        su.update("DELETE FROM drug_master WHERE id LIKE '%t22-%'");
        su.update("DELETE FROM ingredients WHERE id LIKE 't22-%'");
    }

    private void product(String id, String brand, String strength, String form, String status) {
        JdbcTemplate su = su();
        su.update("INSERT INTO drug_master (id, region, kb_id, brand, strength, form, status, version) VALUES (?,'IN',?,?,?,?,?,1)",
                id, id, brand, strength, form, status);
        su.update("INSERT INTO drug_ingredients (drug_id, ingredient_id) VALUES (?, 't22-alpha')", id);
    }

    private String patient(String token, String name, String phone, String gender) {
        return (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "1995-01-01", "gender", gender)), Map.class).getBody().get("id");
    }

    private String checkIn(String token, String patientId, UUID doctorId) {
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class).getBody().get("id");
    }

    private ResponseEntity<Map> save(String token, String entry, List<Map<String, Object>> items) {
        return exchange("/v1/clinical/prescriptions/" + entry, HttpMethod.PATCH, authedJsonBody(token, Map.of("items", items)), Map.class);
    }

    private ResponseEntity<Map> sign(String token, String entry) {
        return exchange("/v1/clinical/prescriptions/" + entry + "/sign", HttpMethod.POST, authed(token), Map.class);
    }

    private ResponseEntity<Map> repeat(String token, String entry, Object body) {
        return exchange("/v1/clinical/prescriptions/" + entry + "/repeat-last", HttpMethod.POST,
                body == null ? authed(token) : authedJsonBody(token, body), Map.class);
    }

    private static List<Map> items(ResponseEntity<Map> rx) {
        return (List<Map>) rx.getBody().get("items");
    }

    // J7 variant: an overridden pregnancy warning on the repeated Rx is NOT carried over; it needs a fresh decision.
    @Test
    void aRepeatedLineWithAnEarlierPregnancyOverrideComesBackUnresolvedAndBlocksSigning() {
        SeededTenant tenant = seedTenant();
        SeededStaff doc = seedStaff(tenant, seedFullAccessRole(tenant.id()), "c22a@a.com", "+919800022001", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = patient(token, "J7", "+919999922001", "female");

        String first = checkIn(token, patientId, doc.id());
        save(token, first, List.of(Map.of("drugName", "Doxycycline", "dosage", "100mg",
                "overrides", List.of(Map.of("kind", "pregnancy", "reasonCode", "obstetric_consultation")))));
        assertThat(sign(token, first).getStatusCode()).isEqualTo(HttpStatus.OK);

        String second = checkIn(token, patientId, doc.id());
        ResponseEntity<Map> repeated = repeat(token, second, null);
        assertThat(repeated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeated.getBody().get("unresolvedWarnings")).isEqualTo(1);
        Map preg = ((List<Map>) items(repeated).get(0).get("warnings")).stream()
                .filter(w -> "pregnancy".equals(w.get("kind"))).findFirst().orElseThrow();
        assertThat(preg.get("resolved")).isEqualTo(false);
        assertThat(preg.get("decision")).isNull();

        ResponseEntity<Map> refused = sign(token, second);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("type")).asString().contains("warning-unresolved");
    }

    // Timed task (< 30 s) proxy: a repeat is two server calls (repeat-last, sign) and the preview needs only the history the
    // consult screen already loads, which now names the signer ("Repeat last (date, doctor)").
    @Test
    void aRepeatIsTwoCallsAndTheHistoryCarriesTheSignersNameForThePreview() {
        SeededTenant tenant = seedTenant();
        SeededStaff doc = seedStaff(tenant, seedFullAccessRole(tenant.id()), "c22b@a.com", "+919800022002", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = patient(token, "Chronic", "+919999922002", "male");
        String first = checkIn(token, patientId, doc.id());
        save(token, first, List.of(Map.of("drugName", "Cetirizine", "dosage", "10mg", "frequency", "OD", "duration", "30 days")));
        sign(token, first);

        List<Map> history = exchange("/v1/clinical/patients/" + patientId + "/prescriptions", HttpMethod.GET, authed(token), List.class).getBody();
        assertThat(history.get(0).get("signedByName")).isNotNull();
        assertThat(history.get(0).get("signedAt")).isNotNull();

        String second = checkIn(token, patientId, doc.id());
        assertThat(repeat(token, second, Map.of("sourcePrescriptionId", history.get(0).get("id"))).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> signed = sign(token, second);
        assertThat(signed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signed.getBody().get("status")).isEqualTo("signed");
        assertThat(items(signed).get(0).get("duration")).isEqualTo("30 days");
    }

    @Test
    void theSourcePinsThePreviewedRxAndAnotherPatientsRxIs404() {
        SeededTenant tenant = seedTenant();
        SeededStaff doc = seedStaff(tenant, seedFullAccessRole(tenant.id()), "c22c@a.com", "+919800022003", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = patient(token, "Src", "+919999922003", "male");
        String older = checkIn(token, patientId, doc.id());
        String olderRx = (String) save(token, older, List.of(Map.of("drugName", "Cetirizine", "dosage", "10mg"))).getBody().get("id");
        sign(token, older);
        String newer = checkIn(token, patientId, doc.id());
        save(token, newer, List.of(Map.of("drugName", "Paracetamol", "dosage", "500mg")));
        sign(token, newer);
        String other = patient(token, "Other", "+919999922004", "male");
        String otherEntry = checkIn(token, other, doc.id());
        String otherRx = (String) save(token, otherEntry, List.of(Map.of("drugName", "Loratadine", "dosage", "10mg"))).getBody().get("id");
        sign(token, otherEntry);

        String today = checkIn(token, patientId, doc.id());
        assertThat(items(repeat(token, today, null)).get(0).get("drugName")).isEqualTo("Paracetamol"); // default: most recent
        assertThat(items(repeat(token, today, Map.of("sourcePrescriptionId", olderRx))).get(0).get("drugName")).isEqualTo("Cetirizine");
        ResponseEntity<Map> foreign = repeat(token, today, Map.of("sourcePrescriptionId", otherRx));
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreign.getBody().get("type")).asString().contains("no-previous-prescription");
        assertThat(repeat(token, today, Map.of("sourcePrescriptionId", UUID.randomUUID().toString())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        sign(token, today);
        ResponseEntity<Map> afterSign = repeat(token, today, null);
        assertThat(afterSign.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(afterSign.getBody().get("type")).asString().contains("prescription-signed");
    }

    @Test
    void repeatLastNeedsAnIdempotencyKeyAndARetriedTapRunsOnce() {
        SeededTenant tenant = seedTenant();
        SeededStaff doc = seedStaff(tenant, seedFullAccessRole(tenant.id()), "c22d@a.com", "+919800022005", false);
        String token = loginAndGetAccessToken(doc);
        String patientId = patient(token, "Idem", "+919999922005", "male");
        String first = checkIn(token, patientId, doc.id());
        save(token, first, List.of(Map.of("drugName", "Cetirizine", "dosage", "10mg")));
        sign(token, first);
        String second = checkIn(token, patientId, doc.id());

        HttpHeaders noKey = new HttpHeaders();
        noKey.setBearerAuth(token);
        assertThat(exchange("/v1/clinical/prescriptions/" + second + "/repeat-last", HttpMethod.POST, new HttpEntity<>(noKey), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of();
        ResponseEntity<Map> a = exchange("/v1/clinical/prescriptions/" + second + "/repeat-last", HttpMethod.POST,
                authedJsonBodyWithHeader(token, "Idempotency-Key", key, body), Map.class);
        ResponseEntity<Map> b = exchange("/v1/clinical/prescriptions/" + second + "/repeat-last", HttpMethod.POST,
                authedJsonBodyWithHeader(token, "Idempotency-Key", key, body), Map.class);
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.getHeaders().getFirst("Idempotent-Replayed")).isNotNull();
        Integer audited = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'prescription.repeated_last'", Integer.class, tenant.id()));
        assertThat(audited).isEqualTo(1);
    }

    // Timed task (< 90 s) proxy: a new patient + favourite set + one dose edit + sign is three server calls.
    @Test
    void applyingASetEditingOneDoseAndSigningIsThreeCallsAndMarksTheSetUsed() {
        SeededTenant tenant = seedTenant();
        SeededStaff doc = seedStaff(tenant, seedFullAccessRole(tenant.id()), "c22e@a.com", "+919800022006", false);
        String token = loginAndGetAccessToken(doc);
        String setId = (String) exchange("/v1/clinical/rx-sets", HttpMethod.POST, authedJsonBody(token, Map.of("name", "Cold",
                "items", List.of(Map.of("drugName", "Cetirizine", "dosage", "10mg", "frequency", "OD"),
                        Map.of("drugName", "Paracetamol", "dosage", "500mg", "frequency", "TDS")))), Map.class).getBody().get("id");
        String entry = checkIn(token, patient(token, "New", "+919999922006", "male"), doc.id());

        ResponseEntity<Map> applied = exchange("/v1/clinical/prescriptions/" + entry + "/apply-set/" + setId, HttpMethod.POST, authed(token), Map.class);
        assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> edited = items(applied).stream().map(i -> Map.<String, Object>of(
                "drugName", i.get("drugName"), "dosage", "Paracetamol".equals(i.get("drugName")) ? "650mg" : i.get("dosage"),
                "frequency", i.get("frequency"))).toList();
        assertThat(save(token, entry, edited).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> signed = sign(token, entry);
        assertThat(signed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(items(signed).stream().map(i -> i.get("dosage")).toList()).containsExactly("10mg", "650mg");

        Map set = (Map) exchange("/v1/clinical/rx-sets", HttpMethod.GET, authed(token), List.class).getBody().get(0);
        assertThat(set.get("lastUsedAt")).isNotNull();
    }

    @Test
    void aRetiredOrOutOfStockCodedItemIsStaleWithASameIngredientReplacement() {
        SeededTenant tenant = seedTenant();
        SeededStaff doc = seedStaff(tenant, seedFullAccessRole(tenant.id()), "c22f@a.com", "+919800022007", false);
        String token = loginAndGetAccessToken(doc);
        exchange("/v1/clinical/rx-sets", HttpMethod.POST, authedJsonBody(token, Map.of("name", "Coded", "items", List.of(
                Map.of("drugName", "Alphazine 10 mg tablet", "drugId", "in-t22-old"),
                Map.of("drugName", "Alphazine 20 mg tablet", "drugId", "in-t22-big")))), Map.class);

        // no pharmacy master at all: coded items are still judged against the drug master
        Map set = (Map) exchange("/v1/clinical/rx-sets", HttpMethod.GET, authed(token), List.class).getBody().get(0);
        assertThat(set.get("staleCheckAvailable")).isEqualTo(true);
        assertThat(set.get("staleItemCount")).isEqualTo(1);
        List<Map> it = (List<Map>) set.get("items");
        assertThat(it.get(0).get("stale")).isEqualTo(true);
        assertThat(it.get(0).get("staleReason")).isEqualTo("retired");
        assertThat(((Map) it.get(0).get("replacement")).get("drugId")).isEqualTo("in-t22-new"); // same ingredient, same strength
        assertThat(it.get(1).get("stale")).isEqualTo(false); // not dispensed here: stock says nothing
        assertThat(it.get(1).get("replacement")).isNull();

        // dispensed in-house and none left: stale, and the in-stock same-ingredient product is offered first
        inTenantTx(tenant.id(), () -> {
            jdbc.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, tax_rate_percent, stock_qty, drug_id) VALUES (?,?,?,?,?,?,?,?)",
                    tenant.id(), "PH-T22B", "Alphabig 20", "Pharmacy", 1, 0, 0, "in-t22-big");
            jdbc.update("INSERT INTO charge_catalogue (tenant_id, code, name, category, base_amount, tax_rate_percent, stock_qty, drug_id) VALUES (?,?,?,?,?,?,?,?)",
                    tenant.id(), "PH-T22N", "Alphanew 10", "Pharmacy", 1, 0, 12, "in-t22-new");
        });
        it = (List<Map>) ((Map) exchange("/v1/clinical/rx-sets", HttpMethod.GET, authed(token), List.class).getBody().get(0)).get("items");
        assertThat(it.get(1).get("staleReason")).isEqualTo("out_of_stock");
        assertThat(((Map) it.get(1).get("replacement")).get("drugId")).isEqualTo("in-t22-new");
    }

    @Test
    void setsAreListedMineThenSharedThenClinicStandardsEachByLastUse() {
        SeededTenant tenant = seedTenant();
        UUID role = seedFullAccessRole(tenant.id());
        SeededStaff me = seedStaff(tenant, role, "c22g@a.com", "+919800022008", false);
        SeededStaff colleague = seedStaff(tenant, role, "c22h@a.com", "+919800022009", false);
        String myToken = loginAndGetAccessToken(me);
        String theirToken = loginAndGetAccessToken(colleague);
        String std1 = create(myToken, "Std old", "standard");
        String std2 = create(myToken, "Std recent", "standard");
        String shared1 = create(theirToken, "Shared never", "shared");
        String shared2 = create(theirToken, "Shared recent", "shared");
        String mine1 = create(myToken, "Mine never", "mine");
        String mine2 = create(myToken, "Mine old", "shared"); // my own set shared with the clinic is still "mine"
        String mine3 = create(myToken, "Mine recent", "mine");
        create(theirToken, "Their private", "mine");
        inTenantTx(tenant.id(), () -> {
            jdbc.update("UPDATE favourite_rx_sets SET last_used_at = now() - interval '2 days' WHERE id IN (?::uuid, ?::uuid)", std1, mine2);
            jdbc.update("UPDATE favourite_rx_sets SET last_used_at = now() - interval '1 hour' WHERE id IN (?::uuid, ?::uuid, ?::uuid)", std2, shared2, mine3);
        });
        List<Map> listed = exchange("/v1/clinical/rx-sets", HttpMethod.GET, authed(myToken), List.class).getBody();
        assertThat(listed.stream().map(s -> s.get("id")).toList()).containsExactly(mine3, mine2, mine1, shared2, shared1, std2, std1);
        assertThat(listed.stream().map(s -> s.get("scope")).toList())
                .containsExactly("mine", "mine", "mine", "shared", "shared", "standard", "standard");
    }

    private String create(String token, String name, String scope) {
        return (String) exchange("/v1/clinical/rx-sets", HttpMethod.POST, authedJsonBody(token, Map.of("name", name, "scope", scope,
                "items", List.of(Map.of("drugName", "Cetirizine")))), Map.class).getBody().get("id");
    }
}

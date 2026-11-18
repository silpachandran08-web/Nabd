package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.SafetyDictionaryBundle;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLN-024: per-line Rx validity and refills by drug class. The drug master products, the "t24-biguanide" class and its rule are
 * SYNTHETIC test data written by the test; the seeded IN rules (V3320) are placeholders awaiting clinical review. Drug words are the
 * story's own acceptance examples, used as test input only.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Cln024RxValidityApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 400_000);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    @Autowired SafetyDictionaryService dictionaries;

    private Runnable restoreDictionaries;

    private record Clinic(SeededTenant tenant, SeededStaff staff, String token) {
    }

    private static JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static String phone() {
        return "+9196" + String.format("%08d", SEQ.incrementAndGet());
    }

    @BeforeEach
    void seed() {
        restoreDictionaries = SafetyDictionaryFixtures.snapshotState(superuserDataSource(), dictionaries);
        clean();
        JdbcTemplate su = su();
        su.update("INSERT INTO drug_classes (id, name) VALUES ('t24-biguanide', 'T24 test class') ON CONFLICT DO NOTHING");
        su.update("INSERT INTO ingredients (id, name, class_ids) VALUES ('t24-ing-met', 'T24 metformin ingredient', '{t24-biguanide}') ON CONFLICT DO NOTHING");
        su.update("INSERT INTO ingredients (id, name) VALUES ('t24-ing-h1', 'T24 scheduled ingredient') ON CONFLICT DO NOTHING");
        su.update("INSERT INTO drug_master (id, region, kb_id, brand, strength, form, schedule, status, version) VALUES " +
                "('in-t24-met', 'IN', 'in-t24-met', 'T24 Metformin', '500 mg', 'tablet', '{}', 'active', 1), " +
                "('in-t24-h1', 'IN', 'in-t24-h1', 'T24 Painrelief', '50 mg', 'tablet', '{H1}', 'active', 1)");
        su.update("INSERT INTO drug_ingredients (drug_id, ingredient_id) VALUES ('in-t24-met', 't24-ing-met'), ('in-t24-h1', 't24-ing-h1')");
        // a chronic-class rule for the synthetic class only (version 0 = the seeded IN dictionary)
        su.update("INSERT INTO rx_validity_rules (region, version, class_id, default_days, max_days, max_refills) VALUES ('IN', 0, 't24-biguanide', 30, 30, 2)");
    }

    @AfterEach
    void clean() {
        if (restoreDictionaries != null) {
            restoreDictionaries.run();
        }
        JdbcTemplate su = su();
        su.update("DELETE FROM rx_validity_rules WHERE class_id LIKE 't24-%'");
        su.update("UPDATE charge_catalogue SET drug_id = NULL WHERE drug_id LIKE '%t24-%'");
        su.update("DELETE FROM drug_ingredients WHERE drug_id LIKE '%t24-%'");
        su.update("DELETE FROM drug_master WHERE id LIKE '%t24-%'");
        su.update("DELETE FROM ingredients WHERE id LIKE 't24-%'");
        su.update("DELETE FROM drug_classes WHERE id LIKE 't24-%'");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private Clinic clinic() {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = ClinicianFixture.licensed(seedStaff(tenant, seedFullAccessRole(tenant.id()), "c24" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        String token = loginAndGetAccessToken(staff);
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "CLN-024 test")),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Clinic(tenant, staff, token);
    }

    /** seedTenant() makes an IN tenant; the region is immutable after provisioning, so flip it as superuser (as SafetyDictionaryApiTest). */
    private Clinic ksaClinic() {
        SeededTenant t = seedTenant();
        try (java.sql.Connection c = superuserConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("UPDATE tenants SET region_code = 'SA' WHERE id = '" + t.id() + "'");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        SeededStaff staff = ClinicianFixture.licensed(seedStaff(t, seedFullAccessRole(t.id()), "k24" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        return new Clinic(t, staff, loginAndGetAccessToken(staff));
    }

    private String visit(Clinic c) {
        String patient = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", "Validity Patient " + SEQ.incrementAndGet(),
                "phone", phone(), "dob", java.time.LocalDate.of(1960, 1, 1).plusDays(SEQ.incrementAndGet() % 9000).toString(), "gender", "male")),
                Map.class).getBody().get("id");
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patient, "doctorId", c.staff().id().toString())), Map.class).getBody().get("id");
    }

    private static Map<String, Object> line(String drugName, Object... extra) {
        Map<String, Object> m = new HashMap<>(Map.of("drugName", drugName, "quantity", 10, "quantityUnit", "tablets"));
        for (int i = 0; i < extra.length; i += 2) m.put((String) extra[i], extra[i + 1]);
        return m;
    }

    private ResponseEntity<Map> save(Clinic c, String qe, Map<String, Object>... items) {
        return exchange("/v1/clinical/prescriptions/" + qe, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("items", List.of(items))), Map.class);
    }

    private ResponseEntity<Map> sign(Clinic c, String qe) {
        return exchange("/v1/clinical/prescriptions/" + qe + "/sign", HttpMethod.POST, authed(c.token()), Map.class);
    }

    private Map<String, Object> saveAndSign(Clinic c, String qe, Map<String, Object>... items) {
        ResponseEntity<Map> saved = save(c, qe, items);
        assertThat(saved.getStatusCode()).as("save %s", saved.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> signed = sign(c, qe);
        assertThat(signed.getStatusCode()).as("sign %s", signed.getBody()).isEqualTo(HttpStatus.OK);
        return signed.getBody();
    }

    private static Map<String, Object> item(Map<String, Object> rx, int i) {
        return (Map<String, Object>) ((List<?>) rx.get("items")).get(i);
    }

    private static Map<String, Object> validity(Map<String, Object> rx, int i) {
        return (Map<String, Object>) item(rx, i).get("validity");
    }

    private static String slug(ResponseEntity<Map> r) {
        String type = String.valueOf(r.getBody().get("type"));
        return type.substring(type.lastIndexOf('/') + 1);
    }

    private String stockItem(Clinic c, String name) {
        ResponseEntity<Map> r = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", name, "isRx", true, "hsnCode", "3004", "price", 10.00, "taxRatePercent", 0, "stockQty", 200)), Map.class);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("item %s", r.getBody()).isTrue();
        return (String) r.getBody().get("code");
    }

    private ResponseEntity<Map> dispense(Clinic c, String code, int qty, Object prescriptionItemId) {
        return exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", List.of(
                Map.of("chargeCode", code, "quantity", qty, "prescriptionItemId", prescriptionItemId)))), Map.class);
    }

    private String operator() {
        return platformLoginAndGetAccessToken(seedOperator("super_admin-cln024-" + UUID.randomUUID() + "@nabd.internal", "super_admin", false));
    }

    // ── AC1 + the rules endpoint: amoxicillin signed today is valid 5 days, on the line and on the PDF ──

    @Test
    void anAmoxicillinLineIsValidFiveDaysFromSigningAndThePdfSaysValidUntil() {
        Clinic c = clinic();
        ResponseEntity<Map> rules = exchange("/v1/clinical/rx-validity-rules", HttpMethod.GET, authed(c.token()), Map.class);
        assertThat(rules.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rules.getBody()).containsEntry("region", "IN").containsEntry("version", 0);
        assertThat((List<Map>) rules.getBody().get("rules")).anySatisfy(r -> assertThat(r).containsEntry("classId", "penicillin")
                .containsEntry("defaultDays", 5).containsEntry("maxDays", 5).containsEntry("maxRefills", 0).containsEntry("fixed", false));

        String qe = visit(c);
        ResponseEntity<Map> draft = save(c, qe, line("Amoxicillin 500 mg"));
        assertThat(validity(draft.getBody(), 0)).containsEntry("days", 5).containsEntry("maxDays", 5).containsEntry("maxRefills", 0)
                .containsEntry("classId", "penicillin").containsEntry("validUntil", null);
        Map<String, Object> rx = saveAndSign(c, qe, line("Amoxicillin 500 mg"));
        Instant signedAt = Instant.parse((String) rx.get("signedAt"));
        Instant validUntil = Instant.parse((String) validity(rx, 0).get("validUntil"));
        assertThat(Duration.between(signedAt, validUntil)).isEqualTo(Duration.ofDays(5));
        assertThat(validity(rx, 0)).containsEntry("days", 5).containsEntry("refills", 0);
        assertThat(su().queryForObject("SELECT validity_days FROM prescription_items WHERE id = ?::uuid", Integer.class, item(rx, 0).get("id"))).isEqualTo(5);

        ZoneId zone = ZoneId.of(su().queryForObject("SELECT COALESCE(t.timezone, r.default_timezone) FROM tenants t LEFT JOIN regions r ON r.code = t.region_code " +
                "WHERE t.id = ?", String.class, c.tenant().id()));
        ResponseEntity<byte[]> pdf = exchange("/v1/clinical/prescriptions/" + qe + "/pdf", HttpMethod.GET, authed(c.token()), byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(pdf.getBody(), StandardCharsets.ISO_8859_1)).contains("Valid until " + DAY.format(signedAt.plus(Duration.ofDays(5)).atZone(zone)));
    }

    // ── AC3: an antibiotic above its class maximum is refused; shorter is kept ──

    @Test
    void anAntibioticValidityAboveTheClassMaximumIs422AndAShorterOneIsSigned() {
        Clinic c = clinic();
        String qe = visit(c);
        ResponseEntity<Map> tooLong = save(c, qe, line("Amoxicillin 500 mg", "validityDays", 14));
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(slug(tooLong)).isEqualTo("VALIDITY_ABOVE_CLASS_MAX");
        assertThat(tooLong.getBody()).containsEntry("maxDays", 5);
        assertThat(exchange("/v1/clinical/prescriptions/" + qe, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("items", List.of(
                line("Amoxicillin 500 mg", "validityDays", 0)))), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<String, Object> rx = saveAndSign(c, qe, line("Amoxicillin 500 mg", "validityDays", 3));
        assertThat(item(rx, 0)).containsEntry("validityDays", 3);
        assertThat(validity(rx, 0)).containsEntry("days", 3);
    }

    // ── AC4: an H1 / controlled line is 7 days and 0 refills whatever the input ──

    @Test
    void aControlledOrScheduleH1LineIsSevenDaysAndNoRefillsRegardlessOfInput() {
        Clinic c = clinic();
        String qe = visit(c);
        Map<String, Object> rx = saveAndSign(c, qe,
                line("Tramadol 50 mg", "validityDays", 30, "refills", 3,
                        "overrides", List.of(Map.of("kind", "controlled", "reasonCode", "documentation_confirmed"))),
                line("T24 Painrelief 50 mg tablet", "drugId", "in-t24-h1", "validityDays", 20, "refills", 2));
        for (int i = 0; i < 2; i++) {
            assertThat(validity(rx, i)).as("line %s", i).containsEntry("days", 7).containsEntry("refills", 0);
            assertThat(su().queryForMap("SELECT validity_days, refills_allowed FROM prescription_items WHERE id = ?::uuid", item(rx, i).get("id")))
                    .containsEntry("validity_days", 7).containsEntry("refills_allowed", 0);
        }
    }

    // ── AC2 + REFILLS_NOT_ALLOWED + the verify page: a chronic line with 2 refills; one dispense more is REFILLS_EXHAUSTED ──

    @Test
    void aChronicLineWithTwoRefillsDispensesThreeTimesThenIsRefusedWithRefillsExhausted() {
        Clinic c = clinic();
        String qe = visit(c);
        ResponseEntity<Map> tooMany = save(c, qe, line("Metformin 500 mg", "drugId", "in-t24-met", "refills", 3));
        assertThat(tooMany.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(slug(tooMany)).isEqualTo("REFILLS_NOT_ALLOWED");
        ResponseEntity<Map> noRefillClass = save(c, qe, line("Amoxicillin 500 mg", "refills", 1));
        assertThat(slug(noRefillClass)).isEqualTo("REFILLS_NOT_ALLOWED");

        Map<String, Object> rx = saveAndSign(c, qe, line("Metformin 500 mg", "drugId", "in-t24-met", "refills", 2));
        assertThat(validity(rx, 0)).containsEntry("days", 30).containsEntry("refills", 2);
        Object lineId = item(rx, 0).get("id");
        String code = stockItem(c, "T24 Metformin stock");

        assertThat(dispense(c, code, 10, lineId).getStatusCode()).as("first fill").isEqualTo(HttpStatus.OK);
        assertThat(dispense(c, code, 10, lineId).getStatusCode()).as("refill 1").isEqualTo(HttpStatus.OK);

        String pharmacist = loginAndGetAccessToken(seedStaff(c.tenant(), seedRole(c.tenant().id(), "Pharmacist " + SEQ.incrementAndGet(), false,
                new ModuleGrant("pharmacy", true, true, true, false, false, false, false)), "ph24" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        String qr = (String) exchange("/v1/clinical/prescriptions/" + qe + "/print-header", HttpMethod.GET, authed(c.token()), Map.class).getBody().get("qrPayload");
        ResponseEntity<Map> verified = exchange("/v1/pharmacy/rx-verifications", HttpMethod.POST, authedJsonBody(pharmacist, Map.of("qrPayload", qr)), Map.class);
        assertThat(verified.getStatusCode()).as("%s", verified.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody()).containsEntry("status", "valid");
        Map<String, Object> left = (Map<String, Object>) ((List<?>) verified.getBody().get("remaining")).get(0);
        assertThat(left).containsEntry("refillsAllowed", 2).containsEntry("refillsUsed", 1);
        assertThat(((Number) left.get("remaining")).intValue()).isEqualTo(10);
        assertThat(left.get("validUntil")).isNotNull();

        assertThat(dispense(c, code, 10, lineId).getStatusCode()).as("refill 2").isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> refused = dispense(c, code, 10, lineId);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(slug(refused)).isEqualTo("REFILLS_EXHAUSTED");
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'pharmacy.rx_gate_blocked' " +
                "AND after->>'reason' = 'REFILLS_EXHAUSTED'", Integer.class, c.tenant().id())).isEqualTo(1);
    }

    // ── per-line expiry: a line past its own validity is refused at the counter ──

    @Test
    void aLinePastItsOwnValidityIsRefusedAtDispense() {
        Clinic c = clinic();
        String qe = visit(c);
        Map<String, Object> rx = saveAndSign(c, qe, line("Amoxicillin 500 mg"));
        String code = stockItem(c, "T24 Amox stock");
        try (java.sql.Connection conn = superuserConnection(); java.sql.Statement st = conn.createStatement()) {
            st.execute("SET session_replication_role = replica"); // test-only: a signed row is final (V51); age it by 6 days
            st.execute("UPDATE prescriptions SET signed_at = signed_at - interval '6 days' WHERE id = '" + rx.get("id") + "'");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        ResponseEntity<Map> refused = dispense(c, code, 5, item(rx, 0).get("id"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(slug(refused)).isEqualTo("rx-expired");
    }

    // ── AC5: the CSO activates a new rule version; only prescriptions signed afterwards use it ──

    @Test
    void aNewRuleVersionAppliesOnlyToPrescriptionsSignedAfterTheCsoActivatesIt() {
        Clinic c = clinic();
        String before = visit(c);
        Map<String, Object> old = saveAndSign(c, before, line("Paracetamol 500 mg"));
        assertThat(validity(old, 0)).containsEntry("days", 30);

        String op = operator();
        SafetyDictionaryBundle in = SafetyDictionaryFixtures.bundle("IN");
        SafetyDictionaryBundle v1 = new SafetyDictionaryBundle(in.classes(), in.terms(), in.stems(), in.exclusions(), in.pregnancyRisk(), in.controlled(),
                in.labels(), List.of(new SafetyDictionaryBundle.ValidityRule("*", 10, 10, 0, false),
                new SafetyDictionaryBundle.ValidityRule("controlled", 7, 7, 0, true)));
        ResponseEntity<Map> up = exchange("/internal/v1/safety-dictionaries/IN/versions", HttpMethod.POST, authedJsonBody(op, v1), Map.class);
        assertThat(up.getStatusCode()).as("%s", up.getBody()).isEqualTo(HttpStatus.CREATED);
        Object id = up.getBody().get("versionId");
        int version = (Integer) up.getBody().get("version");
        assertThat(exchange("/v1/clinical/rx-validity-rules", HttpMethod.GET, authed(c.token()), Map.class).getBody())
                .as("an uploaded draft changes nothing").containsEntry("version", 0);
        assertThat(exchange("/internal/v1/safety-dictionaries/IN/versions/" + id + "/approve", HttpMethod.POST, authedJsonBody(op,
                Map.of("csoApprovalRef", "CSO-T24-1", "approvedBy", "Test CSO")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/internal/v1/safety-dictionaries/IN/versions/" + id + "/activate", HttpMethod.POST, authedJsonBody(op,
                Map.of("csoApprovalRef", "CSO-T24-1", "reason", "CLN-024 test")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> rules = exchange("/v1/clinical/rx-validity-rules", HttpMethod.GET, authed(c.token()), Map.class);
        assertThat(rules.getBody()).containsEntry("version", version);
        assertThat((List<Map>) rules.getBody().get("rules")).extracting(r -> r.get("classId")).containsExactlyInAnyOrder("*", "controlled");

        String after = visit(c);
        Map<String, Object> fresh = saveAndSign(c, after, line("Paracetamol 500 mg"), line("Amoxicillin 500 mg"));
        assertThat(validity(fresh, 0)).containsEntry("days", 10);
        assertThat(validity(fresh, 1)).as("the new set has no antibiotic rule").containsEntry("days", 10);
        Map<String, Object> reread = exchange("/v1/clinical/prescriptions/" + before, HttpMethod.GET, authed(c.token()), Map.class).getBody();
        assertThat(validity(reread, 0)).as("signed before the activation: unchanged").containsEntry("days", 30);
    }

    // ── per market: a region without rules (KSA until MOH rules are supplied) has no line validity and no refills ──

    @Test
    void aRegionWithoutRulesGivesNoLineValidityAndRefusesRefills() {
        Clinic c = ksaClinic();
        ResponseEntity<Map> rules = exchange("/v1/clinical/rx-validity-rules", HttpMethod.GET, authed(c.token()), Map.class);
        assertThat(rules.getBody()).containsEntry("region", "SA").containsEntry("version", null);
        assertThat((List<?>) rules.getBody().get("rules")).isEmpty();
        String qe = visit(c);
        ResponseEntity<Map> refill = save(c, qe, line("Paracetamol 500 mg", "refills", 1));
        assertThat(slug(refill)).isEqualTo("REFILLS_NOT_ALLOWED");
        Map<String, Object> rx = saveAndSign(c, qe, line("Paracetamol 500 mg"));
        assertThat(validity(rx, 0)).containsEntry("days", null).containsEntry("validUntil", null).containsEntry("refills", 0);
    }
}

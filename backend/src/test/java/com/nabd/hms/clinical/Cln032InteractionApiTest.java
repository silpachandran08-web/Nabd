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
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CLN-032: drug-drug interaction and duplicate-therapy warnings on the Rx pad, the active-medicines resolver and the
 * knowledge-base loader.
 *
 * TEST DATA ONLY. Every product, ingredient, class, interaction pair, severity, mechanism and reference in this file is
 * SYNTHETIC, written by the test into ids that start with t32- / in-t32- and into a knowledge base named
 * SYNTHETIC-TEST-KB. The real-looking names (warfarin, ibuprofen, diclofenac, naproxen, paracetamol, Crocin, Dolo) are the
 * story's own acceptance examples; their severities and texts here are placeholders, not clinical content, and no
 * licensed knowledge base is used or implied. Everything is deleted again after each test.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Cln032InteractionApiTest extends ApiTestBase {

    private static final String LOAD = "/internal/v1/drug-master/interaction-imports";
    private static final String KB_LABEL = "SYNTHETIC-TEST-KB 2026-10";
    private static final String HEADER = "{\"kb\":\"SYNTHETIC-TEST-KB\",\"month\":\"2026-10\",\"region\":\"IN\",\"kind\":\"interactions\","
            + "\"duplicateIngredientSeverity\":\"moderate\"}";
    private static final String WARFARIN_IBUPROFEN = pair("t32-warfarin", "t32-ibuprofen", "major");
    private static final List<String> STANDARD = List.of(WARFARIN_IBUPROFEN, pair("t32-minor-a", "t32-minor-b", "minor"),
            "{\"type\":\"duplicate-class\",\"classId\":\"t32-nsaid\",\"severity\":\"major\",\"sourceRef\":\"TEST-REF-CLASS\"}");
    private static final AtomicInteger SEQ = new AtomicInteger(3200);

    @Autowired DrugMasterImportService importService;
    @Autowired JdbcTemplate appJdbc;

    private Path dir;
    private String operator;

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
    void setUp() throws Exception {
        dir = Files.createTempDirectory("interaction-feeds");
        Object target = AopProxyUtils.getSingletonTarget(importService) != null ? AopProxyUtils.getSingletonTarget(importService) : importService;
        ReflectionTestUtils.setField(target, "feedDir", dir.toRealPath());
        clean();
        JdbcTemplate su = su();
        su.update("INSERT INTO drug_classes (id, name) VALUES ('t32-nsaid', 'NSAID'), ('t32-plain', 'T32 unmarked class')");
        ingredient("t32-warfarin", "warfarin", "t32-plain");
        ingredient("t32-ibuprofen", "ibuprofen", "t32-nsaid");
        ingredient("t32-diclofenac", "diclofenac", "t32-nsaid");
        ingredient("t32-naproxen", "naproxen", "t32-nsaid");
        ingredient("t32-paracetamol", "paracetamol", "t32-plain");
        ingredient("t32-minor-a", "T32 minor A", null);
        ingredient("t32-minor-b", "T32 minor B", null);
        product("in-t32-warf", "Warf-T32", "t32-warfarin", "5");
        product("in-t32-ibu", "Ibu-T32", "t32-ibuprofen", "400");
        product("in-t32-diclo", "Diclo-T32", "t32-diclofenac", "50");
        product("in-t32-napro", "Napro-T32", "t32-naproxen", "250");
        product("in-t32-crocin", "Crocin", "t32-paracetamol", "500");
        product("in-t32-dolo", "Dolo", "t32-paracetamol", "650");
        product("in-t32-ma", "MinorA-T32", "t32-minor-a", "1");
        product("in-t32-mb", "MinorB-T32", "t32-minor-b", "1");
        operator = platformLoginAndGetAccessToken(seedOperator("kb32-" + UUID.randomUUID() + "@nabd.internal", "super_admin", false));
    }

    @AfterEach
    void clean() {
        JdbcTemplate su = su();
        su.update("DELETE FROM drug_interaction_versions"); // cascades to drug_interactions and drug_duplicate_classes
        su.update("DELETE FROM patient_reported_medicines WHERE text LIKE 'T32 %'");
        su.update("DELETE FROM drug_ingredients WHERE drug_id LIKE 'in-t32-%'");
        su.update("DELETE FROM drug_master WHERE id LIKE 'in-t32-%'");
        su.update("DELETE FROM ingredients WHERE id LIKE 't32-%'");
        su.update("DELETE FROM drug_classes WHERE id LIKE 't32-%'");
    }

    // ── fixtures (all synthetic) ─────────────────────────────────────────

    private void ingredient(String id, String name, String classId) {
        su().update("INSERT INTO ingredients (id, name, class_ids) VALUES (?, ?, ?::text[])", id, name, classId == null ? "{}" : "{" + classId + "}");
    }

    private void product(String id, String brand, String ingredientId, String mg) {
        JdbcTemplate su = su();
        su.update("INSERT INTO drug_master (id, region, kb_id, brand, strength, form, status, version) VALUES (?,'IN',?,?,?,'tablet','active',1)",
                id, id, brand, mg + " mg");
        su.update("INSERT INTO drug_ingredients (drug_id, ingredient_id, amount, unit) VALUES (?, ?, ?::numeric, 'mg')", id, ingredientId, mg);
    }

    private static String pair(String a, String b, String severity) {
        return "{\"type\":\"interaction\",\"a\":\"" + a + "\",\"b\":\"" + b + "\",\"severity\":\"" + severity + "\","
                + "\"mechanism\":\"TEST DATA mechanism text\",\"management\":\"TEST DATA management text\",\"sourceRef\":\"TEST-REF-001\"}";
    }

    private String feed(String name, String header, List<String> rows) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add(header);
        lines.addAll(rows);
        Files.write(dir.resolve(name), lines);
        return "file:" + name;
    }

    private ResponseEntity<Map> load(String token, String uri) {
        return exchange(LOAD, HttpMethod.POST, authedJsonBody(token, Map.of("feedUri", uri)), Map.class);
    }

    private ResponseEntity<Map> activate(Object importId, String cso) {
        return exchange(LOAD + "/" + importId + "/activate", HttpMethod.POST,
                authedJsonBody(operator, cso == null ? Map.of() : Map.of("csoAckRef", cso)), Map.class);
    }

    /** Loads and activates a synthetic knowledge-base version through the real loader. */
    private String activeKb(String name, List<String> rows) throws Exception {
        ResponseEntity<Map> loaded = load(operator, feed(name, HEADER, rows));
        assertThat(loaded.getStatusCode()).as(String.valueOf(loaded.getBody())).isEqualTo(HttpStatus.OK);
        String id = (String) loaded.getBody().get("importId");
        assertThat(activate(id, "TEST-CSO-ACK").getStatusCode()).isEqualTo(HttpStatus.OK);
        return id;
    }

    private record Clinic(SeededTenant tenant, SeededStaff doctor, String token) {
    }

    private Clinic clinic() {
        SeededTenant tenant = seedTenant();
        int n = SEQ.incrementAndGet();
        SeededStaff doc = seedStaff(tenant, seedFullAccessRole(tenant.id()), "c32-" + n + "@a.com", "+9198032" + String.format("%05d", n), false);
        return new Clinic(tenant, doc, loginAndGetAccessToken(doc));
    }

    private String patient(Clinic c) {
        int n = SEQ.incrementAndGet();
        return (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "name", "T32 Patient " + n, "phone", "+9199932" + String.format("%05d", n), "dob", "1970-01-01", "gender", "male")), Map.class).getBody().get("id");
    }

    private String visit(Clinic c, String patientId) {
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "patientId", patientId, "doctorId", c.doctor().id().toString())), Map.class).getBody().get("id");
    }

    private static Map<String, Object> line(String generic, String drugId, String duration, String sig) {
        Map<String, Object> m = new HashMap<>(Map.of("drugName", generic, "duration", duration));
        if (drugId != null) {
            m.put("drugId", drugId);
        }
        if (sig != null) {
            m.put("sigPattern", sig);
        }
        return m;
    }

    private static Map<String, Object> decided(Map<String, Object> line, Map<String, Object> decision) {
        Map<String, Object> m = new HashMap<>(line);
        m.put("overrides", List.of(decision));
        return m;
    }

    private ResponseEntity<Map> save(Clinic c, String entry, List<Map<String, Object>> items) {
        return exchange("/v1/clinical/prescriptions/" + entry, HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("items", items)), Map.class);
    }

    private ResponseEntity<Map> sign(Clinic c, String entry) {
        return exchange("/v1/clinical/prescriptions/" + entry + "/sign", HttpMethod.POST, authed(c.token()), Map.class);
    }

    private ResponseEntity<Map> get(Clinic c, String entry) {
        return exchange("/v1/clinical/prescriptions/" + entry, HttpMethod.GET, authed(c.token()), Map.class);
    }

    /** A signed prescription of one coded line, from its own visit. */
    private String signedRx(Clinic c, String patientId, Map<String, Object> line) {
        String entry = visit(c, patientId);
        assertThat(save(c, entry, List.of(line)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sign(c, entry).getStatusCode()).isEqualTo(HttpStatus.OK);
        return entry;
    }

    /** Moves a signed prescription's signing time into the past. A signed row is immutable (V51 trigger), so this one
     * superuser session switches triggers off for its own statements only. */
    private void signedDaysAgo(String entry, int days) throws Exception {
        try (Connection con = superuserConnection(); Statement st = con.createStatement()) {
            st.execute("SET session_replication_role = replica");
            assertThat(st.executeUpdate("UPDATE prescriptions SET signed_at = now() - interval '" + days + " days' WHERE queue_entry_id = '"
                    + UUID.fromString(entry) + "'")).isEqualTo(1);
        }
    }

    private static List<Map> items(ResponseEntity<Map> rx) {
        return (List<Map>) rx.getBody().get("items");
    }

    private static List<Map> warnings(ResponseEntity<Map> rx, int item) {
        return (List<Map>) items(rx).get(item).get("warnings");
    }

    private static List<Map> ofKind(List<Map> warnings, String kind) {
        return warnings.stream().filter(w -> kind.equals(w.get("kind"))).toList();
    }

    private List<Map> activeMedicines(String token, String patientId) {
        ResponseEntity<Map> r = exchange("/v1/clinical/patients/" + patientId + "/active-medicines", HttpMethod.GET, authed(token), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map>) r.getBody().get("items");
    }

    private void reported(Clinic c, String patientId, String drugId, String text, LocalDate stoppedOn) {
        su().update("INSERT INTO patient_reported_medicines (tenant_id, patient_id, drug_id, text, stopped_on, recorded_by) VALUES (?,?,?,?,?,?)",
                c.tenant().id(), UUID.fromString(patientId), drugId, text, stoppedOn, c.doctor().id());
    }

    private static String type(ResponseEntity<Map> r) {
        return r.getBody() == null ? "" : String.valueOf(r.getBody().get("type"));
    }

    // ── no knowledge base: not checked, and never "nothing found" ─────────

    @Test
    void withNoKnowledgeBaseThePadBehavesAsBeforeAndTheAnswerSaysNotCheckedNeverNoneFound() throws Exception {
        Clinic c = clinic();
        String patientId = patient(c);
        signedRx(c, patientId, line("Warfarin", "in-t32-warf", "30 days", "1-0-0"));

        String entry = visit(c, patientId);
        ResponseEntity<Map> saved = save(c, entry, List.of(line("Ibuprofen", "in-t32-ibu", "5 days", "1-0-1"),
                line("Diclofenac", "in-t32-diclo", "5 days", null), line("Naproxen", "in-t32-napro", "5 days", null),
                line("Paracetamol", "in-t32-crocin", "5 days", "1-1-1"), line("Paracetamol", "in-t32-dolo", "5 days", "1-1-1")));
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody().get("interactionKb")).as("null = not checked").isNull();
        assertThat(saved.getBody().get("unresolvedWarnings")).isEqualTo(0);
        assertThat((List) saved.getBody().get("warnings")).isEmpty();
        for (Map item : items(saved)) {
            assertThat((List) item.get("warnings")).as("no warning of any kind, as before CLN-032").isEmpty();
            assertThat(item.get("allergyWarning")).isNull();
            assertThat(item.get("controlledSubstanceWarning")).isNull();
            assertThat(item.get("interactionCheck")).as("null = not checked, never \"checked\"").isNull();
        }
        // nothing in the answer can be read as a clean interaction result
        String body = objectMapper.writeValueAsString(saved.getBody()).toLowerCase();
        assertThat(body).doesNotContain("no interaction").doesNotContain("none found").doesNotContain("\"ddi\"").doesNotContain("\"duplicate\"");
        assertThat(sign(c, entry).getStatusCode()).isEqualTo(HttpStatus.OK);
        String audited = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT after::text FROM audit_log WHERE tenant_id = ? AND action = 'prescription.signed' ORDER BY created_at DESC LIMIT 1",
                String.class, c.tenant().id()));
        assertThat(audited).contains("\"interactionKb\": \"not_checked\"");

        // a version that is loaded but not activated changes nothing either
        ResponseEntity<Map> loaded = load(operator, feed("pending.jsonl", HEADER, STANDARD));
        assertThat(loaded.getBody()).containsEntry("status", "validated").containsEntry("pairs", 2).containsEntry("duplicateClasses", 1);
        String second = visit(c, patientId);
        ResponseEntity<Map> again = save(c, second, List.of(line("Ibuprofen", "in-t32-ibu", "5 days", null)));
        assertThat(again.getBody().get("interactionKb")).isNull();
        assertThat(warnings(again, 0)).isEmpty();
    }

    // ── AC1: active medicine from a signed Rx, major interaction, source, decision flow ──

    @Test
    void ac1_aPatientOnWarfarinGetsAMajorInteractionOnIbuprofenWithTheKbSourceAndSigningWaitsForADecision() throws Exception {
        activeKb("kb.jsonl", STANDARD);
        Clinic c = clinic();
        String patientId = patient(c);
        String first = signedRx(c, patientId, new HashMap<>(Map.of("drugName", "Warfarin", "brandName", "Warf-T32", "drugId", "in-t32-warf", "duration", "30 days")));

        String entry = visit(c, patientId);
        save(c, entry, List.of(line("Ibuprofen", "in-t32-ibu", "5 days", null))); // warm-up: the timed call below is a re-save
        long t0 = System.nanoTime();
        ResponseEntity<Map> saved = save(c, entry, List.of(line("Ibuprofen", "in-t32-ibu", "5 days", null)));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        System.out.println("CLN-032 AC1 save-with-check ms=" + ms);
        assertThat(ms).as("warning shown in < 1 s").isLessThan(1000);

        assertThat(saved.getBody().get("interactionKb")).isEqualTo(KB_LABEL);
        assertThat(saved.getBody().get("unresolvedWarnings")).isEqualTo(1);
        List<Map> ddi = ofKind(warnings(saved, 0), "ddi");
        assertThat(ddi).hasSize(1);
        Map w = ddi.get(0);
        assertThat(w).containsEntry("severity", "major").containsEntry("requiresDecision", true).containsEntry("resolved", false)
                .containsEntry("source", KB_LABEL).containsEntry("sourceRef", "TEST-REF-001")
                .containsEntry("mechanism", "TEST DATA mechanism text").containsEntry("management", "TEST DATA management text")
                .containsEntry("otherDrug", "Warf-T32").containsEntry("otherFrom", "rx").containsEntry("matched", "ibuprofen + warfarin");
        assertThat(w.get("otherLineRef")).isNull();
        assertThat((List) w.get("reasonOptions")).isNotEmpty();
        assertThat(items(saved).get(0).get("controlledSubstanceWarning")).as("a ddi warning has no plain-text twin").isNull();

        ResponseEntity<Map> refused = sign(c, entry);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(type(refused)).contains("warning-unresolved");

        // a decision must name the warning it is for, must use a reason of that kind, and does not cover another warning
        Map<String, Object> ibu = line("Ibuprofen", "in-t32-ibu", "5 days", null);
        ResponseEntity<Map> unnamed = save(c, entry, List.of(decided(ibu, Map.of("kind", "ddi", "reasonCode", "benefit_outweighs_risk"))));
        assertThat(unnamed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(type(unnamed)).contains("invalid-override-match");
        ResponseEntity<Map> wrongReason = save(c, entry, List.of(decided(ibu, Map.of("kind", "ddi", "reasonCode", "obstetric_consultation", "matched", "ibuprofen + warfarin"))));
        assertThat(wrongReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> otherWarning = save(c, entry, List.of(decided(ibu, Map.of("kind", "ddi", "reasonCode", "benefit_outweighs_risk", "matched", "ibuprofen + something else"))));
        assertThat(otherWarning.getBody().get("unresolvedWarnings")).isEqualTo(1);
        assertThat(sign(c, entry).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<Map> decidedRx = save(c, entry, List.of(decided(ibu, Map.of("kind", "ddi", "reasonCode", "benefit_outweighs_risk", "matched", "ibuprofen + warfarin"))));
        assertThat(decidedRx.getBody().get("unresolvedWarnings")).isEqualTo(0);
        Map resolved = ofKind(warnings(decidedRx, 0), "ddi").get(0);
        assertThat(resolved).containsEntry("resolved", true);
        assertThat(((Map) resolved.get("decision")).get("reasonCode")).isEqualTo("benefit_outweighs_risk");
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'prescription.interaction_override'", Long.class, c.tenant().id()))).isEqualTo(1L);

        assertThat(sign(c, entry).getStatusCode()).isEqualTo(HttpStatus.OK);
        String audited = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT after::text FROM audit_log WHERE tenant_id = ? AND action = 'prescription.signed' ORDER BY created_at DESC LIMIT 1",
                String.class, c.tenant().id()));
        assertThat(audited).contains(KB_LABEL);

        // the signed prescription keeps showing the warning with its decision; the earlier one is not judged against the later one
        Map after = ofKind(warnings(get(c, entry), 0), "ddi").get(0);
        assertThat(after).containsEntry("resolved", true).containsEntry("otherDrug", "Warf-T32");
        assertThat(ofKind(warnings(get(c, first), 0), "ddi")).isEmpty();
    }

    // ── AC2 / AC3: duplicate therapy ─────────────────────────────────────

    @Test
    void ac2_aSecondNsaidInTheDraftIsDuplicateTherapyOnTheNewLineOnly() throws Exception {
        activeKb("kb.jsonl", STANDARD);
        Clinic c = clinic();
        String entry = visit(c, patient(c));
        ResponseEntity<Map> saved = save(c, entry, List.of(line("Diclofenac", "in-t32-diclo", "5 days", null), line("Naproxen", "in-t32-napro", "5 days", null)));

        assertThat(warnings(saved, 0)).isEmpty();
        List<Map> dup = ofKind(warnings(saved, 1), "duplicate");
        assertThat(dup).hasSize(1);
        assertThat(dup.get(0)).containsEntry("matched", "same class: NSAID").containsEntry("severity", "major").containsEntry("requiresDecision", true)
                .containsEntry("otherDrug", "Diclofenac").containsEntry("otherFrom", "draft").containsEntry("otherLineRef", 0)
                .containsEntry("source", KB_LABEL).containsEntry("sourceRef", "TEST-REF-CLASS");
        assertThat(String.valueOf(dup.get(0).get("message"))).contains("NSAID").contains("Diclofenac");
        assertThat(sign(c, entry).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        Map<String, Object> napro = decided(line("Naproxen", "in-t32-napro", "5 days", null),
                Map.of("kind", "duplicate", "reasonCode", "intended_combination", "matched", "same class: NSAID"));
        assertThat(save(c, entry, List.of(line("Diclofenac", "in-t32-diclo", "5 days", null), napro)).getBody().get("unresolvedWarnings")).isEqualTo(0);
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'prescription.duplicate_override'", Long.class, c.tenant().id()))).isEqualTo(1L);
        assertThat(sign(c, entry).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ac3_theSameIngredientInTwoBrandsIsADuplicateIngredientWithTheTotalDailyDose() throws Exception {
        activeKb("kb.jsonl", STANDARD);
        Clinic c = clinic();
        String entry = visit(c, patient(c));
        ResponseEntity<Map> saved = save(c, entry, List.of(line("Crocin", "in-t32-crocin", "5 days", "1-1-1"), line("Dolo", "in-t32-dolo", "5 days", "1-1-1")));

        assertThat(warnings(saved, 0)).isEmpty();
        List<Map> dup = ofKind(warnings(saved, 1), "duplicate");
        assertThat(dup).hasSize(1);
        assertThat(dup.get(0)).containsEntry("matched", "same ingredient: paracetamol").containsEntry("otherDrug", "Crocin")
                .containsEntry("severity", "moderate") // the knowledge base header's duplicateIngredientSeverity
                .containsEntry("totalDailyDose", "3450 mg/day = 650 mg x 3 (Dolo) + 500 mg x 3 (Crocin)");
        assertThat(String.valueOf(dup.get(0).get("message"))).contains("paracetamol").contains("3450 mg/day");

        // without a readable pattern on one line the total is not calculated (never a partial sum); the warning stays
        ResponseEntity<Map> noSig = save(c, entry, List.of(line("Crocin", "in-t32-crocin", "5 days", null), line("Dolo", "in-t32-dolo", "5 days", "1-1-1")));
        Map w = ofKind(warnings(noSig, 1), "duplicate").get(0);
        assertThat(w.get("totalDailyDose")).isNull();
        assertThat(String.valueOf(w.get("message"))).doesNotContain("mg/day");
    }

    // ── AC4: a minor interaction is advisory ─────────────────────────────

    @Test
    void ac4_aMinorInteractionIsAdvisoryAndDoesNotBlockSigning() throws Exception {
        activeKb("kb.jsonl", STANDARD);
        Clinic c = clinic();
        String entry = visit(c, patient(c));
        ResponseEntity<Map> saved = save(c, entry, List.of(line("T32 minor A", "in-t32-ma", "5 days", null), line("T32 minor B", "in-t32-mb", "5 days", null)));

        Map w = ofKind(warnings(saved, 1), "ddi").get(0);
        assertThat(w).containsEntry("severity", "minor").containsEntry("requiresDecision", false).containsEntry("resolved", true);
        assertThat((List) w.get("reasonOptions")).isEmpty();
        assertThat(saved.getBody().get("unresolvedWarnings")).isEqualTo(0);
        ResponseEntity<Map> signed = sign(c, entry);
        assertThat(signed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(signed.getBody().get("status")).isEqualTo("signed");
    }

    // ── AC6: a course that ended is not current ──────────────────────────

    @Test
    void ac6_aCourseThatEndedThreeDaysAgoIsNotCurrentAndOneStillRunningIs() throws Exception {
        activeKb("kb.jsonl", STANDARD);
        Clinic c = clinic();
        String patientId = patient(c);
        String first = signedRx(c, patientId, line("Warfarin", "in-t32-warf", "5 days", null));
        LocalDate today = LocalDate.now(ZoneOffset.UTC); // seeded clinics are in UTC

        signedDaysAgo(first, 8); // 5-day course signed 8 days ago: ended 3 days ago
        assertThat(activeMedicines(c.token(), patientId)).isEmpty();
        String entry = visit(c, patientId);
        ResponseEntity<Map> saved = save(c, entry, List.of(line("Ibuprofen", "in-t32-ibu", "5 days", null)));
        assertThat(saved.getBody().get("interactionKb")).isEqualTo(KB_LABEL);
        assertThat(warnings(saved, 0)).isEmpty();
        assertThat(sign(c, entry).getStatusCode()).isEqualTo(HttpStatus.OK);

        signedDaysAgo(first, 3); // the same course signed 3 days ago is still running
        List<Map> active = activeMedicines(c.token(), patientId);
        assertThat(active).filteredOn(m -> "in-t32-warf".equals(m.get("drugId"))).singleElement()
                .satisfies(m -> assertThat(m).containsEntry("name", "Warfarin").containsEntry("from", "rx").containsEntry("until", today.plusDays(2).toString()));
        String next = visit(c, patientId);
        assertThat(ofKind(warnings(save(c, next, List.of(line("Naproxen", "in-t32-napro", "5 days", null))), 0), "duplicate"))
                .as("the ibuprofen signed today is current: a second NSAID is duplicate therapy against it").hasSize(1);
    }

    @Test
    void aRevokedPrescriptionAndAStoppedReportedMedicineAreNotCurrent_anOpenEndedOrReportedOneIs() throws Exception {
        activeKb("kb.jsonl", STANDARD);
        Clinic c = clinic();
        String patientId = patient(c);
        String revoked = signedRx(c, patientId, line("Warfarin", "in-t32-warf", "30 days", null));
        assertThat(exchange("/v1/clinical/prescriptions/" + revoked + "/revoke", HttpMethod.POST,
                authedJsonBody(c.token(), Map.of("reason", "T32 test revoke")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        reported(c, patientId, "in-t32-warf", "T32 reported, stopped", LocalDate.now(ZoneOffset.UTC).minusDays(1));
        assertThat(activeMedicines(c.token(), patientId)).isEmpty();
        String entry = visit(c, patientId);
        assertThat(warnings(save(c, entry, List.of(line("Ibuprofen", "in-t32-ibu", "5 days", null))), 0)).isEmpty();

        // a patient-reported medicine that is not stopped counts, coded or not; only the coded one can be checked
        reported(c, patientId, "in-t32-warf", "T32 reported warfarin", null);
        reported(c, patientId, null, "T32 reported free text", null);
        assertThat(activeMedicines(c.token(), patientId)).extracting(m -> m.get("from") + "|" + m.get("drugId") + "|" + m.get("name") + "|" + m.get("until"))
                .containsExactlyInAnyOrder("reported|in-t32-warf|T32 reported warfarin|null", "reported|null|T32 reported free text|null");
        Map w = ofKind(warnings(save(c, entry, List.of(line("Ibuprofen", "in-t32-ibu", "5 days", null))), 0), "ddi").get(0);
        assertThat(w).containsEntry("otherFrom", "reported").containsEntry("otherDrug", "T32 reported warfarin").containsEntry("severity", "major");

        // a signed line whose duration cannot be read has no known end: it stays current
        Clinic other = clinic();
        String p2 = patient(other);
        signedDaysAgo(signedRx(other, p2, line("Warfarin", "in-t32-warf", "till review", null)), 400);
        assertThat(activeMedicines(other.token(), p2)).singleElement().satisfies(m -> assertThat(m.get("until")).isNull());
    }

    @Test
    void anUncodedLineIsNeverCheckedEvenWithAnActiveKnowledgeBase() throws Exception {
        activeKb("kb.jsonl", STANDARD);
        Clinic c = clinic();
        String entry = visit(c, patient(c));
        ResponseEntity<Map> saved = save(c, entry, List.of(line("Warfarin", null, "5 days", null), line("Ibuprofen", "in-t32-ibu", "5 days", null)));
        assertThat(saved.getBody().get("interactionKb")).isEqualTo(KB_LABEL);
        assertThat(items(saved).get(0).get("coded")).isEqualTo(false);
        assertThat(items(saved).get(0).get("interactionCheck")).isEqualTo("not_checked");
        assertThat(items(saved).get(1).get("interactionCheck")).isEqualTo("checked");
        assertThat(ofKind(warnings(saved, 0), "ddi")).isEmpty();
        assertThat(ofKind(warnings(saved, 1), "ddi")).as("free text is not matched to an ingredient: the pad must flag the line as not checked").isEmpty();
    }

    // ── active medicines: permission, read scope, tenant isolation ───────

    @Test
    void activeMedicinesNeedsClinicalViewTheReadScopeAndTheOwnTenant() throws Exception {
        Clinic c = clinic();
        String patientId = patient(c);
        signedRx(c, patientId, line("Warfarin", "in-t32-warf", "30 days", null));
        reported(c, patientId, null, "T32 reported free text", null);
        assertThat(activeMedicines(c.token(), patientId)).hasSize(2);

        String path = "/v1/clinical/patients/" + patientId + "/active-medicines";
        int n = SEQ.incrementAndGet();
        SeededStaff desk = seedStaff(c.tenant(), seedRole(c.tenant().id(), "Desk" + n, false, new ModuleGrant("patients", true, true, true, false, false, false, false)),
                "desk32-" + n + "@a.com", "+9198132" + String.format("%05d", n), false);
        assertThat(exchange(path, HttpMethod.GET, authed(loginAndGetAccessToken(desk)), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // IDC-001: a doctor limited to own patients who never saw this patient gets the same 404 as for an unknown patient
        SeededStaff own = seedStaff(c.tenant(), seedRole(c.tenant().id(), "Own" + n, false, new ModuleGrant("clinical", true, true, true, false, false, false, false)),
                "own32-" + n + "@a.com", "+9198232" + String.format("%05d", n), false);
        su().update("UPDATE staff SET scope = 'own_patients_only' WHERE id = ?", own.id());
        ResponseEntity<Map> scoped = exchange(path, HttpMethod.GET, authed(loginAndGetAccessToken(own)), Map.class);
        assertThat(scoped.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(String.valueOf(scoped.getBody())).doesNotContain("Warfarin").doesNotContain("T32 reported");

        // another clinic: 404, and its own rows are invisible under RLS
        Clinic other = clinic();
        ResponseEntity<Map> foreign = exchange(path, HttpMethod.GET, authed(other.token()), Map.class);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(inTenantTx(other.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_reported_medicines", Integer.class))).isZero();
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM patient_reported_medicines", Integer.class))).isEqualTo(1);
        // one PHI read row for the one successful read (the writer is asynchronous: wait for it, then make sure it stays one)
        String phiReads = "SELECT count(*) FROM phi_read_log WHERE tenant_id = ? AND resource_type = 'active_medicines' AND patient_id = ?::uuid";
        for (int i = 0; i < 100 && su().queryForObject(phiReads, Integer.class, c.tenant().id(), patientId) == 0; i++) {
            Thread.sleep(100);
        }
        Thread.sleep(300);
        assertThat(su().queryForObject(phiReads, Integer.class, c.tenant().id(), patientId)).isEqualTo(1);
    }

    // ── loader and version handling ──────────────────────────────────────

    @Test
    void theLoaderRefusesABadFeedWholeAndKeepsNothing() throws Exception {
        String badHeader = HEADER.replace("\"duplicateIngredientSeverity\":\"moderate\"", "\"duplicateIngredientSeverity\":\"severe\"");
        Map<String, List<String>> bad = new HashMap<>();
        bad.put("unknown ingredient", List.of(WARFARIN_IBUPROFEN, pair("t32-warfarin", "t32-not-in-master", "major")));
        bad.put("unknown class", List.of("{\"type\":\"duplicate-class\",\"classId\":\"t32-not-a-class\",\"severity\":\"major\"}"));
        bad.put("same ingredient twice", List.of(pair("t32-warfarin", "t32-warfarin", "major")));
        bad.put("pair repeated in the other order", List.of(WARFARIN_IBUPROFEN, pair("t32-ibuprofen", "t32-warfarin", "minor")));
        bad.put("bad severity", List.of(pair("t32-warfarin", "t32-ibuprofen", "severe")));
        bad.put("unknown record type", List.of("{\"type\":\"note\",\"severity\":\"major\"}"));
        bad.put("not json", List.of("warfarin,ibuprofen,major"));
        bad.put("no records", List.of());
        int n = 0;
        for (Map.Entry<String, List<String>> e : bad.entrySet()) {
            ResponseEntity<Map> r = load(operator, feed("bad" + (n++) + ".jsonl", HEADER, e.getValue()));
            assertThat(r.getStatusCode()).as(e.getKey()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(type(r)).as(e.getKey()).contains("FEED_INVALID");
            assertThat(String.valueOf(r.getBody())).as("the message names the record and the rule, not the values").doesNotContain("t32-");
        }
        assertThat(load(operator, feed("badheader.jsonl", badHeader, STANDARD)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(load(operator, feed("drugfeed.jsonl", HEADER.replace("\"interactions\"", "\"full\""), STANDARD)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(load(operator, "file:../outside.jsonl").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(su().queryForObject("SELECT count(*) FROM drug_interaction_versions", Integer.class)).as("nothing of a refused feed is kept").isZero();
        assertThat(su().queryForObject("SELECT count(*) FROM drug_interactions", Integer.class)).isZero();
    }

    @Test
    void loadingIsOperatorOnlyActivationNeedsTheCsoReferenceAndTheAppRoleCannotWriteTheTables() throws Exception {
        Clinic c = clinic();
        String uri = feed("kb.jsonl", HEADER, STANDARD);
        assertThat(load(c.token(), uri).getStatusCode()).as("a clinic token is not a platform token").isEqualTo(HttpStatus.UNAUTHORIZED);
        String noKbImport = platformLoginAndGetAccessToken(seedOperator("impl32-" + UUID.randomUUID() + "@nabd.internal", "implementation", false));
        assertThat(load(noKbImport, uri).getStatusCode()).as("an operator without kb_import:edit").isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> loaded = load(operator, uri);
        assertThat(loaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loaded.getBody()).containsEntry("region", "IN").containsEntry("kbVersion", 1).containsEntry("status", "validated");
        String id = (String) loaded.getBody().get("importId");
        assertThat(load(operator, uri).getBody()).as("the same file again is the same version").containsEntry("importId", id).containsEntry("kbVersion", 1);
        assertThat(su().queryForObject("SELECT count(*) FROM drug_interaction_versions", Integer.class)).isEqualTo(1);

        assertThat(activate(id, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(activate(id, "   ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange(LOAD + "/" + id + "/activate", HttpMethod.POST, authedJsonBody(noKbImport, Map.of("csoAckRef", "x")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(su().queryForObject("SELECT count(*) FROM drug_interaction_versions WHERE status = 'active'", Integer.class)).isZero();
        assertThat(activate(UUID.randomUUID(), "TEST-CSO-ACK").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> active = activate(id, "TEST-CSO-ACK");
        assertThat(active.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(active.getBody()).containsEntry("status", "active").containsEntry("kbVersion", 1);
        assertThat(activate(id, "TEST-CSO-ACK").getStatusCode()).as("idempotent").isEqualTo(HttpStatus.OK);
        assertThat(su().queryForObject("SELECT cso_ack_ref FROM drug_interaction_versions WHERE id = ?::uuid", String.class, id)).isEqualTo("TEST-CSO-ACK");
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id IS NULL AND action LIKE 'drug_interactions.%' AND entity_id = ?::uuid",
                Integer.class, id)).isEqualTo(2);

        assertThat(appJdbc.queryForObject("SELECT count(*) FROM drug_interactions", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> appJdbc.update("UPDATE drug_interactions SET severity = 'minor'")).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> appJdbc.update("DELETE FROM drug_duplicate_classes")).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> appJdbc.update("UPDATE drug_interaction_versions SET status = 'active'")).rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void aNewVersionReplacesTheOldOneOnlyWhenActivatedAndTheOldOneCanBeActivatedAgain() throws Exception {
        String v1 = activeKb("v1.jsonl", STANDARD);
        Clinic c = clinic();
        String patientId = patient(c);
        signedRx(c, patientId, line("Warfarin", "in-t32-warf", "30 days", null));
        String entry = visit(c, patientId);
        List<Map<String, Object>> ibu = List.of(line("Ibuprofen", "in-t32-ibu", "5 days", null));
        assertThat(ofKind(warnings(save(c, entry, ibu), 0), "ddi").get(0)).containsEntry("severity", "major");

        // version 2 states the pair differently (synthetic): loaded but not active = version 1 still answers
        ResponseEntity<Map> loaded = load(operator, feed("v2.jsonl", HEADER.replace("2026-10", "2026-11"), List.of(pair("t32-warfarin", "t32-ibuprofen", "moderate"))));
        assertThat(loaded.getBody()).containsEntry("kbVersion", 2);
        assertThat(ofKind(warnings(save(c, entry, ibu), 0), "ddi").get(0)).containsEntry("severity", "major").containsEntry("source", KB_LABEL);

        assertThat(activate(loaded.getBody().get("importId"), "TEST-CSO-ACK-2").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> onV2 = save(c, entry, ibu);
        assertThat(ofKind(warnings(onV2, 0), "ddi").get(0)).containsEntry("severity", "moderate").containsEntry("requiresDecision", false)
                .containsEntry("source", "SYNTHETIC-TEST-KB 2026-11");
        assertThat(onV2.getBody().get("interactionKb")).isEqualTo("SYNTHETIC-TEST-KB 2026-11");
        assertThat(su().queryForObject("SELECT status FROM drug_interaction_versions WHERE id = ?::uuid", String.class, v1)).isEqualTo("superseded");

        assertThat(activate(v1, "TEST-CSO-ROLLBACK").getStatusCode()).isEqualTo(HttpStatus.OK); // back to version 1
        assertThat(ofKind(warnings(save(c, entry, ibu), 0), "ddi").get(0)).containsEntry("severity", "major").containsEntry("source", KB_LABEL);
        assertThat(su().queryForObject("SELECT count(*) FROM drug_interaction_versions WHERE status = 'active'", Integer.class)).isEqualTo(1);
    }

    // ── NFR: 10-line Rx with 15 active medicines ─────────────────────────

    @Test
    void nfr_aTenLineDraftAgainstFifteenActiveMedicinesIsCheckedWellUnderOneSecond() throws Exception {
        List<String> rows = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            String k = String.format("%02d", i);
            ingredient("t32-perf-" + k, "T32 perf " + k, null);
            product("in-t32-perf-" + k, "Perf-T32 " + k, "t32-perf-" + k, "10");
        }
        for (int d = 16; d <= 25; d++) { // every draft ingredient has a (synthetic, minor) pair with two active ones
            rows.add(pair("t32-perf-" + String.format("%02d", d), "t32-perf-" + String.format("%02d", d - 15), "minor"));
            rows.add(pair("t32-perf-" + String.format("%02d", d), "t32-perf-" + String.format("%02d", d - 10), "minor"));
        }
        activeKb("perf.jsonl", rows);
        Clinic c = clinic();
        String patientId = patient(c);
        List<Map<String, Object>> fifteen = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            fifteen.add(line("T32 perf " + i, "in-t32-perf-" + String.format("%02d", i), "30 days", "1-0-1"));
        }
        String first = visit(c, patientId);
        assertThat(save(c, first, fifteen).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sign(c, first).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(activeMedicines(c.token(), patientId)).hasSize(15);

        List<Map<String, Object>> ten = new ArrayList<>();
        for (int i = 16; i <= 25; i++) {
            ten.add(line("T32 perf " + i, "in-t32-perf-" + String.format("%02d", i), "5 days", "1-0-1"));
        }
        String entry = visit(c, patientId);
        ResponseEntity<Map> warm = save(c, entry, ten);
        assertThat(items(warm)).allSatisfy(item -> assertThat(ofKind((List<Map>) item.get("warnings"), "ddi")).hasSize(2));
        List<Long> samples = new ArrayList<>();
        for (int run = 0; run < 20; run++) {
            long t0 = System.nanoTime();
            assertThat(save(c, entry, ten).getStatusCode()).isEqualTo(HttpStatus.OK);
            samples.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
        }
        List<Long> sorted = samples.stream().sorted().toList();
        long p95 = sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1);
        System.out.println("CLN-032 10-line save with 15 active medicines ms " + samples + " p95=" + p95);
        assertThat(p95).as("p95 of the whole save (check included)").isLessThan(1000);
    }
}

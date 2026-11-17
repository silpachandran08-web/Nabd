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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S24 QA (t1) CLN-024: spelling/brand variants of the AC examples, controlled lines whatever the input, refills counted per dispense,
 * a draft that straddles a rule activation, boundaries, permissions. Drug words are the story's examples, used as test input only;
 * the q24 product and class are synthetic.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S24QaCln024RxValidityApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 100_000);
    private static final List<Map<String, Object>> CONTROLLED_OK = List.of(Map.of("kind", "controlled", "reasonCode", "documentation_confirmed"));

    @Autowired SafetyDictionaryService dictionaries;
    private Runnable restore;

    private record Clinic(SeededTenant tenant, SeededStaff staff, String token) {
    }

    private static JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private static String phone() {
        return "+9193" + String.format("%08d", SEQ.incrementAndGet());
    }

    @BeforeEach
    void seed() {
        restore = SafetyDictionaryFixtures.snapshotState(superuserDataSource(), dictionaries);
        clean();
        JdbcTemplate su = su();
        su.update("INSERT INTO drug_classes (id, name) VALUES ('q24-biguanide', 'Q24 test class') ON CONFLICT DO NOTHING");
        su.update("INSERT INTO ingredients (id, name, class_ids) VALUES ('q24-ing-met', 'Q24 ingredient', '{q24-biguanide}') ON CONFLICT DO NOTHING");
        su.update("INSERT INTO drug_master (id, region, kb_id, brand, strength, form, schedule, status, version) VALUES "
                + "('in-q24-met', 'IN', 'in-q24-met', 'Q24 Metformin', '500 mg', 'tablet', '{}', 'active', 1)");
        su.update("INSERT INTO drug_ingredients (drug_id, ingredient_id) VALUES ('in-q24-met', 'q24-ing-met')");
        su.update("INSERT INTO rx_validity_rules (region, version, class_id, default_days, max_days, max_refills) VALUES ('IN', 0, 'q24-biguanide', 30, 30, 2)");
    }

    @AfterEach
    void clean() {
        if (restore != null) restore.run();
        JdbcTemplate su = su();
        su.update("DELETE FROM rx_validity_rules WHERE class_id LIKE 'q24-%'");
        su.update("UPDATE charge_catalogue SET drug_id = NULL WHERE drug_id LIKE '%q24-%'");
        su.update("DELETE FROM drug_ingredients WHERE drug_id LIKE '%q24-%'");
        su.update("DELETE FROM drug_master WHERE id LIKE '%q24-%'");
        su.update("DELETE FROM ingredients WHERE id LIKE 'q24-%'");
        su.update("DELETE FROM drug_classes WHERE id LIKE 'q24-%'");
    }

    private Clinic clinic() {
        SeededTenant t = seedTenant();
        SeededStaff s = ClinicianFixture.licensed(seedStaff(t, seedFullAccessRole(t.id()), "q24" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        String token = loginAndGetAccessToken(s);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(token, Map.of("mode", "hybrid", "reason", "QA")), Map.class);
        return new Clinic(t, s, token);
    }

    private Clinic ksa() {
        SeededTenant t = seedTenant();
        try (java.sql.Connection c = superuserConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("UPDATE tenants SET region_code = 'SA' WHERE id = '" + t.id() + "'");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        SeededStaff s = ClinicianFixture.licensed(seedStaff(t, seedFullAccessRole(t.id()), "k24" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        return new Clinic(t, s, loginAndGetAccessToken(s));
    }

    private String visit(Clinic c) {
        String p = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", "Q24 Patient " + SEQ.incrementAndGet(),
                "phone", phone(), "dob", java.time.LocalDate.of(1961, 1, 1).plusDays(SEQ.incrementAndGet() % 9000).toString(), "gender", "male")),
                Map.class).getBody().get("id");
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(c.token(), Map.of("patientId", p,
                "doctorId", c.staff().id().toString())), Map.class).getBody().get("id");
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

    private static Map<String, Object> validity(Map<String, Object> rx, int i) {
        return (Map<String, Object>) ((Map<String, Object>) ((List<?>) rx.get("items")).get(i)).get("validity");
    }

    private static String slug(ResponseEntity<Map> r) {
        String type = String.valueOf(r.getBody().get("type"));
        return type.substring(type.lastIndexOf('/') + 1);
    }

    // C24-A01: the AC's antibiotic under its Indian spelling and a common brand is still a 5-day line
    @Test
    void c24a01_amoxicillinSpellingsAndBrandAreFiveDays() {
        Clinic c = clinic();
        String qe = visit(c);
        ResponseEntity<Map> r = save(c, qe, line("Amoxicillin 500 mg"), line("Amoxycillin 500mg capsule"), line("AUGMENTIN 625 DUO"));
        List<Object> days = new ArrayList<>();
        for (int i = 0; i < 3; i++) days.add(validity(r.getBody(), i).get("days") + "/" + validity(r.getBody(), i).get("classId"));
        System.out.println("C24-A01 draft days/class=" + days);
        for (int i = 0; i < 3; i++) assertThat(validity(r.getBody(), i)).as("line %s", i).containsEntry("days", 5).containsEntry("maxRefills", 0);
        ResponseEntity<Map> signed = sign(c, qe);
        assertThat(signed.getStatusCode()).as("%s", signed.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<byte[]> pdf = exchange("/v1/clinical/prescriptions/" + qe + "/pdf", HttpMethod.GET, authed(c.token()), byte[].class);
        assertThat(new String(pdf.getBody(), java.nio.charset.StandardCharsets.ISO_8859_1)).contains("Valid until");
    }

    // C24-A02: 6 days on an antibiotic is above the class max (boundary), 5 is accepted
    @Test
    void c24a02_antibioticBoundary() {
        Clinic c = clinic();
        String qe = visit(c);
        ResponseEntity<Map> six = save(c, qe, line("Amoxicillin 500 mg", "validityDays", 6));
        ResponseEntity<Map> five = save(c, qe, line("Amoxicillin 500 mg", "validityDays", 5));
        System.out.println("C24-A02 6days=" + six.getStatusCode() + " " + slug(six) + " 5days=" + five.getStatusCode());
        assertThat(six.getStatusCode().value()).isEqualTo(422);
        assertThat(slug(six)).isEqualTo("VALIDITY_ABOVE_CLASS_MAX");
        assertThat(five.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // C24-A03: a controlled line is 7 days / 0 refills at sign whatever is sent (upper case, refills only, a shorter validity)
    @Test
    void c24a03_tramadolIsSevenDaysNoRefillsWhateverIsSent() {
        Clinic c = clinic();
        String qe = visit(c);
        ResponseEntity<Map> saved = save(c, qe, line("TRAMADOL HCL 50MG", "refills", 2, "overrides", CONTROLLED_OK),
                line("tramadol 50 mg", "validityDays", 3, "overrides", CONTROLLED_OK),
                line("Ultracet tablet", "refills", 0));
        System.out.println("C24-A03 save=" + saved.getStatusCode() + " " + (saved.getStatusCode().is2xxSuccessful() ? "" : saved.getBody()));
        if (saved.getStatusCode().is2xxSuccessful()) {
            ResponseEntity<Map> signed = sign(c, qe);
            assertThat(signed.getStatusCode()).as("%s", signed.getBody()).isEqualTo(HttpStatus.OK);
            System.out.println("C24-A03 signed lines=" + validity(signed.getBody(), 0) + " | " + validity(signed.getBody(), 1)
                    + " | brand-only (observation, not on the IN controlled list)=" + validity(signed.getBody(), 2));
            for (int i = 0; i < 2; i++) {
                assertThat(validity(signed.getBody(), i)).as("line %s", i).containsEntry("days", 7).containsEntry("refills", 0);
            }
        } else {
            assertThat(saved.getStatusCode().value()).isEqualTo(422); // refusing the input is also safe
        }
    }

    // C24-A04 (decision, not a bug): what '2 refills' allows, and whether all fills can leave in one bill
    @Test
    void c24a04_twoRefillsDecisionAndAllFillsInOneBill() {
        Clinic c = clinic();
        String qe = visit(c);
        assertThat(save(c, qe, line("Metformin 500 mg", "drugId", "in-q24-met", "refills", 2)).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> rx = sign(c, qe);
        assertThat(rx.getStatusCode()).isEqualTo(HttpStatus.OK);
        Object lineId = ((Map<String, Object>) ((List<?>) rx.getBody().get("items")).get(0)).get("id");
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(c.token(), Map.of("mode", "hybrid", "reason", "CLN-024 QA")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> item = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(c.token(), Map.of("name", "Q24 Met stock " + SEQ.incrementAndGet(),
                "isRx", true, "hsnCode", "3004", "price", 10.00, "taxRatePercent", 0, "stockQty", 200)), Map.class);
        assertThat(item.getStatusCode().is2xxSuccessful()).as("item %s", item.getBody()).isTrue();
        assertThat(lineId).as("signed line id %s", rx.getBody()).isNotNull();
        String code = (String) item.getBody().get("code");
        List<String> outcomes = new ArrayList<>();
        for (int qty : new int[]{30, 1}) {
            ResponseEntity<Map> d = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(c.token(), Map.of("lineItems", List.of(
                    Map.of("chargeCode", code, "quantity", qty, "prescriptionItemId", lineId)))), Map.class);
            outcomes.add(qty + "->" + d.getStatusCode().value() + (d.getStatusCode().is2xxSuccessful() ? "" : ":" + slug(d)));
        }
        System.out.println("C24-A04 qty10 refills2: one bill of 30 then 1 more = " + outcomes + " (first fill + 2 refills = 3 fills; the 4th dispense is refused)");
        assertThat(outcomes.get(1)).contains("REFILLS_EXHAUSTED");
    }

    // C24-A05: a draft saved under the old rules and signed after a new activation follows the new rules (or is refused), never the old max
    @Test
    void c24a05_draftAcrossARuleActivation() {
        Clinic c = clinic();
        String signedBefore = visit(c);
        assertThat(save(c, signedBefore, line("Paracetamol 500 mg")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sign(c, signedBefore).getStatusCode()).isEqualTo(HttpStatus.OK);
        String draft = visit(c);
        assertThat(save(c, draft, line("Paracetamol 500 mg", "validityDays", 20)).getStatusCode()).isEqualTo(HttpStatus.OK);

        String op = platformLoginAndGetAccessToken(seedOperator("sa-q24-" + UUID.randomUUID() + "@nabd.internal", "super_admin", false));
        SafetyDictionaryBundle in = SafetyDictionaryFixtures.bundle("IN");
        SafetyDictionaryBundle v1 = new SafetyDictionaryBundle(in.classes(), in.terms(), in.stems(), in.exclusions(), in.pregnancyRisk(), in.controlled(),
                in.labels(), List.of(new SafetyDictionaryBundle.ValidityRule("*", 10, 10, 0, false), new SafetyDictionaryBundle.ValidityRule("controlled", 7, 7, 0, true)));
        ResponseEntity<Map> up = exchange("/internal/v1/safety-dictionaries/IN/versions", HttpMethod.POST, authedJsonBody(op, v1), Map.class);
        Object id = up.getBody().get("versionId");
        exchange("/internal/v1/safety-dictionaries/IN/versions/" + id + "/approve", HttpMethod.POST, authedJsonBody(op,
                Map.of("csoApprovalRef", "CSO-Q24", "approvedBy", "QA CSO")), Map.class);
        assertThat(exchange("/internal/v1/safety-dictionaries/IN/versions/" + id + "/activate", HttpMethod.POST, authedJsonBody(op,
                Map.of("csoApprovalRef", "CSO-Q24", "reason", "QA")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> s = sign(c, draft);
        Map<String, Object> old = exchange("/v1/clinical/prescriptions/" + signedBefore, HttpMethod.GET, authed(c.token()), Map.class).getBody();
        System.out.println("C24-A05 draft(20d, old max 30) signed after new max 10 = " + s.getStatusCode() + " "
                + (s.getStatusCode().is2xxSuccessful() ? validity(s.getBody(), 0) : slug(s)) + " | signed before=" + validity(old, 0).get("days"));
        if (s.getStatusCode().is2xxSuccessful()) {
            assertThat(((Number) validity(s.getBody(), 0).get("days")).intValue()).isLessThanOrEqualTo(10);
        } else {
            assertThat(slug(s)).isEqualTo("VALIDITY_ABOVE_CLASS_MAX");
        }
        assertThat(validity(old, 0)).containsEntry("days", 30);
    }

    // C24-A06: a Gulf clinic has no rules: no refills, no per-line validity
    @Test
    void c24a06_saudiClinicHasNoRules() {
        Clinic c = ksa();
        Map<String, Object> rules = exchange("/v1/clinical/rx-validity-rules", HttpMethod.GET, authed(c.token()), Map.class).getBody();
        String qe = visit(c);
        ResponseEntity<Map> refill = save(c, qe, line("Paracetamol 500 mg", "refills", 1));
        ResponseEntity<Map> days = save(c, qe, line("Amoxicillin 500 mg", "validityDays", 5));
        System.out.println("C24-A06 SA rules=" + rules.get("rules") + " refills1=" + refill.getStatusCode() + ":" + slug(refill)
                + " validityDays5=" + days.getStatusCode() + (days.getStatusCode().is2xxSuccessful() ? " validity=" + validity(days.getBody(), 0) : ""));
        assertThat((List<?>) rules.get("rules")).isEmpty();
        assertThat(slug(refill)).isEqualTo("REFILLS_NOT_ALLOWED");
    }

    // C24-A07: the rules read needs clinical:view
    @Test
    void c24a07_receptionIs403() {
        Clinic c = clinic();
        UUID desk = seedRole(c.tenant().id(), "QDesk", false, fullGrant("queue"), fullGrant("patients"));
        String token = loginAndGetAccessToken(seedStaff(c.tenant(), desk, "r24" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        int s = exchange("/v1/clinical/rx-validity-rules", HttpMethod.GET, authed(token), Map.class).getStatusCode().value();
        System.out.println("C24-A07 reception rules=" + s);
        assertThat(s).isEqualTo(403);
    }

    // C24-A08: boundaries
    @Test
    void c24a08_boundaries() {
        Clinic c = clinic();
        String qe = visit(c);
        Map<String, Integer> got = new java.util.LinkedHashMap<>();
        got.put("validityDays=0", save(c, qe, line("Paracetamol 500 mg", "validityDays", 0)).getStatusCode().value());
        got.put("validityDays=366", save(c, qe, line("Paracetamol 500 mg", "validityDays", 366)).getStatusCode().value());
        got.put("refills=6", save(c, qe, line("Metformin 500 mg", "drugId", "in-q24-met", "refills", 6)).getStatusCode().value());
        got.put("refills=-1", save(c, qe, line("Paracetamol 500 mg", "refills", -1)).getStatusCode().value());
        got.put("star validityDays=31", save(c, qe, line("Paracetamol 500 mg", "validityDays", 31)).getStatusCode().value());
        got.put("star validityDays=30", save(c, qe, line("Paracetamol 500 mg", "validityDays", 30)).getStatusCode().value());
        System.out.println("C24-A08 " + got);
        assertThat(got).containsEntry("validityDays=0", 400).containsEntry("validityDays=366", 400).containsEntry("refills=6", 400)
                .containsEntry("refills=-1", 400).containsEntry("star validityDays=31", 422).containsEntry("star validityDays=30", 200);
    }
}

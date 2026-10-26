package com.nabd.hms.clinical;

import com.nabd.hms.clinical.dto.SafetyDictionaryBundle;
import com.nabd.hms.clinical.dto.SafetyDictionaryBundle.Item;
import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ClinicianFixture;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S11 CLN-004): versioned, CSO-approved regional safety dictionary; operator-only content endpoints.
 * Drug words are the story's own acceptance examples, used as test input only. Activation is global per region, so every
 * test that activates restores v0 in finally. */
@SuppressWarnings({"unchecked", "rawtypes"})
class S11QaSafetyDictionaryTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 300_000) + 300_000);
    private static final String DICT = "/v1/clinical/safety-dictionary";

    @org.springframework.beans.factory.annotation.Autowired SafetyDictionaryService dictionaryService;

    private Runnable restoreDictionaries;

    @org.junit.jupiter.api.BeforeEach
    void snapshotDictionaries() {
        restoreDictionaries = SafetyDictionaryFixtures.snapshotState(superuserDataSource(), dictionaryService);
    }

    @org.junit.jupiter.api.AfterEach
    void restoreDictionaries() {
        restoreDictionaries.run();
    }
    private static final List<String> NON_CONTENT_ROLES = List.of("implementation", "support_engineer", "billing", "sre", "commercial", "compliance_dpo");

    private static String phone() {
        return "+9193" + String.format("%08d", SEQ.incrementAndGet());
    }

    private String operator(String role) {
        return platformLoginAndGetAccessToken(seedOperator("qa1-" + role + "-" + UUID.randomUUID() + "@nabd.internal", role, false));
    }

    private static String versions(String region) {
        return "/internal/v1/safety-dictionaries/" + region + "/versions";
    }

    private ResponseEntity<Map> post(String tok, String path, Object body) {
        return exchange(path, HttpMethod.POST, authedJsonBody(tok, body), Map.class);
    }

    private ResponseEntity<Map> approve(String op, String region, Object id, String ref) {
        return post(op, versions(region) + "/" + id + "/approve", Map.of("csoApprovalRef", ref, "approvedBy", "QA CSO"));
    }

    private ResponseEntity<Map> activate(String op, String region, Object id, String ref) {
        return post(op, versions(region) + "/" + id + "/activate", Map.of("csoApprovalRef", ref, "reason", "qa"));
    }

    private void restoreV0(String op, String region) {
        List<Map> list = exchange(versions(region), HttpMethod.GET, authed(op), List.class).getBody();
        Map v0 = list.stream().filter(v -> Integer.valueOf(0).equals(v.get("version"))).findFirst().orElseThrow();
        if ("active".equals(v0.get("status"))) return;
        String ref = (String) v0.get("csoApprovalRef");
        if (ref == null) {
            ref = "QA-RESTORE";
            approve(op, region, v0.get("versionId"), ref);
        }
        assertThat(activate(op, region, v0.get("versionId"), ref).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static <T> List<T> plus(List<T> l, T extra) {
        List<T> out = new ArrayList<>(l);
        out.add(extra);
        return out;
    }

    private SeededTenant ksaTenant() {
        SeededTenant t = seedTenant();
        try (java.sql.Connection c = superuserConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("SET session_replication_role = replica");
            st.execute("UPDATE tenants SET region_code = 'SA' WHERE id = '" + t.id() + "'");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return t;
    }

    private record Visit(SeededTenant t, String tok, String patientId, String entry) {
    }

    private Visit visit(SeededTenant t, String gender, LocalDate dob) {
        UUID role = seedRole(t.id(), "QaDoc" + SEQ.incrementAndGet(), false, fullGrant("clinical"), fullGrant("queue"), fullGrant("patients"));
        SeededStaff d = ClinicianFixture.licensed(seedStaff(t, role, "qa1d" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        String tok = loginAndGetAccessToken(d);
        String p = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(tok, Map.of("name", "Qa Dict " +
                Integer.toString(SEQ.incrementAndGet(), 36), "phone", phone(), "dob", dob.toString(), "gender", gender)), Map.class).getBody().get("id");
        String entry = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(tok, Map.of("patientId", p,
                "doctorId", d.id().toString())), Map.class).getBody().get("id");
        return new Visit(t, tok, p, entry);
    }

    private List<Map<?, ?>> warnings(Visit v, String... drugs) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String d : drugs) items.add(Map.of("drugName", d, "dosage", "1 tab"));
        ResponseEntity<Map> r = exchange("/v1/clinical/prescriptions/" + v.entry(), HttpMethod.PATCH, authedJsonBody(v.tok(), Map.of("items", items)), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        List<Map<?, ?>> out = new ArrayList<>();
        for (Object it : (List<?>) r.getBody().get("items")) out.addAll((List<Map<?, ?>>) ((Map<?, ?>) it).get("warnings"));
        return out;
    }

    private static List<Object> kinds(List<Map<?, ?>> ws) {
        return ws.stream().map(w -> (Object) w.get("kind")).toList();
    }

    private void allergy(Visit v, String substance) {
        assertThat(exchange("/v1/clinical/patients/" + v.patientId() + "/allergies", HttpMethod.POST, authedJsonBody(v.tok(),
                Map.of("substance", substance, "severity", "severe", "reaction", "anaphylaxis")), Map.class).getStatusCode().is2xxSuccessful()).isTrue();
    }

    private static JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    // ── GET /v1/clinical/safety-dictionary ───────────────────────────────

    @Test
    void activeDictionaryIsShownToClinicalViewersOnlyAndIsRegionScoped() {
        Visit in = visit(seedTenant(), "male", LocalDate.of(1980, 1, 1));
        ResponseEntity<Map> r = exchange(DICT, HttpMethod.GET, authed(in.tok()), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsKeys("region", "version", "approvedBy", "approvedAt", "source").containsEntry("region", "IN");

        UUID noClinical = seedRole(in.t().id(), "QaQueueOnly", false, fullGrant("queue"));
        String q = loginAndGetAccessToken(seedStaff(in.t(), noClinical, "qa1q" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        assertThat(exchange(DICT, HttpMethod.GET, authed(q), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(DICT, HttpMethod.GET, HttpEntity.EMPTY, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Visit ksa = visit(ksaTenant(), "male", LocalDate.of(1980, 1, 1));
        assertThat(exchange(DICT, HttpMethod.GET, authed(ksa.tok()), Map.class).getBody()).containsEntry("region", "SA");
    }

    // ── AC1 (mechanism) / AC1 (shipped content) ──────────────────────────

    @Test
    void anActivatedApprovedVersionWithTheBrandWarnsAWomanOf28AndNotAMan() {
        String op = operator("super_admin");
        try {
            SafetyDictionaryBundle in = SafetyDictionaryFixtures.bundle("IN");
            SafetyDictionaryBundle v1 = new SafetyDictionaryBundle(in.classes(), in.terms(), in.stems(), in.exclusions(),
                    plus(in.pregnancyRisk(), new Item(null, "isotroin", null, "term")), in.controlled(), in.labels());
            ResponseEntity<Map> up = post(op, versions("IN"), v1);
            assertThat(up.getStatusCode()).as("%s", up.getBody()).isEqualTo(HttpStatus.CREATED);
            Object id = up.getBody().get("versionId");
            assertThat(approve(op, "IN", id, "QA-CSO-1").getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(activate(op, "IN", id, "QA-CSO-1").getStatusCode()).isEqualTo(HttpStatus.OK);

            Visit woman = visit(seedTenant(), "female", LocalDate.now().minusYears(28));
            assertThat(kinds(warnings(woman, "Isotroin 20mg"))).contains("pregnancy");
            Visit man = visit(seedTenant(), "male", LocalDate.now().minusYears(28));
            assertThat(kinds(warnings(man, "Isotroin 20mg"))).doesNotContain("pregnancy");
        } finally {
            restoreV0(op, "IN");
        }
    }

    /** The shipped IN dictionary (v0, the old code lists) does not know the brand: QA-16 is still open in production. */
    @Test
    @Tag("known-defect-CLN-004")
    void shippedIndiaDictionaryWarnsForIsotroin() {
        Visit woman = visit(seedTenant(), "female", LocalDate.now().minusYears(28));
        assertThat(kinds(warnings(woman, "Isotroin 20mg"))).contains("pregnancy");
    }

    // ── AC2 ──────────────────────────────────────────────────────────────

    @Test
    void penicillinAllergyWarnsSeverelyForNovamoxAndCrossReactsToACephalosporinBrand() {
        Visit v = visit(seedTenant(), "male", LocalDate.of(1985, 6, 1));
        allergy(v, "Penicillin");
        List<Map<?, ?>> novamox = warnings(v, "Novamox 500");
        Map<?, ?> w = novamox.stream().filter(x -> "allergy".equals(x.get("kind"))).findFirst().orElseThrow();
        assertThat(w.get("severity")).isEqualTo("severe");
        assertThat(w.get("requiresDecision")).isEqualTo(true);
        assertThat(kinds(warnings(v, "Keflex 500"))).contains("allergy");
        assertThat(kinds(warnings(v, "Paracetamol 500"))).doesNotContain("allergy"); // negative
    }

    @Test
    void cephalosporinAllergyDoesNotWarnForAPenicillinBrand() { // cross-reactivity is one direction only
        Visit v = visit(seedTenant(), "male", LocalDate.of(1985, 6, 1));
        allergy(v, "Cephalosporin");
        assertThat(kinds(warnings(v, "Novamox 500"))).doesNotContain("allergy");
        assertThat(kinds(warnings(v, "Keflex 500"))).contains("allergy");
    }

    @Test
    @Tag("known-defect-CLN-004")
    void penicillinAllergyWarnsSeverelyForMox500() {
        Visit v = visit(seedTenant(), "male", LocalDate.of(1985, 6, 1));
        allergy(v, "Penicillin");
        Map<?, ?> w = warnings(v, "Mox 500").stream().filter(x -> "allergy".equals(x.get("kind"))).findFirst().orElse(null);
        assertThat(w).as("no allergy warning for 'Mox 500'").isNotNull();
        assertThat(w.get("requiresDecision")).isEqualTo(true);
    }

    // ── AC4 + bundle validation ──────────────────────────────────────────

    @Test
    void uploadedVersionNeedsARecordedCsoApprovalBeforeActivation() {
        String op = operator("super_admin");
        try {
            SafetyDictionaryBundle in = SafetyDictionaryFixtures.bundle("IN");
            SafetyDictionaryBundle draft = new SafetyDictionaryBundle(in.classes(), in.terms(), in.stems(), in.exclusions(),
                    plus(in.pregnancyRisk(), new Item(null, "qadraftterm" + SEQ.incrementAndGet(), null, "term")), in.controlled(), in.labels());
            ResponseEntity<Map> up = post(op, versions("IN"), draft);
            assertThat(up.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(up.getBody()).containsEntry("status", "draft").containsKeys("versionId", "checksum", "diffSummary");
            Object id = up.getBody().get("versionId");

            ResponseEntity<Map> r = activate(op, "IN", id, "QA-ANY");
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(String.valueOf(r.getBody().get("type"))).endsWith("NOT_APPROVED");
            assertThat(exchange(DICT, HttpMethod.GET, authed(visit(seedTenant(), "male", LocalDate.of(1990, 1, 1)).tok()), Map.class)
                    .getBody()).containsEntry("version", 0);

            assertThat(approve(op, "IN", id, "QA-CSO-2").getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(String.valueOf(activate(op, "IN", id, "QA-WRONG").getBody().get("type"))).endsWith("NOT_APPROVED");
            assertThat(activate(op, "IN", id, "QA-CSO-2").getStatusCode()).isEqualTo(HttpStatus.OK);
            ResponseEntity<Map> again = activate(op, "IN", id, "QA-CSO-2");
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(String.valueOf(again.getBody().get("type"))).endsWith("ALREADY_ACTIVE");
        } finally {
            restoreV0(op, "IN");
        }
    }

    @Test
    void bundleTermsShorterThanFourCharactersAreRefusedExceptTheCefStem() {
        String op = operator("super_admin");
        SafetyDictionaryBundle in = SafetyDictionaryFixtures.bundle("IN");
        ResponseEntity<Map> bad = post(op, versions("IN"), new SafetyDictionaryBundle(in.classes(),
                plus(in.terms(), new Item("penicillin", "abc", null, null)), in.stems(), in.exclusions(), in.pregnancyRisk(), in.controlled(), in.labels()));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(String.valueOf(bad.getBody().get("type"))).endsWith("BUNDLE_INVALID");
        // boundary: a 4-character term is accepted as a draft (not activated)
        ResponseEntity<Map> four = post(op, versions("IN"), new SafetyDictionaryBundle(in.classes(),
                plus(in.terms(), new Item("penicillin", "qzxw", null, null)), in.stems(), in.exclusions(), in.pregnancyRisk(), in.controlled(), in.labels()));
        assertThat(four.getStatusCode()).as("%s", four.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(four.getBody()).containsEntry("status", "draft");
        // the shipped bundle (which carries the 'cef' stem) validates
        assertThat(post(op, versions("IN"), in).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ── AC5 + AC6 ────────────────────────────────────────────────────────

    @Test
    void signedPrescriptionKeepsTheVersionActiveAtSigningAcrossRollbackWhichIsAudited() {
        String op = operator("super_admin");
        try {
            Visit before = visit(seedTenant(), "male", LocalDate.of(1990, 1, 1));
            warnings(before, "Paracetamol");
            ResponseEntity<Map> s0 = post(before.tok(), "/v1/clinical/prescriptions/" + before.entry() + "/sign", Map.of());
            assertThat(s0.getStatusCode()).as("%s", s0.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(s0.getBody()).containsEntry("safetyDictionaryVersion", "IN v0");

            SafetyDictionaryBundle in = SafetyDictionaryFixtures.bundle("IN");
            ResponseEntity<Map> up = post(op, versions("IN"), new SafetyDictionaryBundle(in.classes(), in.terms(), in.stems(), in.exclusions(),
                    plus(in.pregnancyRisk(), new Item(null, "qarollback" + SEQ.incrementAndGet(), null, "term")), in.controlled(), in.labels()));
            Object id = up.getBody().get("versionId");
            int version = (Integer) up.getBody().get("version");
            approve(op, "IN", id, "QA-CSO-3");
            assertThat(activate(op, "IN", id, "QA-CSO-3").getStatusCode()).isEqualTo(HttpStatus.OK);

            Visit during = visit(seedTenant(), "male", LocalDate.of(1990, 1, 1));
            warnings(during, "Paracetamol");
            assertThat(post(during.tok(), "/v1/clinical/prescriptions/" + during.entry() + "/sign", Map.of()).getBody())
                    .containsEntry("safetyDictionaryVersion", "IN v" + version);

            restoreV0(op, "IN"); // rollback
            assertThat(exchange(DICT, HttpMethod.GET, authed(during.tok()), Map.class).getBody()).containsEntry("version", 0);
            assertThat(su().queryForObject("SELECT safety_dictionary_version FROM prescriptions WHERE queue_entry_id = ?::uuid", String.class,
                    during.entry())).isEqualTo("IN v" + version);
            assertThat(su().queryForObject("SELECT safety_dictionary_version FROM prescriptions WHERE queue_entry_id = ?::uuid", String.class,
                    before.entry())).isEqualTo("IN v0");
            assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id IS NULL AND action = 'safety_dictionary.activated' " +
                    "AND after->>'region' = 'IN' AND (after->>'activeVersion')::int = 0 AND (after->>'previousVersion')::int = ?", Integer.class, version))
                    .isEqualTo(1);
            // every pod polls the active version; the poll must be well inside the 5-minute budget
            String delay = SafetyDictionaryService.class.getMethod("refresh").getAnnotation(Scheduled.class).fixedDelayString();
            long ms = Long.parseLong(delay.replaceAll(".*:(\\d+)}$", "$1"));
            assertThat(ms).isLessThanOrEqualTo(300_000);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        } finally {
            restoreV0(op, "IN");
        }
    }

    // ── AC7 ──────────────────────────────────────────────────────────────

    @Test
    void ksaVersionWithTheBrandFiresTheSfdaControlledWarningWithArabicLabel() {
        String op = operator("super_admin");
        try {
            SafetyDictionaryBundle sa = SafetyDictionaryFixtures.bundle("SA");
            ResponseEntity<Map> up = post(op, versions("SA"), new SafetyDictionaryBundle(sa.classes(), sa.terms(), sa.stems(), sa.exclusions(),
                    sa.pregnancyRisk(), plus(sa.controlled(), new Item(null, "tramal", null, "term")),
                    Map.of("controlled", Map.of("en", "SFDA-controlled substance", "ar", "[qa ar label]"))));
            Object id = up.getBody().get("versionId");
            approve(op, "SA", id, "QA-CSO-SA");
            assertThat(activate(op, "SA", id, "QA-CSO-SA").getStatusCode()).isEqualTo(HttpStatus.OK);
            Visit v = visit(ksaTenant(), "male", LocalDate.of(1975, 1, 1));
            Map<?, ?> w = warnings(v, "Tramal 50mg").stream().filter(x -> "controlled".equals(x.get("kind"))).findFirst().orElseThrow();
            assertThat(String.valueOf(w.get("message"))).contains("SFDA");
            assertThat((Map<String, Object>) exchange(DICT, HttpMethod.GET, authed(v.tok()), Map.class).getBody().get("controlledLabel"))
                    .containsEntry("ar", "[qa ar label]");
            // an IN tenant is not affected by the SA version
            assertThat(kinds(warnings(visit(seedTenant(), "male", LocalDate.of(1975, 1, 1)), "Tramal 50mg"))).doesNotContain("controlled");
        } finally {
            restoreV0(op, "SA");
        }
    }

    @Test
    @Tag("known-defect-CLN-004")
    void shippedSaudiDictionaryWarnsForTramalWithAnArabicLabel() {
        Visit v = visit(ksaTenant(), "male", LocalDate.of(1975, 1, 1));
        assertThat(kinds(warnings(v, "Tramal 50mg"))).contains("controlled");
        assertThat((Map<String, Object>) exchange(DICT, HttpMethod.GET, authed(v.tok()), Map.class).getBody().get("controlledLabel")).containsKey("ar");
    }

    // ── internal endpoints: who may call them ────────────────────────────

    @Test
    void onlyTheSafetyContentOperatorReachesTheInternalDictionaryEndpoints() {
        String admin = operator("super_admin");
        List<Map> list = exchange(versions("IN"), HttpMethod.GET, authed(admin), List.class).getBody();
        Object v0 = list.stream().filter(v -> Integer.valueOf(0).equals(v.get("version"))).findFirst().orElseThrow().get("versionId");
        SafetyDictionaryBundle in = SafetyDictionaryFixtures.bundle("IN");
        for (String role : NON_CONTENT_ROLES) {
            String op = operator(role);
            ResponseEntity<Map> g = exchange(versions("IN"), HttpMethod.GET, authed(op), Map.class);
            assertThat(g.getStatusCode()).as(role + " list").isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(g.getHeaders().getContentType().toString()).contains("problem+json");
            assertThat(post(op, versions("IN"), in).getStatusCode()).as(role + " upload").isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(approve(op, "IN", v0, "X").getStatusCode()).as(role + " approve").isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(activate(op, "IN", v0, "X").getStatusCode()).as(role + " activate").isEqualTo(HttpStatus.FORBIDDEN);
        }
        // a clinic token (even the Owner's full grants) never opens /internal/v1/**
        SeededTenant t = seedTenant();
        String owner = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "qa1o" + SEQ.incrementAndGet() + "@q.com", phone(), false));
        assertThat(exchange(versions("IN"), HttpMethod.GET, authed(owner), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post(owner, versions("IN"), in).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(versions("IN"), HttpMethod.GET, HttpEntity.EMPTY, Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** (b) Operator tokens are accepted on /internal/v1/**: they must open nothing else there and nothing on /v1/** outside
     * /v1/platform/, including through path tricks that start with /internal/v1/. */
    @Test
    void operatorTokenOpensNoOtherSurface() throws Exception {
        String admin = operator("super_admin");
        SeededTenant t = seedTenant();
        // staff-side endpoints stay closed to every operator, the most privileged included
        for (String path : List.of("/v1/patients", DICT, "/v1/staff", "/v1/billing/otc-charges", "/v1/clinical/prescriptions/" + UUID.randomUUID())) {
            assertThat(exchange(path, HttpMethod.GET, authed(admin), Map.class).getStatusCode()).as(path).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // unmapped internal paths: 404/403, never 200 or 500
        for (String path : List.of("/internal/v1/", "/internal/v1/tenants", "/internal/v1/safety-dictionaries", "/internal/v1/patients")) {
            int s = exchange(path, HttpMethod.GET, authed(admin), String.class).getStatusCode().value();
            assertThat(s).as(path).isIn(403, 404, 405);
        }
        // raw (non-normalised) URIs that start with /internal/v1/ but point elsewhere must not reach a staff endpoint
        HttpClient client = HttpClient.newHttpClient();
        for (String raw : List.of("/internal/v1/../../v1/patients", "/internal/v1/%2e%2e/%2e%2e/v1/patients", "/internal/v1/..%2f..%2fv1/patients",
                "/internal/v1/;/../../v1/patients")) {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + raw))
                    .header("Authorization", "Bearer " + admin).header("X-Tenant-Id", t.id().toString()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(raw + " -> " + r.body()).isIn(400, 401, 403, 404);
        }
        // an operator token whose role lacks the authority still cannot use a non-safety platform action through /internal
        String sre = operator("sre");
        assertThat(exchange("/internal/v1/safety-dictionaries/SA/versions", HttpMethod.GET, authed(sre), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // and operator tokens still work where they always did
        assertThat(exchange("/v1/platform/tenants", HttpMethod.GET, authed(admin), String.class).getStatusCode().is2xxSuccessful()).isTrue();
    }
}

package com.nabd.hms.messaging;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-014: PHI lint for WhatsApp templates (forbidden-field catalogue, typed slots, create/edit wiring, audited override). */
class TemplateLintApiTest extends ApiTestBase {

    private static final String LIST = "/v1/setup/whatsapp-templates";

    @Autowired
    private TemplateLint lint;

    private record Case(String lang, String body, String phrase) {
    }

    private static List<Case> forbiddenCases() throws Exception {
        List<Case> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                TemplateLintApiTest.class.getResourceAsStream("/template-lint/forbidden-cases.tsv"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    String[] p = line.split("\t");
                    out.add(new Case(p[0], p[1], p[2]));
                }
            }
        }
        return out;
    }

    @Test
    void forbiddenFieldCatalogueIsBlockedInFiveLanguages() throws Exception {
        List<Case> cases = forbiddenCases();
        assertThat(cases.size()).isGreaterThanOrEqualTo(60);
        assertThat(cases.stream().map(Case::lang).collect(Collectors.toSet())).containsExactlyInAnyOrder("en", "hi", "kn", "te", "ar");
        for (Case c : cases) {
            assertThat(lint.lint(c.body(), null)).as("%s: %s", c.lang(), c.body())
                    .anyMatch(f -> f.phrase().toLowerCase().contains(c.phrase().toLowerCase()));
        }
    }

    @Test
    void drugMasterTermsAreReportedAsDrugNames() {
        assertThat(lint.lint("Take penicillin twice a day", null)).anyMatch(f -> "drug_master".equals(f.rule()) && f.phrase().equals("penicillin"));
    }

    @Test
    void wordBoundariesKeepOrdinaryWordsClear() {
        // "still" holds "sti", "scanned" would hold "scan" without whole-word matching; none of these are medical
        assertThat(lint.lint("Hello {{1}}, we are still open. See you at {{2}} today.", null)).isEmpty();
        assertThat(lint.lint("Hello {{1}}, your visit is at {{2}}.", List.of(new TemplateLint.Slot(1, "patient_first_name"), new TemplateLint.Slot(2, "time")))).isEmpty();
    }

    @Test
    void hbA1cResultIsRefusedWith422TemplatePhiNamingThePhraseAndNothingIsStored() {
        SeededTenant tenant = seedTenant();
        String token = ownerToken(tenant);
        ResponseEntity<Map> resp = exchange(LIST, HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "lab_note", "language", "en", "category", "UTILITY",
                "body", "your HbA1c result {{2}}", "examples", List.of("7.2"))), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(resp.getBody().get("code")).isEqualTo("TEMPLATE_PHI");
        assertThat((String) resp.getBody().get("detail")).contains("HbA1c result");
        List<Map<String, Object>> findings = (List<Map<String, Object>>) resp.getBody().get("findings");
        assertThat(findings).anyMatch(f -> "HbA1c result".equals(f.get("phrase")) && "deny_word".equals(f.get("rule")));
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM whatsapp_templates", Integer.class))).isZero();
    }

    @Test
    void onlyAllowListedSlotTypesAreAccepted() {
        SeededTenant tenant = seedTenant();
        String token = ownerToken(tenant);
        for (String bad : List.of("diagnosis", "report_value", "drug", "banana")) {
            ResponseEntity<Map> resp = exchange(LIST, HttpMethod.POST, authedJsonBody(token, Map.of(
                    "name", "slot_" + bad, "language", "en", "category", "UTILITY", "body", "Hello {{1}}, see you soon.",
                    "examples", List.of("Asha"), "slots", List.of(Map.of("n", 1, "type", bad)))), Map.class);
            assertThat(resp.getStatusCode()).as(bad).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(resp.getBody().get("code")).isEqualTo("TEMPLATE_PHI");
            List<Map<String, Object>> findings = (List<Map<String, Object>>) resp.getBody().get("findings");
            assertThat(findings.get(0).get("phrase")).isEqualTo(bad);
            assertThat(findings.get(0).get("rule")).isEqualTo("banana".equals(bad) ? "allow_slot" : "deny_slot");
        }
        ResponseEntity<Map> untyped = exchange(LIST, HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "untyped", "language", "en", "category", "UTILITY", "body", "Hello {{1}}, at {{2}} today.",
                "examples", List.of("Asha", "10:00"), "slots", List.of(Map.of("n", 1, "type", "patient_first_name")))), Map.class);
        assertThat(untyped.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<Map> ok = exchange(LIST, HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "typed", "language", "en", "category", "UTILITY", "body", "Hello {{1}}, at {{2}} today.",
                "examples", List.of("Asha", "10:00"),
                "slots", List.of(Map.of("n", 1, "type", "patient_first_name"), Map.of("n", 2, "type", "time")))), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void editingARejectedTemplateIsLintedToo() {
        SeededTenant tenant = seedTenant();
        String token = ownerToken(tenant);
        Map<?, ?> created = exchange(LIST, HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "visit_note", "language", "en", "category", "UTILITY",
                "body", "Hello {{1}}, see you at {{2}}.", "examples", List.of("Asha", "10:00"))), Map.class).getBody();
        String id = (String) created.get("id");
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE whatsapp_templates SET status = 'rejected' WHERE id = ?::uuid", id));
        ResponseEntity<Map> resp = exchange(LIST + "/" + id, HttpMethod.PATCH, authedJsonBody(token, Map.of(
                "body", "Hello {{1}}, your diagnosis for {{2}} is ready.", "examples", List.of("Asha", "10:00"))), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(resp.getBody().get("code")).isEqualTo("TEMPLATE_PHI");
    }

    @Test
    void dryRunReturnsFindingsAndNeedsSetupEdit() {
        SeededTenant tenant = seedTenant();
        String token = ownerToken(tenant);
        ResponseEntity<Map> bad = exchange(LIST + ":lint", HttpMethod.POST, authedJsonBody(token, Map.of(
                "body", "Your blood test result {{1}} is ready", "language", "en",
                "slots", List.of(Map.of("n", 1, "type", "result")))), Map.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bad.getBody().get("ok")).isEqualTo(false);
        List<Map<String, Object>> findings = (List<Map<String, Object>>) bad.getBody().get("findings");
        assertThat(findings).anyMatch(f -> "blood test result".equals(f.get("phrase")));
        assertThat(findings).anyMatch(f -> "deny_slot".equals(f.get("rule")));

        ResponseEntity<Map> good = exchange(LIST + ":lint", HttpMethod.POST, authedJsonBody(token, Map.of(
                "body", "Hello {{1}}, see you at {{2}}.", "language", "en",
                "slots", List.of(Map.of("n", 1, "type", "patient_first_name"), Map.of("n", 2, "type", "time")))), Map.class);
        assertThat(good.getBody().get("ok")).isEqualTo(true);
        assertThat((List<?>) good.getBody().get("findings")).isEmpty();

        UUID roleId = seedRole(tenant.id(), "Front desk", false, fullGrant("queue"));
        String desk = loginAndGetAccessToken(seedStaff(tenant, roleId, "d" + UUID.randomUUID().toString().substring(0, 8) + "@a.com",
                "+9198" + (10000000 + (int) (Math.random() * 89999999)), false));
        assertThat(exchange(LIST + ":lint", HttpMethod.POST, authedJsonBody(desk, Map.of("body", "x")), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void platformOverrideCreatesTheTemplateAndWritesReasonAndOperatorToTheAuditLog() {
        SeededTenant tenant = seedTenant();
        SeededOperator superAdmin = seedOperator("lint-super-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        String op = platformLoginAndGetAccessToken(superAdmin);
        Map<String, Object> req = Map.of("name", "result_word", "language", "en", "category", "UTILITY",
                "body", "Hello {{1}}, we have the results of the survey for {{2}} today.", "examples", List.of("Asha", "Sunday"),
                "reason", "Survey wording, not a clinical result; reviewed by platform compliance");

        String reasonText = "Survey wording, not a clinical result; reviewed by platform compliance";
        ResponseEntity<Map> created = exchange("/v1/platform/tenants/" + tenant.id() + "/whatsapp-templates", HttpMethod.POST,
                authedJsonBody(op, req), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("status")).isEqualTo("approved");

        List<Map<String, Object>> audit = inTenantTx(tenant.id(), () -> jdbc.queryForList(
                "SELECT actor_type, actor_id, after::text AS after FROM audit_log WHERE action = 'whatsapp_template.phi_lint_overridden'"));
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("actor_type")).isEqualTo("operator");
        assertThat(audit.get(0).get("actor_id")).isEqualTo(superAdmin.id());
        assertThat((String) audit.get(0).get("after")).contains(reasonText).contains("results");

        // the same body through the clinic's own endpoint is still refused, and other operators and clinics cannot override
        String owner = ownerToken(tenant);
        assertThat(exchange(LIST, HttpMethod.POST, authedJsonBody(owner, Map.of("name", "again", "language", "en", "category", "UTILITY",
                "body", req.get("body"), "examples", req.get("examples"))), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        String billing = platformLoginAndGetAccessToken(seedOperator("lint-bill-" + UUID.randomUUID() + "@nabd.health", "billing", false));
        assertThat(exchange("/v1/platform/tenants/" + tenant.id() + "/whatsapp-templates", HttpMethod.POST, authedJsonBody(billing, req), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/platform/tenants/" + tenant.id() + "/whatsapp-templates", HttpMethod.POST, authedJsonBody(owner, req), Map.class)
                .getStatusCode().is2xxSuccessful()).isFalse();
        Map<String, Object> noReason = new java.util.HashMap<>(req);
        noReason.put("name", "no_reason");
        noReason.put("reason", "short");
        assertThat(exchange("/v1/platform/tenants/" + tenant.id() + "/whatsapp-templates", HttpMethod.POST, authedJsonBody(op, noReason), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private String ownerToken(SeededTenant tenant) {
        UUID roleId = seedFullAccessRole(tenant.id());
        return loginAndGetAccessToken(seedStaff(tenant, roleId, "o" + UUID.randomUUID().toString().substring(0, 8) + "@a.com",
                "+9198" + (10000000 + (int) (Math.random() * 89999999)), false));
    }
}

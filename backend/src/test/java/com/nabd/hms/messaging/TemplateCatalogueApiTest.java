package com.nabd.hms.messaging;

import com.nabd.hms.common.CatalogueTemplates;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-012: starter template catalogue (16 keys x EN/HI/KN/TE, plus ACC-024 queue_paused (EN only)), instantiate endpoint, background submission, saga step. */
class TemplateCatalogueApiTest extends ApiTestBase {

    private static final String LIST = "/v1/setup/whatsapp-templates";

    @Autowired
    private TemplateLint lint;
    @Autowired
    private CatalogueSubmissionJob job;
    @Autowired
    private CatalogueTemplates catalogueTemplates;

    @Test
    void everyCatalogueTemplatePassesPhiLintMetaRulesAndRendersWithNoVariableLeft() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT key, language, category, body, example_values, slot_types FROM template_catalogue ORDER BY key, language");
        assertThat(rows).hasSize(65);
        assertThat(rows.stream().map(r -> r.get("key")).distinct()).hasSize(17);
        assertThat(rows.stream().map(r -> r.get("language")).distinct()).containsExactlyInAnyOrder("en", "hi", "kn", "te");
        for (Map<String, Object> r : rows) {
            String body = (String) r.get("body");
            List<String> examples = strings(r.get("example_values"));
            List<String> types = strings(r.get("slot_types"));
            String id = r.get("key") + "/" + r.get("language");
            List<TemplateLint.Slot> slots = new java.util.ArrayList<>();
            for (int i = 0; i < types.size(); i++) {
                slots.add(new TemplateLint.Slot(i + 1, types.get(i)));
            }
            assertThat(lint.lint(body, slots)).as(id).isEmpty();                        // COM-014: no diagnosis, drug or result text
            assertThat(TemplateRules.checkBody(body, examples)).as(id).isEqualTo(types.size()); // Meta's syntax rules
            String rendered = body;
            for (int i = 0; i < examples.size(); i++) {
                rendered = rendered.replace("{{" + (i + 1) + "}}", examples.get(i));
            }
            assertThat(rendered).as(id).doesNotContain("{{").doesNotContain("}}");
            assertThat(body).as(id).doesNotContainPattern("[\\u200B-\\u200D\\uFEFF]");
            if ("hi".equals(r.get("language"))) {
                assertThat(Pattern.compile("[\\u0900-\\u097F]").matcher(rendered).find()).as(id).isTrue(); // Devanagari survives the database
            }
        }
        assertThat(rows.stream().filter(r -> "otp_login".equals(r.get("key"))).map(r -> r.get("category")).distinct()).containsExactly("AUTHENTICATION");
    }

    @Test
    void defaultLanguagesFollowTheMarket() {
        assertThat(TemplateCatalogueService.defaultLanguages("IN", null)).containsExactly("en", "hi");
        assertThat(TemplateCatalogueService.defaultLanguages("IN", "kn")).containsExactly("en", "hi", "kn");
        assertThat(TemplateCatalogueService.defaultLanguages("IN", "te")).containsExactly("en", "hi", "te");
        assertThat(TemplateCatalogueService.defaultLanguages("IN", "ta")).containsExactly("en", "hi");
        assertThat(TemplateCatalogueService.defaultLanguages("SA", "kn")).containsExactly("en");
        assertThat(TemplateCatalogueService.defaultLanguages("QA", null)).containsExactly("en");
    }

    @Test
    void instantiateQueuesTheCatalogueThenTheBackgroundJobSubmitsItAndOwnersSeeStatus() {
        SeededTenant tenant = seedTenant();
        String op = platformLoginAndGetAccessToken(seedOperator("cat-" + UUID.randomUUID() + "@nabd.health", "implementation", false));
        String path = "/internal/v1/tenants/" + tenant.id() + "/templates:instantiate";

        ResponseEntity<Map> resp = exchange(path, HttpMethod.POST, authedJsonBody(op, Map.of("languages", List.of("en", "hi"))), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody().get("submitted")).isEqualTo(33);

        String owner = ownerToken(tenant);
        List<Map<String, Object>> queued = exchange(LIST, HttpMethod.GET, authed(owner), List.class).getBody();
        assertThat(queued).hasSize(33).allMatch(t -> "catalogue".equals(t.get("source")) && "queued".equals(t.get("status")));
        assertThat(queued).allMatch(t -> ("Test Clinic " + tenant.slug()).equals(t.get("headerText"))); // NB-198: header = clinic name

        for (int i = 0; i < 30 && stillQueued(tenant); i++) {
            job.runOnce(); // paced: perRun templates per run, shared with whatever else is queued
        }
        List<Map<String, Object>> done = exchange(LIST, HttpMethod.GET, authed(owner), List.class).getBody();
        assertThat(done).hasSize(33).allMatch(t -> "approved".equals(t.get("status"))); // LoggingWhatsAppClient auto-approves
        assertThat(done.stream().map(t -> t.get("language")).distinct()).containsExactlyInAnyOrder("en", "hi");
        assertThat(done.stream().map(t -> t.get("key")).distinct()).hasSize(17);

        // the second call is refused; bad input and a clinic token are refused
        ResponseEntity<Map> again = exchange(path, HttpMethod.POST, authedJsonBody(op, Map.of("languages", List.of("en"))), Map.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code")).isEqualTo("ALREADY_INSTANTIATED");
        SeededTenant other = seedTenant();
        assertThat(exchange("/internal/v1/tenants/" + other.id() + "/templates:instantiate", HttpMethod.POST,
                authedJsonBody(op, Map.of("languages", List.of("fr"))), Map.class).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(exchange("/internal/v1/tenants/" + other.id() + "/templates:instantiate", HttpMethod.POST,
                authedJsonBody(owner, Map.of("languages", List.of("en"))), Map.class).getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    void aTenantNeverSeesAnotherTenantsCatalogueRows() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        catalogueTemplates.instantiateDefaults(a.id(), "SA");
        assertThat(exchange(LIST, HttpMethod.GET, authed(ownerToken(b)), List.class).getBody()).isEmpty();
        assertThat(exchange(LIST, HttpMethod.GET, authed(ownerToken(a)), List.class).getBody()).hasSize(17);
    }

    @Test
    void provisioningInstantiatesTheDefaultsForTheMarketAndARetryDoesNotDuplicate() {
        String token = platformLoginAndGetAccessToken(seedOperator("cat-prov-" + UUID.randomUUID() + "@nabd.health", "super_admin", false));
        String slug = "cat-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> created = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(token, Map.of(
                "tenantSlug", slug, "tenantName", "Test Clinic " + slug, "region", "IN", "ownerEmail", "owner-" + slug + "@nabd.health",
                "ownerName", "Test Owner", "ownerMobile", "+9198765" + String.format("%05d", Math.abs(slug.hashCode() % 100000)),
                "brandName", "Test Brand " + slug, "path", "self_serve")), Map.class);
        String jobId = (String) created.getBody().get("id");
        Map<String, Object> last = null;
        for (int i = 0; i < 6; i++) {
            last = exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance", HttpMethod.POST, authed(token), Map.class).getBody();
        }
        assertThat(last.get("status")).isEqualTo("done");
        UUID tenantId = UUID.fromString((String) last.get("createdTenantId"));
        assertThat(inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT count(*) FROM whatsapp_templates WHERE catalogue_key IS NOT NULL", Integer.class)))
                .isEqualTo(33); // India: EN + HI; queue_paused is EN only (the clinic's state language is not recorded yet)
        assertThat(catalogueTemplates.instantiateDefaults(tenantId, "IN")).isZero();
    }

    private static List<String> strings(Object sqlArray) {
        try {
            return List.of((String[]) ((java.sql.Array) sqlArray).getArray());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean stillQueued(SeededTenant t) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM whatsapp_templates WHERE status = 'queued'", Integer.class)) > 0;
    }

    private String ownerToken(SeededTenant tenant) {
        UUID roleId = seedFullAccessRole(tenant.id());
        return loginAndGetAccessToken(seedStaff(tenant, roleId, "o" + UUID.randomUUID().toString().substring(0, 8) + "@a.com",
                "+9198" + (10000000 + (int) (Math.random() * 89999999)), false));
    }
}

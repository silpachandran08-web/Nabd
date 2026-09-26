package com.nabd.hms.messaging;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** NB-190 (library, review verdicts, quality), NB-191 (slot-only sends), NB-198 (clinic name in header). */
class WhatsAppTemplateApiTest extends ApiTestBase {

    private static final String APP_SECRET = "test-app-secret";

    @DynamicPropertySource
    static void whatsappProps(DynamicPropertyRegistry registry) {
        registry.add("app.whatsapp.app-secret", () -> APP_SECRET);
    }

    @Autowired
    private WhatsAppMessageService messages;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Test
    void createdTemplateCarriesClinicNameAndCanBeSentOnlyBySlots() {
        SeededTenant tenant = seedTenant();
        String token = ownerToken(tenant);

        ResponseEntity<Map> created = exchange("/v1/setup/whatsapp-templates", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "visit_reminder", "language", "en", "category", "UTILITY",
                "body", "Hi {{1}}, your visit is at {{2}}.", "examples", List.of("Asha", "10:00"))), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("headerText")).isEqualTo("Test Clinic " + tenant.slug()); // NB-198
        assertThat(created.getBody().get("status")).isEqualTo("approved"); // LoggingWhatsAppClient auto-approves
        assertThat(created.getBody().get("paramCount")).isEqualTo(2);

        // NB-191: wrong slot count and multi-line values are refused before anything is queued
        assertThatThrownBy(() -> inTenantTx(tenant.id(), () -> messages.queueTemplate(
                tenant.id(), null, "+919000000000", "visit_reminder", "en", List.of("Asha"))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> inTenantTx(tenant.id(), () -> messages.queueTemplate(
                tenant.id(), null, "+919000000000", "visit_reminder", "en", List.of("Asha\nFree text here", "10:00"))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> inTenantTx(tenant.id(), () -> messages.queueTemplate(
                tenant.id(), null, "+919000000000", "no_such_template", "en", List.of())))
                .isInstanceOf(ApiException.class);

        UUID id = inTenantTx(tenant.id(), () -> messages.queueTemplate(
                tenant.id(), null, "+919000000000", "visit_reminder", "en", List.of("Asha", "10:00")));
        dispatcher.poll();
        assertThat(messageStatus(tenant.id(), id)).isEqualTo("sent");
    }

    @Test
    void metaRulesAreCheckedBeforeSubmission() {
        SeededTenant tenant = seedTenant();
        String token = ownerToken(tenant);
        for (String badBody : List.of("{{1}} is your code", "Visit at {{1}}", "Hi {{2}} there", "Hi {{1}} {{2}} there")) {
            ResponseEntity<Map> resp = exchange("/v1/setup/whatsapp-templates", HttpMethod.POST, authedJsonBody(token, Map.of(
                    "name", "bad", "language", "en", "category", "UTILITY", "body", badBody,
                    "examples", badBody.contains("{{2}}") ? List.of("a", "b") : List.of("a"))), Map.class);
            assertThat(resp.getStatusCode()).as(badBody).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Test
    void rejectionSurfacesWithReasonThenEditResubmitsAndQualityIsTracked() {
        SeededTenant tenant = seedTenant();
        String token = ownerToken(tenant);
        Map<?, ?> created = exchange("/v1/setup/whatsapp-templates", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "offer", "language", "en", "category", "MARKETING",
                "body", "Get {{1}} off this week only.", "examples", List.of("20%"))), Map.class).getBody();
        String templateId = (String) created.get("id");
        String metaId = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT meta_template_id FROM whatsapp_templates WHERE id = ?::uuid", String.class, templateId));

        postWebhook("""
                {"entry":[{"changes":[{"field":"message_template_status_update","value":{"event":"REJECTED",
                 "message_template_id":"%s","reason":"INCORRECT_CATEGORY"}}]}]}""".formatted(metaId));
        Map<?, ?> rejected = template(token, templateId);
        assertThat(rejected.get("status")).isEqualTo("rejected");
        assertThat(rejected.get("rejectionReason")).isEqualTo("INCORRECT_CATEGORY");

        ResponseEntity<Map> resubmitted = exchange("/v1/setup/whatsapp-templates/" + templateId, HttpMethod.PATCH,
                authedJsonBody(token, Map.of("body", "This week, {{1}} off all consultations.", "examples", List.of("20%"))), Map.class);
        assertThat(resubmitted.getBody().get("status")).isEqualTo("pending");
        assertThat(resubmitted.getBody().get("rejectionReason")).isNull();

        // only rejected templates are editable
        assertThat(exchange("/v1/setup/whatsapp-templates/" + templateId, HttpMethod.PATCH,
                authedJsonBody(token, Map.of("body", "Again {{1}} off.", "examples", List.of("20%"))), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        postWebhook("""
                {"entry":[{"changes":[{"field":"message_template_quality_update","value":{
                 "message_template_id":"%s","new_quality_score":"RED"}}]}]}""".formatted(metaId));
        assertThat(template(token, templateId).get("qualityRating")).isEqualTo("RED");
    }

    @Test
    void queuedMessageFailsIfItsTemplateIsPausedBeforeSending() {
        SeededTenant tenant = seedTenant();
        String token = ownerToken(tenant);
        exchange("/v1/setup/whatsapp-templates", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "visit_reminder", "language", "en", "category", "UTILITY",
                "body", "Your visit is at {{1}} today.", "examples", List.of("10:00"))), Map.class);
        UUID id = inTenantTx(tenant.id(), () -> messages.queueTemplate(
                tenant.id(), null, "+919000000000", "visit_reminder", "en", List.of("10:00")));
        String metaId = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT meta_template_id FROM whatsapp_templates WHERE tenant_id = ?", String.class, tenant.id()));
        postWebhook("""
                {"entry":[{"changes":[{"field":"message_template_status_update","value":{"event":"PAUSED",
                 "message_template_id":"%s"}}]}]}""".formatted(metaId));

        dispatcher.poll();
        assertThat(messageStatus(tenant.id(), id)).isEqualTo("failed");
    }

    @Test
    void templatesAreTenantIsolated() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        exchange("/v1/setup/whatsapp-templates", HttpMethod.POST, authedJsonBody(ownerToken(a), Map.of(
                "name", "visit_reminder", "language", "en", "category", "UTILITY",
                "body", "Your visit is at {{1}} today.", "examples", List.of("10:00"))), Map.class);
        assertThat(exchange("/v1/setup/whatsapp-templates", HttpMethod.GET, authed(ownerToken(b)), List.class).getBody()).isEmpty();
    }

    private String ownerToken(SeededTenant tenant) {
        UUID roleId = seedFullAccessRole(tenant.id());
        return loginAndGetAccessToken(seedStaff(tenant, roleId, "o" + UUID.randomUUID().toString().substring(0, 8) + "@a.com",
                "+9198" + (10000000 + (int) (Math.random() * 89999999)), false));
    }

    private Map<?, ?> template(String token, String id) {
        List<?> all = exchange("/v1/setup/whatsapp-templates", HttpMethod.GET, authed(token), List.class).getBody();
        return all.stream().map(t -> (Map<?, ?>) t).filter(t -> id.equals(t.get("id"))).findFirst().orElseThrow();
    }

    private String messageStatus(UUID tenantId, UUID id) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT status FROM whatsapp_messages WHERE id = ?", String.class, id));
    }

    private void postWebhook(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Hub-Signature-256", "sha256=" + hmac(body));
        assertThat(http.postForEntity(url("/v1/webhooks/whatsapp"), new HttpEntity<>(body, headers), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

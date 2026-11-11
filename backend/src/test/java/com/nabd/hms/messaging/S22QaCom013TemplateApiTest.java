package com.nabd.hms.messaging;

import com.nabd.hms.common.ModuleGrant;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S22 QA (t1), COM-013: template edits are tenant-scoped and need setup:edit; a retried PATCH (same Idempotency-Key, same body) does
 * not create another version; a replayed Meta webhook (same body) changes nothing a second time; bad ids are 400/404, never 500.
 * Meta is the default stub (LoggingWhatsAppClient approves every submission at once).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S22QaCom013TemplateApiTest extends ApiTestBase {

    private static final String APP_SECRET = "test-app-secret";
    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 400_000) + 100_000);

    @DynamicPropertySource
    static void whatsappProps(DynamicPropertyRegistry registry) {
        registry.add("app.whatsapp.app-secret", () -> APP_SECRET);
    }

    @Autowired private WhatsAppInboundProcessor inboundProcessor;

    private String token(SeededTenant t, UUID role) {
        return loginAndGetAccessToken(seedStaff(t, role, "qa22-c13-" + SEQ.incrementAndGet() + "@a.com", "+9197" + String.format("%08d", SEQ.get()), false));
    }

    private String create(String token, String body, List<String> examples) {
        ResponseEntity<Map> r = exchange("/v1/setup/whatsapp-templates", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", "qa_note", "language", "en", "category", "UTILITY", "body", body, "examples", examples)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private ResponseEntity<Map> patch(String token, String id, String body, List<String> examples, String key) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Idempotency-Key", key);
        try {
            return exchange("/v1/setup/whatsapp-templates/" + id, HttpMethod.PATCH,
                    new HttpEntity<>(objectMapper.writeValueAsString(Map.of("body", body, "examples", examples)), h), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int versions(UUID tenant) {
        return inTenantTx(tenant, () -> jdbc.queryForObject("SELECT count(*) FROM whatsapp_templates WHERE tenant_id = ? AND name = 'qa_note'", Integer.class, tenant));
    }

    private List<String> events(UUID tenant) {
        return inTenantTx(tenant, () -> jdbc.queryForList("""
                SELECT (payload->>'from') || '>' || (payload->>'to') || COALESCE(':' || (payload->>'category'), '')
                  FROM outbox_events WHERE tenant_id = ? AND event_type = 'comms.template.status_changed.v1' ORDER BY id""", String.class, tenant));
    }

    private void postWebhook(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Hub-Signature-256", "sha256=" + hmac(body));
        assertThat(http.postForEntity(url("/v1/webhooks/whatsapp"), new HttpEntity<>(body, headers), Void.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        inboundProcessor.poll();
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

    @Test
    void a01_anotherClinicCannotEditATemplate_andSetupViewAloneCannot() {
        SeededTenant a = seedTenant(), b = seedTenant();
        String ownerA = token(a, seedFullAccessRole(a.id()));
        String ownerB = token(b, seedFullAccessRole(b.id()));
        String id = create(ownerA, "Hi {{1}}, your visit is at {{2}}.", List.of("Asha", "10:00"));
        ResponseEntity<Map> foreign = patch(ownerB, id, "Changed {{1}} by B at {{2}}.", List.of("Asha", "10:00"), UUID.randomUUID().toString());
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        String viewer = token(a, seedRole(a.id(), "Viewer", false, new ModuleGrant("setup", true, false, false, false, false, false, false)));
        assertThat(patch(viewer, id, "Changed {{1}} by viewer {{2}}.", List.of("Asha", "10:00"), UUID.randomUUID().toString()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(versions(a.id())).isEqualTo(1);
        assertThat(versions(b.id())).isZero();
        assertThat(inTenantTx(a.id(), () -> jdbc.queryForObject("SELECT body FROM whatsapp_templates WHERE id = ?::uuid", String.class, id)))
                .isEqualTo("Hi {{1}}, your visit is at {{2}}.");
    }

    @Test
    void a02_aRetriedPatchWithTheSameKeyAndBodyDoesNotCreateAnotherVersion() {
        SeededTenant t = seedTenant();
        String owner = token(t, seedFullAccessRole(t.id()));
        String id = create(owner, "Hi {{1}}, your visit is at {{2}}.", List.of("Asha", "10:00"));
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> first = patch(owner, id, "Hello {{1}}, see you at {{2}}.", List.of("Asha", "10:00"), key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> retry = patch(owner, id, "Hello {{1}}, see you at {{2}}.", List.of("Asha", "10:00"), key);
        System.out.println("QA COM-013 a02: first=" + first.getBody().get("version") + " retry=" + retry.getStatusCode() + " " + retry.getBody().get("version")
                + " versions=" + versions(t.id()));
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().get("id")).as("spec: PATCH is idempotent; a retry returns the version it created").isEqualTo(first.getBody().get("id"));
        assertThat(versions(t.id())).isEqualTo(2);
    }

    @Test
    void a03_aReplayedWebhookChangesNothingTheSecondTime() {
        SeededTenant t = seedTenant();
        String owner = token(t, seedFullAccessRole(t.id()));
        String id = create(owner, "Hi {{1}}, your visit is at {{2}}.", List.of("Asha", "10:00"));
        String metaId = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT meta_template_id FROM whatsapp_templates WHERE id = ?::uuid", String.class, id));
        String paused = """
                {"entry":[{"time":1767000000,"changes":[{"field":"message_template_status_update","value":{"event":"PAUSED",
                 "message_template_id":"%s","reason":null,"other_info":{"title":"FIRST_PAUSE"}}}]}]}""".formatted(metaId);
        postWebhook(paused);
        String until1 = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT paused_until::text FROM whatsapp_templates WHERE id = ?::uuid", String.class, id));
        postWebhook(paused);
        String until2 = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT paused_until::text FROM whatsapp_templates WHERE id = ?::uuid", String.class, id));
        assertThat(until1).isNotNull();
        assertThat(until2).as("a replayed pause must not extend the pause").isEqualTo(until1);

        String category = """
                {"entry":[{"time":1767000100,"changes":[{"field":"template_category_update","value":{"message_template_id":"%s",
                 "previous_category":"UTILITY","new_category":"MARKETING"}}]}]}""".formatted(metaId);
        postWebhook(category);
        postWebhook(category);
        assertThat(events(t.id())).containsExactly("approved>paused", "paused>paused:MARKETING");
    }

    @Test
    void a04_badIdsAre400Or404_neverA500() {
        SeededTenant t = seedTenant();
        String owner = token(t, seedFullAccessRole(t.id()));
        assertThat(patch(owner, "not-a-uuid", "Hello {{1}}.", List.of("A"), UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(patch(owner, UUID.randomUUID().toString(), "Hello {{1}}.", List.of("A"), UUID.randomUUID().toString()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}

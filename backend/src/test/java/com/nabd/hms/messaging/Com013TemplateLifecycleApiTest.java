package com.nabd.hms.messaging;

import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** COM-013: version swap on approval, pause hold vs window expiry, category change gates consent, rename resubmits headers. */
class Com013TemplateLifecycleApiTest extends ApiTestBase {

    private static final String APP_SECRET = "test-app-secret";

    @DynamicPropertySource
    static void whatsappProps(DynamicPropertyRegistry registry) {
        registry.add("app.whatsapp.app-secret", () -> APP_SECRET);
    }

    /** The logging client, but Meta's answer to a new template is set per test and every send's template name is recorded. */
    static final class FakeMeta extends LoggingWhatsAppClient {
        volatile String createStatus = "approved";
        final List<String> sent = new CopyOnWriteArrayList<>();

        @Override
        public Submission createTemplate(String metaName, String language, String category, String headerText, String body, List<String> examples) {
            return new Submission("meta." + UUID.randomUUID(), createStatus);
        }

        @Override
        public String sendTemplate(String toPhone, String templateName, String language, List<String> bodyParams) {
            sent.add(templateName);
            return super.sendTemplate(toPhone, templateName, language, bodyParams);
        }
    }

    static final FakeMeta META = new FakeMeta();

    @TestConfiguration
    static class Fake {
        @Bean @Primary WhatsAppClient fakeMeta() { return META; }
    }

    @Autowired private WhatsAppMessageService messages;
    @Autowired private OutboxDispatcher dispatcher;
    @Autowired private WhatsAppInboundProcessor inboundProcessor;
    @Autowired private CatalogueSubmissionJob submissionJob;

    @BeforeEach
    void reset() {
        META.createStatus = "approved";
        META.sent.clear();
    }

    // ---- 1. a new version is in review while the approved one keeps sending, then takes over on approval ----

    @Test
    void editCreatesAVersionInReviewThatTakesOverOnApproval() {
        SeededTenant tenant = seedTenant();
        String token = ownerToken(tenant);
        String v1 = create(token, "visit_note", "UTILITY", "Hi {{1}}, your visit is at {{2}}.", List.of("Asha", "10:00"));
        String v1Meta = metaName(tenant.id(), v1);

        META.createStatus = "pending";
        ResponseEntity<Map> edited = patch(token, v1, "Hello {{1}}, we will see you at {{2}}.", List.of("Asha", "10:00"));
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        String v2 = (String) edited.getBody().get("id");
        assertThat(edited.getBody()).containsEntry("version", 2).containsEntry("status", "pending").containsEntry("activeVersion", 1);

        // while v2 is in review: the same text again is idempotent, other text is 409, another slot count is 422
        assertThat(patch(token, v1, "Hello {{1}}, we will see you at {{2}}.", List.of("Asha", "10:00")).getBody()).containsEntry("id", v2);
        ResponseEntity<Map> conflict = patch(token, v1, "Different {{1}} text {{2}} here.", List.of("a", "b"));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().toString()).contains("VERSION_IN_REVIEW");

        // sends keep using the approved v1, also one queued now
        UUID before = queue(tenant.id(), "visit_note", List.of("Asha", "10:00"), null);
        dispatcher.poll();
        assertThat(messageStatus(tenant.id(), before)).isEqualTo("sent");
        assertThat(META.sent).contains(v1Meta).doesNotContain(v1Meta + "_v2"); // the dispatcher may also drain other classes' sends
        Map<String, Object> listed = listed(token, "visit_note");
        assertThat(listed).containsEntry("id", v1).containsEntry("activeVersion", 1);
        assertThat((List<?>) listed.get("versions")).hasSize(2);

        // a message queued before the approval goes out on v2 once Meta approves it (switch at the next claim, no wait)
        UUID queuedBeforeApproval = queue(tenant.id(), "visit_note", List.of("Asha", "10:00"), null);
        String v2MetaId = metaId(tenant.id(), v2);
        postWebhook(statusChange(v2MetaId, "APPROVED", null));
        assertThat(listed(token, "visit_note")).containsEntry("id", v2).containsEntry("activeVersion", 2);
        dispatcher.poll();
        assertThat(messageStatus(tenant.id(), queuedBeforeApproval)).isEqualTo("sent");
        assertThat(META.sent).contains(v1Meta + "_v2");
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT superseded_by FROM whatsapp_templates WHERE id = ?::uuid", UUID.class, v1))).isEqualTo(UUID.fromString(v2));
        assertThat(templateEvents(tenant.id())).contains("pending>approved");

        // a late approval of an older version never takes over again
        postWebhook(statusChange(metaId(tenant.id(), v1), "APPROVED", null));
        assertThat(listed(token, "visit_note")).containsEntry("id", v2);
    }

    @Test
    void aRejectedVersionKeepsTheApprovedOneSendingAndShowsMetasReason() {
        SeededTenant tenant = seedTenant();
        String token = ownerToken(tenant);
        String v1 = create(token, "visit_note", "UTILITY", "Hi {{1}}, your visit is at {{2}}.", List.of("Asha", "10:00"));
        META.createStatus = "pending";
        String v2 = (String) patch(token, v1, "Hi {{1}}, enjoy 20% off at {{2}}.", List.of("Asha", "10:00")).getBody().get("id");
        postWebhook(statusChange(metaId(tenant.id(), v2), "REJECTED", "PROMOTIONAL"));
        Map<String, Object> listed = listed(token, "visit_note");
        assertThat(listed).containsEntry("id", v1).containsEntry("status", "approved");
        assertThat((List<?>) listed.get("versions")).anySatisfy(v -> assertThat(asMap(v))
                .containsEntry("version", 2).containsEntry("status", "rejected").containsEntry("rejectionReason", "PROMOTIONAL"));
        assertThat(patch(token, v1, "Hi {{1}}, see you at {{2}}.", List.of("Asha", "10:00")).getBody()).containsEntry("version", 3);
    }

    // ---- 2. pause: hold within the window, expire when the window ends first ----

    @Test
    void aPausedTemplateHoldsASendUntilThePauseLiftsAndExpiresOneWhoseWindowEndsFirst() {
        SeededTenant tenant = seedTenant();
        String token = ownerToken(tenant);
        String id = create(token, "visit_note", "UTILITY", "Your visit is at {{1}} today.", List.of("10:00"));
        postWebhook("""
                {"entry":[{"changes":[{"field":"message_template_status_update","value":{"event":"PAUSED",
                 "message_template_id":"%s","reason":null,"other_info":{"title":"FIRST_PAUSE","description":"paused for 3 hours"}}}]}]}"""
                .formatted(metaId(tenant.id(), id)));
        Instant pausedUntil = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT paused_until FROM whatsapp_templates WHERE id = ?::uuid", java.sql.Timestamp.class, id)).toInstant();
        assertThat(Duration.between(Instant.now(), pausedUntil)).isBetween(Duration.ofMinutes(170), Duration.ofMinutes(181));

        UUID held = queue(tenant.id(), "visit_note", List.of("10:00"), Instant.now().plus(Duration.ofHours(6)));
        UUID late = queue(tenant.id(), "visit_note", List.of("10:00"), Instant.now().plus(Duration.ofHours(2)));
        dispatcher.poll();
        assertThat(messageStatus(tenant.id(), held)).isEqualTo("queued");
        assertThat(messageStatus(tenant.id(), late)).as("window ends before the pause lifts: never sent late").isEqualTo("expired");
        Instant retryAt = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT next_attempt_at FROM outbox_events WHERE event_type = 'whatsapp.send' AND payload->>'messageId' = ?",
                java.sql.Timestamp.class, held.toString())).toInstant();
        assertThat(retryAt).isAfterOrEqualTo(pausedUntil.minusSeconds(1));
        assertThat(META.sent).doesNotContain(metaName(tenant.id(), id));

        // the pause lifts (Meta reinstates it); the held send goes when its retry comes round
        postWebhook(statusChange(metaId(tenant.id(), id), "REINSTATED", null));
        inTenantTx(tenant.id(), () -> jdbc.update("UPDATE outbox_events SET next_attempt_at = now() WHERE event_type = 'whatsapp.send' AND payload->>'messageId' = ?",
                held.toString()));
        dispatcher.poll();
        assertThat(messageStatus(tenant.id(), held)).isEqualTo("sent");
    }

    // ---- 3. Meta re-categorises a utility template as marketing ----

    @Test
    void aMoveToMarketingGatesSendsOnMarketingConsentAndAlertsTheOwner() {
        SeededTenant tenant = seedTenant();
        String token = ownerToken(tenant);
        String id = create(token, "visit_note", "UTILITY", "Your visit is at {{1}} today.", List.of("10:00"));
        UUID patient = seedPatient(tenant.id());
        inTenantTx(tenant.id(), () -> jdbc.update(
                "INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'messaging')", tenant.id(), patient));

        postWebhook("""
                {"entry":[{"changes":[{"field":"template_category_update","value":{"message_template_id":"%s",
                 "message_template_name":"x","message_template_language":"en","previous_category":"UTILITY","new_category":"MARKETING"}}]}]}"""
                .formatted(metaId(tenant.id(), id)));
        Map<String, Object> listed = listed(token, "visit_note");
        assertThat(listed).containsEntry("category", "MARKETING").containsEntry("requestedCategory", "UTILITY");
        assertThat(listed.get("categoryChangedAt")).isNotNull(); // the owner banner (Setup > WhatsApp) reads this at once
        assertThat(templateEvents(tenant.id())).contains("approved>approved:MARKETING");

        UUID blocked = inTenantTx(tenant.id(), () -> messages.queueTemplate(patient, "+919000000000", "visit_note", "en", List.of("10:00")));
        dispatcher.poll();
        assertThat(messageStatus(tenant.id(), blocked)).isEqualTo("blocked");

        inTenantTx(tenant.id(), () -> jdbc.update(
                "INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'marketing')", tenant.id(), patient));
        UUID allowed = inTenantTx(tenant.id(), () -> messages.queueTemplate(patient, "+919000000000", "visit_note", "en", List.of("10:00")));
        dispatcher.poll();
        assertThat(messageStatus(tenant.id(), allowed)).isEqualTo("sent");
    }

    // ---- 4. clinic rename resubmits every template with the new header as a new version ----

    @Test
    void aClinicRenameResubmitsEveryTemplateAsANewVersionWithTheNewHeader() {
        SeededTenant tenant = seedTenant();
        SeededTenant other = seedTenant();
        String token = ownerToken(tenant);
        String a = create(token, "visit_note", "UTILITY", "Your visit is at {{1}} today.", List.of("10:00"));
        create(token, "thanks_note", "UTILITY", "Thank you for visiting, {{1}}.", List.of("Asha"));
        create(ownerToken(other), "visit_note", "UTILITY", "Your visit is at {{1}} today.", List.of("10:00"));

        META.createStatus = "pending";
        assertThat(exchange("/v1/setup/profile", HttpMethod.PATCH, authedJsonBody(token, Map.of("name", "Sunrise Dental " + tenant.slug(),
                "timezone", "Asia/Kolkata")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (int i = 0; i < 30 && pendingVersions(tenant.id()) < 2; i++) {
            submissionJob.runOnce(); // a bounded number per run, shared with other clinics in this database
        }

        List<Map<String, Object>> rows = inTenantTx(tenant.id(), () -> jdbc.queryForList(
                "SELECT name, version, active, status, header_text FROM whatsapp_templates WHERE tenant_id = ? ORDER BY name, version", tenant.id()));
        assertThat(rows).filteredOn(r -> (int) r.get("version") == 2).hasSize(2).allSatisfy(r -> assertThat(r)
                .containsEntry("header_text", "Sunrise Dental " + tenant.slug()).containsEntry("status", "pending").containsEntry("active", false));
        assertThat(rows).filteredOn(r -> (int) r.get("version") == 1).allSatisfy(r -> assertThat(r).containsEntry("active", true));
        submissionJob.runOnce(); // idempotent: nothing more to resubmit
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject("SELECT count(*) FROM whatsapp_templates WHERE tenant_id = ?", Integer.class, tenant.id())))
                .isEqualTo(4);
        assertThat(inTenantTx(other.id(), () -> jdbc.queryForObject("SELECT count(*) FROM whatsapp_templates WHERE tenant_id = ?", Integer.class, other.id())))
                .as("the other clinic is untouched").isEqualTo(1);

        postWebhook(statusChange(metaId(tenant.id(), versionId(tenant.id(), "visit_note", 2)), "APPROVED", null));
        assertThat(listed(token, "visit_note")).containsEntry("version", 2).containsEntry("headerText", "Sunrise Dental " + tenant.slug());
        assertThat(a).isNotNull();
    }

    @Test
    void templateWebhooksNeverCrossClinics() {
        SeededTenant a = seedTenant();
        SeededTenant b = seedTenant();
        String tokenA = ownerToken(a), tokenB = ownerToken(b);
        String idA = create(tokenA, "visit_note", "UTILITY", "Your visit is at {{1}} today.", List.of("10:00"));
        create(tokenB, "visit_note", "UTILITY", "Your visit is at {{1}} today.", List.of("10:00"));
        postWebhook(statusChange(metaId(a.id(), idA), "PAUSED", null));
        assertThat(listed(tokenA, "visit_note")).containsEntry("status", "paused");
        assertThat(listed(tokenB, "visit_note")).containsEntry("status", "approved");
        assertThat(templateEvents(b.id())).isEmpty();
    }

    // ---- helpers ----

    private String ownerToken(SeededTenant tenant) {
        UUID roleId = seedFullAccessRole(tenant.id());
        return loginAndGetAccessToken(seedStaff(tenant, roleId, "o" + UUID.randomUUID().toString().substring(0, 8) + "@a.com",
                "+9198" + (10000000 + (int) (Math.random() * 89999999)), false));
    }

    private String create(String token, String name, String category, String body, List<String> examples) {
        ResponseEntity<Map> r = exchange("/v1/setup/whatsapp-templates", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "language", "en", "category", category, "body", body, "examples", examples)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) r.getBody().get("id");
    }

    private ResponseEntity<Map> patch(String token, String id, String body, List<String> examples) {
        return exchange("/v1/setup/whatsapp-templates/" + id, HttpMethod.PATCH, authedJsonBody(token, Map.of("body", body, "examples", examples)), Map.class);
    }

    private Map<String, Object> listed(String token, String name) {
        List<?> all = exchange("/v1/setup/whatsapp-templates", HttpMethod.GET, authed(token), List.class).getBody();
        return all.stream().map(Com013TemplateLifecycleApiTest::asMap).filter(t -> name.equals(t.get("name"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private UUID seedPatient(UUID tenantId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,'P','+919000000000','1990-01-01','other') RETURNING id",
                UUID.class, tenantId));
    }

    private UUID queue(UUID tenantId, String name, List<String> params, Instant notAfter) {
        return inTenantTx(tenantId, () -> messages.queueTemplate(null, "+919000000000", name, "en", params, notAfter));
    }

    private int pendingVersions(UUID tenantId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT count(*) FROM whatsapp_templates WHERE tenant_id = ? AND version = 2 AND status = 'pending'",
                Integer.class, tenantId));
    }

    private String metaId(UUID tenantId, String id) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT meta_template_id FROM whatsapp_templates WHERE id = ?::uuid", String.class, id));
    }

    private String metaName(UUID tenantId, String id) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT meta_name FROM whatsapp_templates WHERE id = ?::uuid", String.class, id));
    }

    private String versionId(UUID tenantId, String name, int version) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT id::text FROM whatsapp_templates WHERE tenant_id = ? AND name = ? AND version = ?",
                String.class, tenantId, name, version));
    }

    private String messageStatus(UUID tenantId, UUID id) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT status FROM whatsapp_messages WHERE id = ?", String.class, id));
    }

    /** "from>to" (":category" when set) of each comms.template.status_changed.v1 of the clinic, oldest first. */
    private List<String> templateEvents(UUID tenantId) {
        return inTenantTx(tenantId, () -> jdbc.queryForList("""
                SELECT (payload->>'from') || '>' || (payload->>'to') || COALESCE(':' || (payload->>'category'), '')
                  FROM outbox_events WHERE tenant_id = ? AND event_type = 'comms.template.status_changed.v1' ORDER BY id""", String.class, tenantId));
    }

    private static String statusChange(String metaId, String event, String reason) {
        return """
                {"entry":[{"changes":[{"field":"message_template_status_update","value":{"event":"%s",
                 "message_template_id":"%s","reason":%s}}]}]}""".formatted(event, metaId, reason == null ? "null" : "\"" + reason + "\"");
    }

    private void postWebhook(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Hub-Signature-256", "sha256=" + hmac(body));
        assertThat(http.postForEntity(url("/v1/webhooks/whatsapp"), new HttpEntity<>(body, headers), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
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
}

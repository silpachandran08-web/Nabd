package com.nabd.hms.messaging;

import com.nabd.hms.common.OutboxDispatcher;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** E17 foundation: queue → outbox → send (LoggingWhatsAppClient, no access token in tests),
 * the consent gate, and the signed status webhook. */
class WhatsAppMessagingApiTest extends ApiTestBase {

    private static final String APP_SECRET = "test-app-secret";

    @DynamicPropertySource
    static void whatsappProps(DynamicPropertyRegistry registry) {
        registry.add("app.whatsapp.app-secret", () -> APP_SECRET);
        registry.add("app.whatsapp.verify-token", () -> "test-verify");
    }

    @Autowired
    private WhatsAppMessageService service;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private WhatsAppTemplateService templates;

    @Test
    void consentedPatientIsSentThenWebhookMovesStatusForwardOnly() {
        SeededTenant tenant = seedTenant();
        UUID patient = seedPatient(tenant.id());
        inTenantTx(tenant.id(), () -> jdbc.update(
                "INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'messaging')", tenant.id(), patient));

        seedReminderTemplate(tenant.id());
        UUID id = inTenantTx(tenant.id(), () -> service.queueTemplate(
                tenant.id(), patient, "+91 90000 00000", "appointment_reminder", "en", List.of("Dr. A", "10:00")));
        dispatcher.poll();

        String wamid = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT wamid FROM whatsapp_messages WHERE id = ? AND status = 'sent'", String.class, id));
        assertThat(wamid).startsWith("mock.");

        assertThat(postWebhook(statusPayload(wamid, "read"), true).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(postWebhook(statusPayload(wamid, "delivered"), true).getStatusCode()).isEqualTo(HttpStatus.OK); // late, out of order
        assertThat(statusOf(tenant.id(), id)).isEqualTo("read");
    }

    @Test
    void withdrawnConsentBlocksAnAlreadyQueuedMessage() {
        SeededTenant tenant = seedTenant();
        UUID patient = seedPatient(tenant.id());
        inTenantTx(tenant.id(), () -> jdbc.update(
                "INSERT INTO consents (tenant_id, patient_id, consent_type) VALUES (?,?,'messaging')", tenant.id(), patient));

        seedReminderTemplate(tenant.id());
        UUID id = inTenantTx(tenant.id(), () -> service.queueTemplate(
                tenant.id(), patient, "+919000000000", "appointment_reminder", "en", List.of("Dr. A", "10:00")));
        // withdrawal lands after queueing, before the dispatcher picks it up (NB-196)
        inTenantTx(tenant.id(), () -> jdbc.update(
                "INSERT INTO consents (tenant_id, patient_id, consent_type, created_at, withdrawn_at) " +
                        "VALUES (?,?,'messaging', now() + interval '1 second', now())", tenant.id(), patient));
        dispatcher.poll();

        assertThat(statusOf(tenant.id(), id)).isEqualTo("blocked");
    }

    @Test
    void unsignedOrBadlySignedWebhookIsRejected() {
        assertThat(postWebhook(statusPayload("wamid.x", "read"), false).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Hub-Signature-256", "sha256=" + "0".repeat(64));
        assertThat(http.postForEntity(url("/v1/webhooks/whatsapp"), new HttpEntity<>("{}", headers), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void subscriptionHandshakeEchoesChallengeOnlyForTheRightToken() {
        assertThat(http.getForEntity(url("/v1/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=test-verify&hub.challenge=42"),
                String.class).getBody()).isEqualTo("42");
        assertThat(http.getForEntity(url("/v1/webhooks/whatsapp?hub.mode=subscribe&hub.verify_token=wrong&hub.challenge=42"),
                String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private void seedReminderTemplate(UUID tenantId) {
        templates.create(tenantId, new WhatsAppTemplateService.TemplateRequest("appointment_reminder", "en", "UTILITY",
                "Your visit with {{1}} is at {{2}} today.", List.of("Dr. A", "10:00")));
    }

    private UUID seedPatient(UUID tenantId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,'P','+919000000000','1990-01-01','other') RETURNING id",
                UUID.class, tenantId));
    }

    private String statusOf(UUID tenantId, UUID messageId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT status FROM whatsapp_messages WHERE id = ?", String.class, messageId));
    }

    private static String statusPayload(String wamid, String status) {
        return """
                {"object":"whatsapp_business_account","entry":[{"changes":[{"field":"messages",
                 "value":{"statuses":[{"id":"%s","status":"%s"}]}}]}]}""".formatted(wamid, status);
    }

    private ResponseEntity<Void> postWebhook(String body, boolean sign) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (sign) {
            headers.set("X-Hub-Signature-256", "sha256=" + hmac(body));
        }
        return http.postForEntity(url("/v1/webhooks/whatsapp"), new HttpEntity<>(body, headers), Void.class);
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

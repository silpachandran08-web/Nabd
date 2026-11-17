package com.nabd.hms.messaging;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COM-028 Core side: the Comms Gateway relays an SMS keyword to POST /internal/v1/consents:sms-keyword. A STOP withdraws marketing
 * (consent row + consent.changed.v1 + audit) in the context clinic within 60 s, suppresses marketing for the number in every clinic, and
 * WhatsApp marketing templates are then blocked at send time while utility messages still go.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class Com028SmsStopApiTest extends ApiTestBase {

    @DynamicPropertySource
    static void whatsappProps(DynamicPropertyRegistry registry) {
        registry.add("app.whatsapp.app-secret", () -> "test-app-secret"); // the phone-hash key of the suppression registry
        registry.add("app.whatsapp.verify-token", () -> "test-verify");
    }

    @Autowired private WhatsAppMessageService service;
    @Autowired private WhatsAppTemplateService templates;
    @Autowired private OutboxDispatcher dispatcher;
    @Autowired private OptOutRegistry optOutRegistry;

    private final String phone = "+9196" + (10000000 + (int) (Math.random() * 89999999));

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private ResponseEntity<Map> keyword(String token, UUID tenant, String text) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.set("X-Internal-Token", token);
        Map<String, Object> body = new HashMap<>();
        body.put("phone", phone.substring(1));
        body.put("text", text);
        body.put("tenantId", tenant);
        body.put("inboundRef", 42);
        return exchange("/internal/v1/consents:sms-keyword", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    @Test
    void theRelayNeedsTheServiceToken() {
        assertThat(keyword(null, null, "STOP").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(keyword("wrong", null, "STOP").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        SeededTenant t = seedTenant();
        assertThat(inTenantTx(t.id(), () -> optOutRegistry.isSuppressed(t.id(), phone, OptOutRegistry.MARKETING))).as("nothing recorded").isFalse();
    }

    @Test
    void anSmsStopWithdrawsMarketingWithin60sEverywhereAndWhatsAppMarketingIsBlockedAtSendTime() {
        SeededTenant a = seedClinic();
        SeededTenant b = seedClinic();
        UUID pa = seedPatient(a.id());
        UUID pb = seedPatient(b.id()); // the same number at another clinic
        for (SeededTenant t : List.of(a, b)) {
            UUID p = t == a ? pa : pb;
            grant(t.id(), p, "messaging");
            grant(t.id(), p, "marketing");
        }

        Instant at = Instant.now();
        ResponseEntity<Map> r = keyword(INTERNAL_COMMS_TOKEN, a.id(), "Stop");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("optedOut", true).containsEntry("confirm", true);

        Map<String, Object> row = inTenantTx(a.id(), () -> jdbc.queryForMap("SELECT withdrawn_at, source, created_at FROM consents "
                + "WHERE patient_id = ? AND consent_type = 'marketing' ORDER BY created_at DESC LIMIT 1", pa));
        assertThat(row.get("withdrawn_at")).isNotNull();
        assertThat(row.get("source")).isEqualTo("sms_stop");
        assertThat(Duration.between(at, ((java.sql.Timestamp) row.get("created_at")).toInstant()).abs()).isLessThan(Duration.ofSeconds(60));
        assertThat(su().queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND ce_type = 'consent.changed.v1' AND aggregate_id = ? "
                + "AND payload->>'consentType' = 'marketing' AND payload->>'status' = 'withdrawn'", Integer.class, a.id(), pa)).as("the gateway's snapshot follows").isEqualTo(1);
        assertThat(su().queryForObject("SELECT actor_name FROM audit_log WHERE tenant_id = ? AND entity_id = ? AND action = 'consent.withdrawn_by_phone'",
                String.class, a.id(), pa)).isEqualTo("SMS");
        assertThat(inTenantTx(a.id(), () -> jdbc.queryForObject("SELECT count(*) FROM consents WHERE patient_id = ? AND consent_type = 'messaging' AND withdrawn_at IS NOT NULL",
                Integer.class, pa))).as("an SMS STOP is the promotional opt-out only").isZero();
        assertThat(inTenantTx(b.id(), () -> optOutRegistry.isSuppressed(b.id(), phone, OptOutRegistry.MARKETING))).as("every clinic").isTrue();

        for (SeededTenant t : List.of(a, b)) {
            UUID p = t == a ? pa : pb;
            UUID offer = inTenantTx(t.id(), () -> service.queueTemplate(p, phone, "offer", "en", List.of("20%")));
            UUID reminder = inTenantTx(t.id(), () -> service.queueTemplate(p, phone, "appointment_reminder", "en", List.of("Dr. A", "10:00")));
            drain();
            assertThat(status(t.id(), offer)).as("marketing at clinic " + t.slug()).isEqualTo("blocked");
            assertThat(status(t.id(), reminder)).as("utility at clinic " + t.slug()).isEqualTo("sent");
        }

        // a repeated STOP changes nothing: no second confirmation
        assertThat(keyword(INTERNAL_COMMS_TOKEN, a.id(), "STOP").getBody()).containsEntry("optedOut", true).containsEntry("confirm", false);
    }

    @Test
    void aNonKeywordChangesNothingAndBadInputIs400() {
        SeededTenant a = seedClinic();
        UUID p = seedPatient(a.id());
        grant(a.id(), p, "marketing");
        assertThat(keyword(INTERNAL_COMMS_TOKEN, a.id(), "please do not stop my reminders").getBody()).containsEntry("optedOut", false).containsEntry("confirm", false);
        assertThat(inTenantTx(a.id(), () -> jdbc.queryForObject("SELECT count(*) FROM consents WHERE patient_id = ? AND withdrawn_at IS NOT NULL", Integer.class, p))).isZero();
        assertThat(keyword(INTERNAL_COMMS_TOKEN, a.id(), "x".repeat(65)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- helpers (same seeding as WhatsAppInboxApiTest)

    private SeededTenant seedClinic() {
        SeededTenant t = seedTenant();
        seedTemplate(t.id(), "appointment_reminder", "UTILITY", "Your visit with {{1}} is at {{2}} today.", List.of("Dr. A", "10:00"));
        seedTemplate(t.id(), "offer", "MARKETING", "Get {{1}} off this week only.", List.of("20%"));
        return t;
    }

    private void seedTemplate(UUID tenantId, String name, String category, String body, List<String> examples) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                        org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t").header("alg", "none")
                                .subject(UUID.randomUUID().toString()).claim("tenantId", tenantId.toString()).build()));
        try {
            templates.create(new WhatsAppTemplateService.TemplateRequest(name, "en", category, body, examples));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private UUID seedPatient(UUID tenantId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject(
                "INSERT INTO patients (tenant_id, name, phone, dob, gender) VALUES (?,'P',?,'1990-01-01','other') RETURNING id", UUID.class, tenantId, phone));
    }

    private void grant(UUID tenantId, UUID patient, String type) {
        inTenantTx(tenantId, () -> jdbc.update("INSERT INTO consents (tenant_id, patient_id, consent_type, created_at) VALUES (?,?,?, now() - interval '1 hour')",
                tenantId, patient, type));
    }

    private String status(UUID tenantId, UUID id) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject("SELECT status FROM whatsapp_messages WHERE id = ?", String.class, id));
    }

    private void drain() {
        for (int i = 0; i < 5; i++) dispatcher.poll();
    }
}

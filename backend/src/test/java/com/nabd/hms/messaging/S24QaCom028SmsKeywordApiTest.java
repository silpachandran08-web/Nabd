package com.nabd.hms.messaging;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** S24 QA (t1) COM-028 Core relay: the service token, and how real-world STOP texts are classified. */
@SuppressWarnings({"unchecked", "rawtypes"})
class S24QaCom028SmsKeywordApiTest extends ApiTestBase {

    @DynamicPropertySource
    static void whatsappProps(DynamicPropertyRegistry registry) {
        registry.add("app.whatsapp.app-secret", () -> "test-app-secret");
        registry.add("app.whatsapp.verify-token", () -> "test-verify");
    }

    private ResponseEntity<Map> keyword(String token, String phone, UUID tenant, String text) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) h.set("X-Internal-Token", token);
        Map<String, Object> body = new HashMap<>();
        body.put("phone", phone);
        body.put("text", text);
        body.put("tenantId", tenant);
        body.put("inboundRef", 7);
        return exchange("/internal/v1/consents:sms-keyword", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
    }

    private static String phone() {
        return "9188" + (10000000 + (int) (Math.random() * 89999999));
    }

    // C28-A01: no token / a staff bearer token instead of the service token
    @Test
    void c28a01_relayNeedsTheServiceToken() {
        SeededTenant t = seedTenant();
        String staff = loginAndGetAccessToken(seedStaff(t, seedFullAccessRole(t.id()), "q28" + UUID.randomUUID().toString().substring(0, 6) + "@a.com",
                "+9187" + (10000000 + (int) (Math.random() * 89999999)), false));
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(staff);
        int bearer = exchange("/internal/v1/consents:sms-keyword", HttpMethod.POST, new HttpEntity<>(Map.of("phone", phone(), "text", "STOP", "inboundRef", 1), h), Map.class)
                .getStatusCode().value();
        int none = keyword(null, phone(), null, "STOP").getStatusCode().value();
        System.out.println("C28-A01 noToken=" + none + " staffBearer=" + bearer);
        assertThat(none).isEqualTo(401);
        assertThat(bearer).isIn(401, 403);
    }

    // C28-A03: which texts opt out (each on a fresh number, no clinic bound)
    @Test
    void c28a03_realWorldStopTexts() {
        Map<String, Object> got = new LinkedHashMap<>();
        for (String text : new String[]{"STOP", "Stop.", " stop ", "STOP!!", "UNSUBSCRIBE", "stop promotions", "STOP NABDHS", "Stop please", "please stop", "रोकें", "START"}) {
            ResponseEntity<Map> r = keyword(INTERNAL_COMMS_TOKEN, phone(), null, text);
            got.put(text, r.getStatusCode().value() == 200 ? r.getBody().get("optedOut") : r.getStatusCode().value());
        }
        System.out.println("C28-A03 optedOut by text=" + got + " (STOP <header> / 'stop please' recorded as decisions)");
        assertThat(got).containsEntry("STOP", true).containsEntry("Stop.", true).containsEntry(" stop ", true).containsEntry("UNSUBSCRIBE", true)
                .containsEntry("stop promotions", true).containsEntry("रोकें", true).containsEntry("START", false);
    }
}

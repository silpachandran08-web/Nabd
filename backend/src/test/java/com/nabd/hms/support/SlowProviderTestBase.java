package com.nabd.hms.support;

import com.nabd.hms.shared.integration.ResilientHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

/** OPS-014/OPS-012: ApiTestBase with the real MetaWhatsAppClient pointed at {@link SlowWhatsAppStub}. The access
 * token is random per JVM (never a real credential); subclasses share one Spring context. The Meta circuit breaker
 * opens for 3 s here (30 s in application.yml) so one test's opened breaker is closed again for the next. */
public abstract class SlowProviderTestBase extends ApiTestBase {

    private static final String STUB_TOKEN = "stub-" + UUID.randomUUID();

    protected static final SlowWhatsAppStub STUB = SlowWhatsAppStub.INSTANCE;
    protected static final String META = "meta-whatsapp";

    @Autowired
    protected ResilientHttpClient integration;

    @DynamicPropertySource
    static void stubProvider(DynamicPropertyRegistry registry) {
        registry.add("app.whatsapp.api-base-url", STUB::baseUrl);
        registry.add("app.whatsapp.access-token", () -> STUB_TOKEN);
        registry.add("app.whatsapp.phone-number-id", () -> "stub-number");
        registry.add("app.integration.circuit-open-seconds", () -> "3");
    }

    @BeforeEach
    void stubHealthyAndBreakerClosed() throws InterruptedException {
        STUB.reset(0);
        for (int i = 0; i < 100 && integration.isOpen(META); i++) {
            Thread.sleep(50);
        }
        // one good call resets the breaker's failure count left by an earlier test
        integration.builder(META).baseUrl(STUB.baseUrl()).build().post().uri("/probe/messages")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body("{}").retrieve().toBodilessEntity();
        STUB.reset(0);
    }

    /** n queued whatsapp_messages rows (no patient, no template row) and their whatsapp.send outbox events; the
     * first body parameter is "<prefix><i>", which the stub counts. */
    protected void queueWhatsApp(SeededTenant t, int n, String prefix) {
        inTenantTx(t.id(), () -> {
            for (int i = 0; i < n; i++) {
                UUID id = jdbc.queryForObject("""
                        INSERT INTO whatsapp_messages (tenant_id, to_phone, template_name, language, params)
                        VALUES (?, '+919800000000', 'appointment_reminder', 'en', jsonb_build_array(?::text)) RETURNING id
                        """, UUID.class, t.id(), prefix + i);
                jdbc.update("INSERT INTO outbox_events (tenant_id, event_type, payload) VALUES (?, 'whatsapp.send', jsonb_build_object('messageId', ?::text))",
                        t.id(), id.toString());
            }
        });
    }
}

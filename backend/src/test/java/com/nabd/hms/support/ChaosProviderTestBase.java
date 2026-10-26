package com.nabd.hms.support;

import com.nabd.hms.shared.integration.ResilientHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

/**
 * OPS-045: ApiTestBase with the real MetaWhatsAppClient pointed at {@link ChaosWhatsAppStub}, the webhook secret set and the Meta read
 * timeout compressed to 1 s (so "10 s latency against a 5 s timeout" becomes 1.5 s against 1 s). The access token is random per JVM.
 * The Meta circuit breaker opens for 2 s here so one test's open breaker is closed again for the next.
 */
public abstract class ChaosProviderTestBase extends ApiTestBase {

    protected static final ChaosWhatsAppStub STUB = ChaosWhatsAppStub.INSTANCE;
    protected static final String META = "meta-whatsapp";
    protected static final String APP_SECRET = "test-app-secret";
    private static final String STUB_TOKEN = "chaos-" + UUID.randomUUID();

    @Autowired
    protected ResilientHttpClient integration;

    @DynamicPropertySource
    static void chaosProvider(DynamicPropertyRegistry registry) {
        registry.add("app.whatsapp.api-base-url", STUB::baseUrl);
        registry.add("app.whatsapp.access-token", () -> STUB_TOKEN);
        registry.add("app.whatsapp.phone-number-id", () -> "chaos-number");
        registry.add("app.whatsapp.business-account-id", () -> "chaos-waba");
        registry.add("app.whatsapp.app-secret", () -> APP_SECRET);
        registry.add("app.whatsapp.verify-token", () -> "test-verify");
        registry.add("app.integration.circuit-open-seconds", () -> "2");
        registry.add("app.integration.providers.meta-whatsapp.read-timeout-ms", () -> "1000");
    }

    @BeforeEach
    void chaosHealthyAndBreakerClosed() throws InterruptedException {
        STUB.reset();
        for (int i = 0; i < 100 && integration.isOpen(META); i++) {
            Thread.sleep(50);
        }
        integration.builder(META).baseUrl(STUB.baseUrl()).build().post().uri("/probe/messages")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body("{}").retrieve().toBodilessEntity();
        STUB.reset();
    }
}

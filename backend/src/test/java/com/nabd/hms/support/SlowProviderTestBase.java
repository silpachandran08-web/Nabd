package com.nabd.hms.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

/** OPS-014/OPS-012: ApiTestBase with the real MetaWhatsAppClient pointed at {@link SlowWhatsAppStub}. The access
 * token is random per JVM (never a real credential); subclasses share one Spring context. */
public abstract class SlowProviderTestBase extends ApiTestBase {

    private static final String STUB_TOKEN = "stub-" + UUID.randomUUID();

    protected static final SlowWhatsAppStub STUB = SlowWhatsAppStub.INSTANCE;

    @DynamicPropertySource
    static void stubProvider(DynamicPropertyRegistry registry) {
        registry.add("app.whatsapp.api-base-url", STUB::baseUrl);
        registry.add("app.whatsapp.access-token", () -> STUB_TOKEN);
        registry.add("app.whatsapp.phone-number-id", () -> "stub-number");
    }
}

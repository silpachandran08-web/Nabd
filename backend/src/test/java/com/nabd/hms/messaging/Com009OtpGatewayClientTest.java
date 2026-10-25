package com.nabd.hms.messaging;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.shared.integration.ResilientHttpClient;
import com.nabd.hms.support.ApiTestBase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * COM-009 Core side: OtpGatewayClient and the in-process fallback in TemplateWhatsAppOtpSender, against a stub gateway.
 * (The gateway itself, its rate limiter and bulkhead belong to services/comms-gateway.) The comms-gateway circuit breaker is
 * shared by provider name, so no test makes five failing calls in a row.
 */
@ExtendWith(OutputCaptureExtension.class)
class Com009OtpGatewayClientTest extends ApiTestBase {

    private static final String CODE = "907351";
    private static final String PHONE = "+919812345678";

    @Autowired private ResilientHttpClient integration;
    @Autowired private WhatsAppClient whatsApp;
    @Autowired private WhatsAppProperties props;

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(202);
    private final AtomicInteger delayMs = new AtomicInteger(0);
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> auth = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startGateway() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/otp/send", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            try {
                Thread.sleep(delayMs.get());
            } catch (InterruptedException ignored) {
                // test over
            }
            byte[] out = "{\"messageId\":\"m1\",\"channel\":\"whatsapp\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stopGateway() {
        server.stop(0);
    }

    private TemplateWhatsAppOtpSender sender(String baseUrl, boolean cutover) {
        return new TemplateWhatsAppOtpSender(whatsApp, props,
                new OtpGatewayClient(new CommsGatewayProperties(baseUrl, "test-token", cutover), integration));
    }

    private String gatewayUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void gatewayAcceptsTheCodeAndTheInProcessSenderIsNotUsed(CapturedOutput output) {
        sender(gatewayUrl(), false).send(PHONE, CODE);

        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0)).contains("\"phoneE164\":\"" + PHONE + "\"").contains("\"code\":\"" + CODE + "\"")
                .contains("\"purpose\":\"staff_login\"").contains("\"language\":\"" + props.otpLanguage() + "\"");
        assertThat(auth).containsExactly("Bearer test-token");
        assertThat(output.getAll()).doesNotContain("[MOCK WhatsApp OTP]").doesNotContain(CODE).doesNotContain(PHONE).doesNotContain("9812345678");
    }

    @Test
    void rateLimitedByTheGatewayIs429AndNothingIsSentInProcess(CapturedOutput output) {
        status.set(429);
        assertThatThrownBy(() -> sender(gatewayUrl(), false).send(PHONE, CODE))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.typeSlug()).isEqualTo("OTP_RATE_LIMITED");
                });
        assertThat(output.getAll()).doesNotContain("[MOCK WhatsApp OTP]").doesNotContain(CODE).doesNotContain("9812345678");
        status.set(202);
        sender(gatewayUrl(), false).send(PHONE, CODE); // a success closes the breaker's failure run
    }

    @Test
    void gatewayDownAndTenantNotCutOverFallsBackToTheInProcessSender(CapturedOutput output) {
        status.set(503);
        sender(gatewayUrl(), false).send(PHONE, CODE);

        assertThat(output.getAll()).contains("[MOCK WhatsApp OTP]").contains("using the in-process sender")
                .doesNotContain(CODE).doesNotContain("9812345678");
        status.set(202);
        sender(gatewayUrl(), false).send(PHONE, CODE);
    }

    @Test
    void gatewayDownAndCutOverIs503WithARetryHintAndNoFallback(CapturedOutput output) {
        status.set(503);
        assertThatThrownBy(() -> sender(gatewayUrl(), true).send(PHONE, CODE))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.typeSlug()).isEqualTo("PROVIDER_UNAVAILABLE");
                    assertThat(e.extras()).containsEntry("retryAfterSeconds", 5);
                });
        assertThat(output.getAll()).doesNotContain("[MOCK WhatsApp OTP]").doesNotContain(CODE).doesNotContain("9812345678");
        status.set(202);
        sender(gatewayUrl(), true).send(PHONE, CODE);
    }

    @Test
    void unreachableGatewayFallsBackWhenNotCutOver(CapturedOutput output) {
        server.stop(0);
        sender(gatewayUrl(), false).send(PHONE, CODE);
        assertThat(output.getAll()).contains("[MOCK WhatsApp OTP]").doesNotContain(CODE).doesNotContain("9812345678");
    }

    @Test
    void aSlowGatewayIsCutOffInsideTheThreeSecondBudgetThenTheFallbackRuns(CapturedOutput output) {
        delayMs.set(4000);
        long t0 = System.nanoTime();
        sender(gatewayUrl(), false).send(PHONE, CODE);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(ms).as("gateway attempt plus fallback").isLessThan(3000);
        assertThat(output.getAll()).contains("[MOCK WhatsApp OTP]").doesNotContain(CODE);
        delayMs.set(0);
        sender(gatewayUrl(), false).send(PHONE, CODE);
    }

    @Test
    void noGatewayConfiguredMeansTheInProcessSenderOnly(CapturedOutput output) {
        sender("", false).send(PHONE, CODE);
        sender(null, true).send(PHONE, CODE); // cut over with no gateway at all is a config error, but never silent loss: still sends
        assertThat(bodies).isEmpty();
        assertThat(output.getAll()).contains("[MOCK WhatsApp OTP]").doesNotContain(CODE).doesNotContain("9812345678");
    }
}

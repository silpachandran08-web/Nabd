package com.nabd.hms.billing.gateway;

import com.nabd.hms.shared.integration.ExternalCallGuard;
import com.nabd.hms.shared.integration.IntegrationProperties;
import com.nabd.hms.shared.integration.ResilientHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FIN-019 adapter contract against a local fake (no network, no Docker): 401 = wrong keys, 5xx = unavailable, unsupported calls say so. */
class RazorpayGatewayTest {

    static HttpServer server;
    static final AtomicInteger NEXT = new AtomicInteger(200);
    static RazorpayGateway gw;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = "{\"id\":\"rfnd_1\",\"status\":\"processed\",\"short_url\":\"https://rzp.io/i/x\"}".getBytes(StandardCharsets.UTF_8);
            int code = NEXT.get();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        var props = new IntegrationProperties(2000, 5000, 50, 30, Map.of());
        gw = new RazorpayGateway(new ResilientHttpClient(props, new ExternalCallGuard(new MockEnvironment()), Clock.systemUTC()),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static final PaymentGateway.Credentials KEYS = new PaymentGateway.Credentials("k", "s");

    @Test
    void verifyMapsStatusToAReasonCodeThatNeverCarriesProviderText() {
        NEXT.set(200);
        gw.verify(KEYS);
        NEXT.set(401);
        assertThatThrownBy(() -> gw.verify(KEYS)).isInstanceOfSatisfying(GatewayException.class, e -> assertThat(e.reason()).isEqualTo("invalid_credentials"));
        NEXT.set(500);
        assertThatThrownBy(() -> gw.verify(KEYS)).isInstanceOfSatisfying(GatewayException.class, e -> assertThat(e.reason()).isEqualTo("unavailable"));
        assertThatThrownBy(() -> gw.verify(new PaymentGateway.Credentials("", ""))).isInstanceOfSatisfying(GatewayException.class, e -> assertThat(e.reason()).isEqualTo("invalid_credentials"));
        assertThat(KEYS.toString()).doesNotContain("s]").doesNotContain("keySecret");
    }

    @Test
    void linkAndRefundWorkAndTheRestOfThePortIsExplicitlyUnsupported() {
        NEXT.set(200);
        assertThat(gw.createLink(KEYS, 100, "INR", "ref", "d").url()).isEqualTo("https://rzp.io/i/x");
        assertThat(gw.refund(KEYS, "pay_1", 100).status()).isEqualTo("processed");
        assertThatThrownBy(() -> gw.createQr(KEYS, 1, "INR", "r")).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> gw.fetchSettlements(KEYS, java.time.LocalDate.now())).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> gw.createMandate(KEYS, "c", 1, "INR")).isInstanceOf(GatewayException.class);
    }

    @Test
    void tapAndHyperPayAreBuiltButSwitchedOff() {
        for (PaymentGateway g : new PaymentGateway[]{new MarketGateways.Tap(), new MarketGateways.HyperPay()}) {
            assertThat(g.enabled()).isFalse();
            assertThat(g.currency()).isEqualTo("SAR");
            assertThatThrownBy(() -> g.verify(KEYS)).isInstanceOf(GatewayException.class);
        }
        assertThat(gw.enabled()).isTrue();
    }
}

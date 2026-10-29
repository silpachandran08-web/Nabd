package com.nabd.hms.support;

import com.nabd.hms.billing.gateway.GatewaySecrets;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sprint 16 E16 (FIN-019, COM-022): ONE Spring test context for both stories. It points Core at two local fake servers (a Razorpay stand-in and
 * the Comms Gateway's /internal/v1/senders API) and provides an in-memory tenant secret writer (the production writer is not built).
 * Keys are generated at test time. Both story tests extend this class so the context is cached once.
 */
@Import(S16E16FakeServicesBase.Beans.class)
public abstract class S16E16FakeServicesBase extends ApiTestBase {

    public static final String KEY_ID = "rzp_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    public static final String KEY_SECRET = UUID.randomUUID().toString().replace("-", "");
    public static final String COMMS_TOKEN = UUID.randomUUID().toString();
    public static final List<String> RAZORPAY_SEEN = new CopyOnWriteArrayList<>();
    public static final List<String> COMMS_SEEN = new CopyOnWriteArrayList<>();
    public static final Map<String, String> VAULT = new ConcurrentHashMap<>();
    /** The clinic the fake fleet answer names (set by the COM-022 test). */
    public static volatile UUID fleetTenant = UUID.randomUUID();

    private static final HttpServer RAZORPAY = server(ex -> {
        String good = "Basic " + Base64.getEncoder().encodeToString((KEY_ID + ":" + KEY_SECRET).getBytes(StandardCharsets.UTF_8));
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        RAZORPAY_SEEN.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " " + body);
        if (!good.equals(ex.getRequestHeaders().getFirst("Authorization"))) return new Reply(401, "{\"error\":{\"description\":\"Authentication failed\"}}");
        if (ex.getRequestURI().getPath().endsWith("/payment_links")) return new Reply(200, "{\"id\":\"plink_test1\",\"short_url\":\"https://rzp.io/i/test1\"}");
        return new Reply(200, "{\"entity\":\"collection\",\"count\":0,\"items\":[]}");
    });

    private static final HttpServer COMMS = server(ex -> {
        ex.getRequestBody().readAllBytes();
        COMMS_SEEN.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " tenant=" + ex.getRequestHeaders().getFirst("X-Tenant-Id"));
        if (!COMMS_TOKEN.equals(ex.getRequestHeaders().getFirst("X-Internal-Token"))) return new Reply(401, "{}");
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/health")) {
            return new Reply(200, "{\"senders\":[{\"id\":\"" + UUID.randomUUID() + "\",\"kind\":\"TENANT\",\"displayPhone\":\"+914000012345\",\"status\":\"active\",\"rating\":\"YELLOW\","
                    + "\"quality\":\"Medium\",\"tier\":\"TIER_1K\",\"tierLabel\":\"1,000 patients a day\",\"throttleMode\":\"bulk_paused\",\"blockRate7d\":[0,0,0,0,0.02,0.03,0.01],"
                    + "\"throttleLine\":0.015,\"events\":[{\"at\":\"2026-10-26T09:00:00Z\",\"kind\":\"rating\",\"text\":\"Your WhatsApp quality changed from High to Medium. Campaign and bulk messages are paused.\"}]}],"
                    + "\"templates\":[{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"reminder\",\"language\":\"en\",\"category\":\"UTILITY\",\"status\":\"approved\"}],\"unknownField\":1}");
        }
        if (path.endsWith("/quality")) return new Reply(200, "{\"quality\":\"Medium\"}");
        return new Reply(200, "{\"items\":[{\"senderAccountId\":\"" + UUID.randomUUID() + "\",\"tenantId\":\"" + fleetTenant + "\",\"kind\":\"TENANT\",\"region\":\"in\",\"status\":\"active\","
                + "\"rating\":\"YELLOW\",\"tier\":\"TIER_250\",\"throttleMode\":\"bulk_paused\",\"deliveredRate7d\":0.8,\"sends7d\":10}],\"nextCursor\":null}");
    });

    public static int commsPort() {
        return COMMS.getAddress().getPort();
    }

    record Reply(int code, String body) {
    }

    interface Handler {
        Reply handle(com.sun.net.httpserver.HttpExchange ex) throws IOException;
    }

    private static HttpServer server(Handler h) {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                Reply r = h.handle(ex);
                byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(r.code(), b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void fakeServices(DynamicPropertyRegistry r) {
        r.add("nabd.payments.razorpay.base-url", () -> "http://127.0.0.1:" + RAZORPAY.getAddress().getPort() + "/v1");
        r.add("app.comms.gateway.base-url", () -> "http://127.0.0.1:" + COMMS.getAddress().getPort());
        r.add("app.comms.gateway.service-token", () -> COMMS_TOKEN);
    }

    @TestConfiguration
    static class Beans {
        @Bean
        GatewaySecrets inMemorySecrets() {
            return new GatewaySecrets() {
                @Override public void put(UUID tenantId, String name, String value) { VAULT.put(tenantId + "/" + name, value); }
                @Override public void delete(UUID tenantId, String name) { VAULT.remove(tenantId + "/" + name); }
                @Override public java.util.Optional<String> get(UUID tenantId, String name) { return java.util.Optional.ofNullable(VAULT.get(tenantId + "/" + name)); }
            };
        }
    }
}

package com.nabd.hms.billing;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** FIN-020 test adapter ("fake"): connected only for tenants a test registers; secrets are generated at test time. */
@Component
public class FakePaymentGateway implements PaymentGatewayPort {

    private static final Map<UUID, byte[]> SECRETS = new ConcurrentHashMap<>();
    private static final Set<String> PAID_AT_GATEWAY = ConcurrentHashMap.newKeySet();
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Connects the tenant and returns its fresh signing secret. */
    public static byte[] connect(UUID tenantId) {
        byte[] s = new byte[32];
        new SecureRandom().nextBytes(s);
        SECRETS.put(tenantId, s);
        return s;
    }

    /** The gateway now reports this order as paid (for the poller) without any webhook having arrived. */
    public static void payAtGateway(String orderId) {
        PAID_AT_GATEWAY.add(orderId);
    }

    public static String sign(byte[] secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String event(String eventId, String type, String orderId, long amountMinor) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"" + type + "\",\"order\":\"" + orderId + "\",\"ref\":\"pay_" + eventId + "\",\"amount\":" + amountMinor + "}";
    }

    @Override public String provider() { return "fake"; }

    @Override public boolean connected(UUID tenantId) { return SECRETS.containsKey(tenantId); }

    @Override public Optional<byte[]> webhookSecret(UUID tenantId) { return Optional.ofNullable(SECRETS.get(tenantId)); }

    @Override
    public Order createOrder(UUID tenantId, UUID intentId, long amountMinor, String currency, String kind, Instant expiresAt) {
        String id = "order_" + intentId.toString().replace("-", "");
        return "qr".equals(kind) ? new Order(id, null, "upi://pay?pa=clinic@bank&am=" + amountMinor + "&tr=" + id)
                : new Order(id, "https://pay.example.test/" + id, null);
    }

    @Override
    public OrderState fetchOrder(UUID tenantId, String providerOrderId) {
        return new OrderState(PAID_AT_GATEWAY.contains(providerOrderId), false, "pay_poll_" + providerOrderId);
    }

    @Override
    public Event parse(byte[] body) {
        JsonNode n = JSON.readTree(body);
        return new Event(n.path("eventId").asString(), n.path("type").asString(), n.path("order").asString(null),
                n.path("ref").asString(null), n.has("amount") ? n.path("amount").asLong() : null);
    }
}

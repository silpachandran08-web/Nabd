package com.nabd.comms.testkit;

import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * COM-010: a scripted WhatsApp user. say()/tap() post a Meta-shaped inbound webhook signed with the app secret (X-Hub-Signature-256) to
 * {@code endpoint}; the replies the gateway sent to this number are read back from the {@link ProviderStub}. Reusable from JUnit; a browser
 * test can drive the same shape over HTTP once the webhook receiver exists. Fake numbers only.
 */
public class VirtualPatient {

    public record Webhook(String body, String signature) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String phone, phoneNumberId;
    private final byte[] appSecret;
    private final Consumer<Webhook> endpoint;
    private final ProviderStub stub;
    private final long started = System.nanoTime();
    private int seen, n;

    public VirtualPatient(String phone, String phoneNumberId, byte[] appSecret, Consumer<Webhook> endpoint, ProviderStub stub) {
        this.phone = phone;
        this.phoneNumberId = phoneNumberId;
        this.appSecret = appSecret;
        this.endpoint = endpoint;
        this.stub = stub;
    }

    public VirtualPatient say(String text) {
        return post(Map.of("type", "text", "text", Map.of("body", text)));
    }

    public VirtualPatient tap(String buttonPayload) {
        return post(Map.of("type", "interactive", "interactive", Map.of("type", "button_reply", "button_reply", Map.of("id", buttonPayload, "title", "button"))));
    }

    /** Asserts the next reply (after the ones already checked) contains {@code fragment}. */
    public VirtualPatient expectReply(String fragment) {
        List<ProviderStub.Outbound> all = stub.sentTo(phone);
        if (all.size() <= seen) {
            throw new AssertionError("no reply to " + phone + ", expected one containing '" + fragment + "'");
        }
        String got = all.get(seen++).template();
        if (!got.contains(fragment)) {
            throw new AssertionError("reply '" + got + "' does not contain '" + fragment + "'");
        }
        return this;
    }

    public int unreadReplies() {
        return stub.sentTo(phone).size() - seen;
    }

    public Duration elapsed() {
        return Duration.ofNanos(System.nanoTime() - started);
    }

    private VirtualPatient post(Map<String, Object> message) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(message);
        m.put("from", phone);
        m.put("id", "wamid.in." + (++n));
        Map<String, Object> value = Map.of("messaging_product", "whatsapp", "metadata", Map.of("phone_number_id", phoneNumberId), "messages", List.of(m));
        String body = JSON.writeValueAsString(Map.of("object", "whatsapp_business_account",
                "entry", List.of(Map.of("id", "waba", "changes", List.of(Map.of("field", "messages", "value", value))))));
        endpoint.accept(new Webhook(body, sign(appSecret, body)));
        return this;
    }

    public static String sign(byte[] secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

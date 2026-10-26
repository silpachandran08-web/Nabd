package com.nabd.hms.messaging;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nabd.hms.common.FieldCipher;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;

/** Meta's webhook endpoint — public (Meta can't send our JWT), so the HMAC signature is the only
 * thing standing between the internet and message status. No app secret configured = reject all. */
@RestController
@RequestMapping("/v1/webhooks/whatsapp")
class WhatsAppWebhookController {

    private static final int MAX_BODY_BYTES = 1_048_576;

    private final WhatsAppProperties props;
    private final WhatsAppInboundRepository inbound;
    private final FieldCipher cipher;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    WhatsAppWebhookController(WhatsAppProperties props, WhatsAppInboundRepository inbound, FieldCipher cipher,
                              ObjectMapper objectMapper, MeterRegistry meters) {
        this.props = props;
        this.inbound = inbound;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    /** Subscription handshake: Meta sends hub.verify_token, expects hub.challenge echoed back. */
    @GetMapping
    ResponseEntity<String> verify(@RequestParam("hub.mode") String mode,
                                  @RequestParam("hub.verify_token") String token,
                                  @RequestParam("hub.challenge") String challenge) {
        if ("subscribe".equals(mode) && notBlank(props.verifyToken()) && constantTimeEquals(props.verifyToken(), token)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    /** COM-001 persist-then-ack: verify the HMAC, store one encrypted row per change item, answer 200. Processing is
     * asynchronous (WhatsAppInboundProcessor); a repeated delivery stores nothing new. The body is never logged. */
    @PostMapping
    ResponseEntity<Void> receive(@RequestBody byte[] body,
                                 @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) throws Exception {
        if (body.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        if (!notBlank(props.appSecret()) || signature == null
                || !constantTimeEquals("sha256=" + hmacSha256Hex(props.appSecret(), body), signature)) {
            meters.counter("whatsapp.webhook.rejected", "reason", "bad_signature").increment();
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build(); // signed but not JSON: not Meta, a retry cannot fix it
        }
        List<WhatsAppInboundKeys.Item> items = WhatsAppInboundKeys.split(root, objectMapper);
        if (!items.isEmpty()) {
            List<byte[]> bodies = new ArrayList<>(items.size());
            for (WhatsAppInboundKeys.Item i : items) {
                bodies.add(cipher.encrypt(i.body().toString().getBytes(StandardCharsets.UTF_8), WhatsAppInboundService.aad(i.key())));
            }
            inbound.store(items, bodies);
        }
        return ResponseEntity.ok().build(); // anything but 2xx makes Meta retry the whole batch
    }

    private static String hmacSha256Hex(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}

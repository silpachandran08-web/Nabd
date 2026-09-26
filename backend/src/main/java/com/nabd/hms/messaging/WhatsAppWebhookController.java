package com.nabd.hms.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Set;

/** Meta's webhook endpoint — public (Meta can't send our JWT), so the HMAC signature is the only
 * thing standing between the internet and message status. No app secret configured = reject all. */
@RestController
@RequestMapping("/v1/webhooks/whatsapp")
class WhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);
    private static final Set<String> TRACKED = Set.of("delivered", "read", "failed");

    private final WhatsAppProperties props;
    private final WhatsAppMessageRepository repo;
    private final WhatsAppTemplateRepository templates;
    private final ObjectMapper objectMapper;

    WhatsAppWebhookController(WhatsAppProperties props, WhatsAppMessageRepository repo,
                              WhatsAppTemplateRepository templates, ObjectMapper objectMapper) {
        this.props = props;
        this.repo = repo;
        this.templates = templates;
        this.objectMapper = objectMapper;
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

    @PostMapping
    ResponseEntity<Void> receive(@RequestBody byte[] body,
                                 @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) throws Exception {
        if (!notBlank(props.appSecret()) || signature == null
                || !constantTimeEquals("sha256=" + hmacSha256Hex(props.appSecret(), body), signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        for (JsonNode entry : objectMapper.readTree(body).path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                switch (change.path("field").asText()) {
                    case "message_template_status_update" -> templates.applyUpdate(
                            value.path("message_template_id").asText(),
                            WhatsAppTemplateService.statusOf(value.path("event").asText()),
                            value.path("reason").isNull() || "NONE".equals(value.path("reason").asText())
                                    ? null : value.path("reason").asText(null),
                            null);
                    case "message_template_quality_update" -> templates.applyUpdate(
                            value.path("message_template_id").asText(), null, null,
                            value.path("new_quality_score").asText("UNKNOWN"));
                    default -> { }
                }
                for (JsonNode s : change.path("value").path("statuses")) {
                    String status = s.path("status").asText();
                    if (TRACKED.contains(status)) {
                        JsonNode err = s.path("errors").path(0);
                        repo.applyStatus(s.path("id").asText(), status,
                                err.isMissingNode() ? null : err.path("code").asText() + ": " + err.path("title").asText());
                    }
                }
                // ponytail: inbound patient messages are acknowledged but not stored — see V46 header.
                if (change.path("value").has("messages")) {
                    log.info("WhatsApp inbound message received and dropped (no inbox yet, NB-197)");
                }
            }
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

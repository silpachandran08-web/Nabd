package com.nabd.comms.sms;

import com.nabd.comms.inbox.InboxException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * COM-026. {@code POST /internal/v1/sms/messages}: Core asks for an SMS (service token + tenant, like the inbox API).
 * {@code POST /v1/webhooks/sms/{provider}}: the BSP's delivery reports, public, so the request must prove itself: HMAC-SHA256 of the
 * body with the shared secret ({@code X-Signature: sha256=<hex>}), or, for a BSP that cannot sign, the shared secret in
 * {@code X-Webhook-Secret} from an allow-listed IP. When an allow-list is set every report must come from it. Anything else is 401
 * BAD_SIGNATURE; no secret configured = reject all. The body is never logged.
 * COM-028: {@code POST /v1/webhooks/sms/{provider}/inbound}: inbound SMS (MO, e.g. STOP), same proof, persist-then-ack; SmsStopService acts on them.
 * shortcut: exact IPs, no CIDR, and the socket peer address (no X-Forwarded-For): put the allow-list on the ingress if a proxy fronts this.
 */
@RestController
@ConditionalOnProperty({"comms.field-key", "comms.sms.bsp.url"})
class SmsController {

    record SendBody(String to, String text, UUID patientRef, UUID requestId) {}

    private static final int MAX_BODY_BYTES = 262_144;

    private final SmsService sms;
    private final byte[] token;
    private final byte[] secret;
    private final Set<String> allowedIps;
    private final MeterRegistry meters;

    SmsController(SmsService sms, @Value("${comms.internal-token:}") String token, @Value("${comms.sms.webhook.secret:}") String secret,
                  @Value("${comms.sms.webhook.allowed-ips:}") String allowedIps, MeterRegistry meters) {
        this.sms = sms;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.allowedIps = Arrays.stream(allowedIps.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        this.meters = meters;
    }

    @PostMapping("/internal/v1/sms/messages")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SmsService.Sent send(@RequestHeader(value = "X-Internal-Token", required = false) String t, @RequestHeader("X-Tenant-Id") UUID tenant,
                         @RequestBody SendBody body) {
        if (token.length == 0 || t == null || !MessageDigest.isEqual(token, t.getBytes(StandardCharsets.UTF_8))) {
            throw new InboxException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return sms.send(tenant, new SmsService.SendRequest(body.to(), body.text(), body.patientRef(), body.requestId()));
    }

    /** Persist-then-ack (COM-026.3): verify, store, answer 200; the status is applied by SmsService.drainDlrs. */
    @PostMapping("/v1/webhooks/sms/{provider}")
    ResponseEntity<Void> deliveryReport(@PathVariable String provider, @RequestBody byte[] body, HttpServletRequest request,
                                        @RequestHeader(value = "X-Signature", required = false) String signature,
                                        @RequestHeader(value = "X-Webhook-Secret", required = false) String shared) {
        if (!sms.provider().name().equals(provider)) {
            return ResponseEntity.notFound().build();
        }
        if (body.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        if (!authentic(request.getRemoteAddr(), body, signature, shared)) {
            meters.counter("comms.sms.webhook.rejected", "reason", "bad_signature").increment();
            throw new InboxException(HttpStatus.UNAUTHORIZED, "BAD_SIGNATURE");
        }
        List<SmsProvider.Dlr> dlrs;
        try {
            dlrs = sms.provider().parseDlr(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build(); // authentic but not a report: a retry cannot fix it
        }
        sms.storeDlrs(dlrs);
        return ResponseEntity.ok().build(); // anything but 2xx makes the BSP retry
    }

    /** COM-028: same checks as the delivery reports; a replayed MO id stores nothing new (idempotent). */
    @PostMapping("/v1/webhooks/sms/{provider}/inbound")
    ResponseEntity<Void> inbound(@PathVariable String provider, @RequestBody byte[] body, HttpServletRequest request,
                                 @RequestHeader(value = "X-Signature", required = false) String signature,
                                 @RequestHeader(value = "X-Webhook-Secret", required = false) String shared) {
        if (!sms.provider().name().equals(provider)) {
            return ResponseEntity.notFound().build();
        }
        if (body.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).build();
        }
        if (!authentic(request.getRemoteAddr(), body, signature, shared)) {
            meters.counter("comms.sms.webhook.rejected", "reason", "bad_signature").increment();
            throw new InboxException(HttpStatus.UNAUTHORIZED, "BAD_SIGNATURE");
        }
        List<SmsProvider.Mo> mos;
        try {
            mos = sms.provider().parseMo(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        sms.storeMos(mos);
        return ResponseEntity.ok().build();
    }

    boolean authentic(String ip, byte[] body, String signature, String shared) {
        if (secret.length == 0 || (!allowedIps.isEmpty() && !allowedIps.contains(ip))) {
            return false;
        }
        if (signature != null && MessageDigest.isEqual(("sha256=" + hmacHex(body)).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        return !allowedIps.isEmpty() && shared != null && MessageDigest.isEqual(secret, shared.getBytes(StandardCharsets.UTF_8));
    }

    private String hmacHex(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @ExceptionHandler(InboxException.class)
    ResponseEntity<ProblemDetail> handle(InboxException e) {
        ProblemDetail p = ProblemDetail.forStatus(e.status());
        p.setTitle(e.code());
        p.setProperty("code", e.code());
        return ResponseEntity.status(e.status()).body(p);
    }
}

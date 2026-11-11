package com.nabd.hms.realtime;

import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.realtime.integration.WebPushClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * FEF-048: "patient ready" to every device the staff member subscribed. The payload is PHI-free by construction: a kind, the
 * token number and the screen to open; the service worker turns it into "Token 14 ready". The HTTP calls run outside any
 * transaction; each answer is recorded in its own tenant transaction (404/410 three times in a row deletes the subscription).
 */
@Component
public class WebPushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);
    static final int MAX_GONE = 3;

    private final PushSubscriptionRepository repo;
    private final WebPushClient client;
    private final FieldCipher cipher;
    private final TenantScope tenantScope;

    WebPushSender(PushSubscriptionRepository repo, WebPushClient client, FieldCipher cipher, TenantScope tenantScope) {
        this.repo = repo;
        this.client = client;
        this.cipher = cipher;
        this.tenantScope = tenantScope;
    }

    private record Device(UUID id, WebPushClient.Target target) {
    }

    /** Sends to each of the staff member's devices; returns how many push services accepted it. */
    public int patientReady(UUID tenantId, UUID staffId, int tokenNumber) {
        if (!client.configured()) return 0;
        List<Device> devices = tenantScope.run(tenantId, () -> repo.forStaff(tenantId, staffId).stream().map(r -> new Device(r.id(),
                new WebPushClient.Target(r.endpoint(), r.p256dh(), cipher.decrypt(r.authSecretEnc(),
                        FieldCipher.Aad.of(tenantId, PushSubscriptionService.TABLE, "auth_secret", r.id()))))).toList());
        byte[] payload = ("{\"kind\":\"patient_ready\",\"token\":" + tokenNumber + ",\"url\":\"/my-day\"}").getBytes(StandardCharsets.UTF_8);
        int accepted = 0;
        for (Device d : devices) {
            int status = client.send(d.target(), payload);
            if (status >= 200 && status < 300) {
                accepted++;
                tenantScope.run(tenantId, () -> repo.delivered(tenantId, d.id()));
            } else if (status == 404 || status == 410) {
                boolean deleted = tenantScope.run(tenantId, () -> repo.gone(tenantId, d.id(), MAX_GONE));
                log.info("web push subscription {} answered {}{}", d.id(), status, deleted ? ", deleted" : ""); // ids only
            } else {
                log.info("web push subscription {} answered {}", d.id(), status); // transient: kept, the next ready event tries again
            }
        }
        return accepted;
    }
}

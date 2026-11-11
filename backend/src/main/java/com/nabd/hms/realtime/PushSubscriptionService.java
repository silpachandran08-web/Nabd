package com.nabd.hms.realtime;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.FieldCipher;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.iam.api.ActorDirectory;
import com.nabd.hms.realtime.integration.WebPushClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * FEF-048: the signed-in staff member registers or removes this browser's Web Push subscription. Own rows only (staff id =
 * token subject). The server POSTs to the endpoint later, so it must be https on a known browser push service
 * (app.webpush.allowed-hosts): an arbitrary URL would let a user make Core call internal addresses.
 */
@Service
class PushSubscriptionService {

    static final String TABLE = "web_push_subscriptions";

    @io.swagger.v3.oas.annotations.media.Schema(name = "PushSubscriptionKeys")
    record Keys(@NotBlank @Size(max = 200) String p256dh, @NotBlank @Size(max = 100) String auth) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "PushSubscriptionRequest")
    record Register(@NotBlank @Size(max = 2048) String endpoint, @NotNull @Valid Keys keys, @Size(max = 80) String deviceLabel) {
    }

    private final PushSubscriptionRepository repo;
    private final TenantContext tenantContext;
    private final FieldCipher cipher;
    private final AuditService audit;
    private final ActorDirectory actors;
    private final List<String> allowedHosts;
    private final boolean allowHttp;

    PushSubscriptionService(PushSubscriptionRepository repo, TenantContext tenantContext, FieldCipher cipher, AuditService audit, ActorDirectory actors,
                            @Value("${app.webpush.allowed-hosts:fcm.googleapis.com,push.apple.com,push.services.mozilla.com,notify.windows.com}") String allowedHosts,
                            @Value("${app.webpush.allow-http:false}") boolean allowHttp) {
        this.repo = repo;
        this.tenantContext = tenantContext;
        this.cipher = cipher;
        this.audit = audit;
        this.actors = actors;
        this.allowedHosts = Arrays.stream(allowedHosts.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(s -> s.toLowerCase(Locale.ROOT)).toList();
        this.allowHttp = allowHttp;
    }

    /** Idempotent: the same endpoint again keeps its id and takes the new keys. */
    @Transactional
    public UUID register(UUID staffId, Register req) {
        String endpoint = validEndpoint(req == null ? null : req.endpoint());
        Keys k = req.keys();
        byte[] auth;
        try {
            if (k == null || k.p256dh() == null || k.auth() == null) throw new IllegalArgumentException("keys missing");
            WebPushClient.publicKey(WebPushClient.decode(k.p256dh()));
            auth = WebPushClient.decode(k.auth());
            if (auth.length != 16) throw new IllegalArgumentException("auth secret must be 16 bytes");
        } catch (IllegalArgumentException e) {
            throw invalid("The subscription keys are not a valid Web Push key pair.");
        }
        String label = req.deviceLabel() == null || req.deviceLabel().isBlank() ? null : req.deviceLabel().trim();
        if (label != null && label.length() > 80) throw invalid("The device label is longer than 80 characters.");
        UUID tenantId = tenantContext.current();
        repo.removeEndpointOfOthers(tenantId, staffId, endpoint);
        UUID fresh = UUID.randomUUID();
        UUID id = repo.insert(tenantId, fresh, staffId, endpoint, k.p256dh(), encrypt(tenantId, fresh, auth), label).orElse(null);
        if (id == null) {
            id = repo.idOf(tenantId, staffId, endpoint);
            repo.update(tenantId, id, k.p256dh(), encrypt(tenantId, id, auth), label);
        }
        record(tenantId, staffId, "push.subscription_registered", id);
        return id;
    }

    @Transactional
    public void delete(UUID staffId, UUID id) {
        UUID tenantId = tenantContext.current();
        if (repo.delete(tenantId, staffId, id) == 0) throw ApiException.notFound(); // another staff member's id is not found either
        record(tenantId, staffId, "push.subscription_removed", id);
    }

    private byte[] encrypt(UUID tenantId, UUID id, byte[] auth) {
        return cipher.encrypt(auth, FieldCipher.Aad.of(tenantId, TABLE, "auth_secret", id));
    }

    private void record(UUID tenantId, UUID staffId, String action, UUID id) {
        ActorDirectory.Actor a = actors.findActor(tenantId, staffId).orElseThrow(ApiException::notFound);
        audit.record(tenantId, "staff", staffId, a.name(), a.role(), null, action, "web_push_subscription", id, null, null);
    }

    private String validEndpoint(String endpoint) {
        URI u;
        try {
            u = endpoint == null || endpoint.length() > 2048 ? null : URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            u = null;
        }
        String host = u == null || u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        boolean scheme = u != null && ("https".equals(u.getScheme()) || (allowHttp && "http".equals(u.getScheme())));
        // The scheme's default port matches a bare host entry (suffix match); any other port must be allowed explicitly as
        // "host:port" (exact host), so an allowed push host cannot be aimed at another service on it (QA S22 N-4).
        int port = u == null ? -1 : u.getPort();
        boolean defaultPort = port == -1 || (port == 443 && "https".equals(u.getScheme())) || (port == 80 && "http".equals(u.getScheme()));
        boolean known = defaultPort
                ? allowedHosts.stream().anyMatch(h -> !h.contains(":") && (host.equals(h) || host.endsWith("." + h)))
                : allowedHosts.contains(host + ":" + port);
        if (!scheme || !known || u.getUserInfo() != null) throw invalid("The endpoint is not a supported browser push service.");
        return endpoint;
    }

    private static ApiException invalid(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-subscription", "Invalid subscription", detail, java.util.Map.of("code", "INVALID_SUBSCRIPTION"));
    }
}

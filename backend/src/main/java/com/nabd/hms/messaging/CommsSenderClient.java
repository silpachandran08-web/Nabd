package com.nabd.hms.messaging;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.messaging.api.SenderDirectory;
import com.nabd.hms.shared.integration.ResilientHttpClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * COM-022: GET {gateway}/internal/v1/senders/**. Read only, no transaction, never logs a body. shortcut: the X-Internal-Token service token
 * (app.comms.gateway.service-token) stands in for the mTLS aud=internal identity, like the gateway's inbox API (PLT-009).
 */
@Component
class CommsSenderClient implements SenderDirectory {

    private final RestClient http;
    private final String token;
    private final boolean configured;

    CommsSenderClient(CommsGatewayProperties props, ResilientHttpClient integration) {
        this.configured = props.baseUrl() != null && !props.baseUrl().isBlank();
        RestClient.Builder b = integration.builder(OtpGatewayClient.PROVIDER);
        if (configured) b.baseUrl(props.baseUrl());
        this.http = b.build();
        this.token = props.serviceToken() == null ? "" : props.serviceToken();
    }

    @Override
    public Health health(UUID tenantId) {
        return call(() -> http.get().uri("/internal/v1/senders/health").header("X-Tenant-Id", tenantId.toString()).header("X-Internal-Token", token)
                .retrieve().body(Health.class));
    }

    @Override
    public String quality(UUID tenantId) {
        Map<?, ?> r = call(() -> http.get().uri("/internal/v1/senders/quality").header("X-Tenant-Id", tenantId.toString()).header("X-Internal-Token", token)
                .retrieve().body(Map.class));
        return r == null ? null : (String) r.get("quality");
    }

    @Override
    public FleetPage fleet(String rating, String region, String cursor, int limit) {
        return call(() -> http.get().uri(u -> u.path("/internal/v1/senders").queryParamIfPresent("rating", java.util.Optional.ofNullable(rating))
                        .queryParamIfPresent("region", java.util.Optional.ofNullable(region)).queryParamIfPresent("cursor", java.util.Optional.ofNullable(cursor))
                        .queryParam("limit", limit).build()).header("X-Internal-Token", token).retrieve().body(FleetPage.class));
    }

    private <T> T call(Supplier<T> request) {
        if (!configured || token.isBlank()) {
            throw unavailable();
        }
        try {
            return request.get();
        } catch (RestClientException | com.nabd.hms.shared.integration.CircuitOpenException e) {
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "messaging-unavailable", "WhatsApp status unavailable",
                "The WhatsApp status could not be loaded right now. Try again in a minute.");
    }
}

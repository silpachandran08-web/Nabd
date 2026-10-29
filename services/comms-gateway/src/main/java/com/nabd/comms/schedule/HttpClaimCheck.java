package com.nabd.comms.schedule;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

/**
 * Claim check over Core's GET /internal/v1/notification-requests/{id}. 410 (appointment cancelled or moved) and 404 mean the
 * message must not go out. shortcut: shared service token like Core's controller, swap for the mTLS identity (PLT-009).
 */
@Component
@ConditionalOnProperty("comms.core.base-url")
class HttpClaimCheck implements ClaimCheck {

    private final RestClient http;
    private final String token;

    HttpClaimCheck(@Value("${comms.core.base-url}") String baseUrl, @Value("${comms.core.token:}") String token) {
        var f = new JdkClientHttpRequestFactory();
        f.setReadTimeout(Duration.ofSeconds(3));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
        this.token = token;
    }

    @Override
    public Verdict check(UUID tenantId, UUID requestId) {
        try {
            http.get().uri("/internal/v1/notification-requests/{id}", requestId).header("X-Internal-Token", token).retrieve().toBodilessEntity();
            return Verdict.VALID;
        } catch (HttpClientErrorException.Gone | HttpClientErrorException.NotFound e) {
            return Verdict.GONE;
        }
    }
}

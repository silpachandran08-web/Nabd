package com.nabd.hms.identity.integration;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.shared.integration.CircuitOpenException;
import com.nabd.hms.shared.integration.ResilientHttpClient;
import com.nabd.hms.shared.secrets.SecretResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.function.Function;

/**
 * IDC-016: the ABDM gateway client. Every call goes through {@link ResilientHttpClient} (provider "abdm": connect/read
 * timeouts, circuit breaker, refused inside a DB transaction). The session token comes from the bridge credentials in the
 * regional secret store; a 401 drops the token and retries once. Requests carry REQUEST-ID, TIMESTAMP and X-CM-ID as the
 * v3 gateway expects. shortcut: endpoints and response shapes follow the published v3 session contract and a recorded
 * fixture; the HFR/HPR lookup URLs stay blank in config until the sandbox contract is confirmed (DEP-05).
 */
@Component
class HttpAbdmGateway implements AbdmGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpAbdmGateway.class);

    private final AbdmProperties props;
    private final RestClient http;
    private final AbdmSessionTokens tokens;
    private final Clock clock;

    @Autowired
    HttpAbdmGateway(AbdmProperties props, ResilientHttpClient integration, SecretResolver secrets, Clock clock) {
        this(props, integration, clock, secrets::platform);
    }

    /** Test seam: credentials come from the function instead of the secret store. */
    HttpAbdmGateway(AbdmProperties props, ResilientHttpClient integration, Clock clock, Function<String, Optional<String>> secret) {
        this.props = props;
        this.clock = clock;
        this.http = integration.builder("abdm").build();
        this.tokens = new AbdmSessionTokens(() -> fetchSession(secret), clock, props.refreshMargin());
    }

    @Override
    public boolean facilityExists(String hfrId) {
        return exists(props.hfrLookup(), hfrId);
    }

    @Override
    public boolean professionalExists(String hprId) {
        return exists(props.hprLookup(), hprId);
    }

    private boolean exists(String template, String id) {
        if (props.baseUrl().isBlank() || template.isBlank()) {
            throw unavailable("abdm-not-configured");
        }
        String url = props.baseUrl() + template.replace("{id}", java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8));
        for (int attempt = 0; ; attempt++) {
            try {
                http.get().uri(url).headers(this::gatewayHeaders).header("Authorization", "Bearer " + tokens.current())
                        .retrieve().toBodilessEntity();
                return true;
            } catch (RestClientResponseException e) {
                int s = e.getStatusCode().value();
                if (s == 404) {
                    return false;
                }
                if (s == 401 && attempt == 0) {
                    tokens.invalidate(); // expired or revoked early: one fresh session, then give up
                    continue;
                }
                throw unavailable("abdm-unavailable");
            } catch (ApiException e) {
                throw e;
            } catch (CircuitOpenException | RestClientException e) {
                throw unavailable("abdm-unavailable");
            }
        }
    }

    private AbdmSessionTokens.Token fetchSession(Function<String, Optional<String>> secret) {
        String id;
        String pw;
        try {
            id = secret.apply("abdm-client-id").orElse("");
            pw = secret.apply("abdm-client-secret").orElse("");
        } catch (IllegalStateException e) { // no secret store wired in this installation
            throw unavailable("abdm-not-configured");
        }
        if (props.baseUrl().isBlank() || id.isBlank() || pw.isBlank()) {
            throw unavailable("abdm-not-configured");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = http.post().uri(props.baseUrl() + props.sessionPath()).headers(this::gatewayHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("clientId", id, "clientSecret", pw, "grantType", "client_credentials"))
                    .retrieve().body(Map.class);
            Object t = r == null ? null : r.get("accessToken");
            if (!(t instanceof String s) || s.isBlank()) {
                throw unavailable("abdm-unavailable");
            }
            Object exp = r.get("expiresIn");
            Duration life = exp instanceof Number n && n.longValue() > 0 ? Duration.ofSeconds(n.longValue()) : props.defaultTokenLifetime();
            return new AbdmSessionTokens.Token(s, life);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("abdm session request refused with status {}", status);
            throw unavailable("abdm-unavailable");
        } catch (CircuitOpenException | RestClientException e) {
            throw unavailable("abdm-unavailable");
        }
    }

    private void gatewayHeaders(org.springframework.http.HttpHeaders h) {
        h.set("REQUEST-ID", UUID.randomUUID().toString());
        h.set("TIMESTAMP", clock.instant().toString());
        h.set("X-CM-ID", props.cmId());
    }

    private static ApiException unavailable(String code) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, "ABDM unavailable", "The ABDM service cannot be reached right now; try again later.");
    }
}

package com.nabd.hms.shared.integration;

import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OPS-012: the one way to build an outbound HTTP client. Every request made through a builder from here
 * (1) refuses to run inside an open DB transaction ({@link ExternalCallGuard}), (2) uses the provider's connect /
 * read timeouts, and (3) goes through that provider's {@link CircuitBreaker}.
 */
@Component
public class ResilientHttpClient {

    private final IntegrationProperties props;
    private final ExternalCallGuard guard;
    private final Clock clock;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public ResilientHttpClient(IntegrationProperties props, ExternalCallGuard guard, Clock clock) {
        this.props = props;
        this.guard = guard;
        this.clock = clock;
    }

    public RestClient.Builder builder(String provider) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(props.connectTimeoutMs(provider));
        timeouts.setReadTimeout(props.readTimeoutMs(provider));
        CircuitBreaker breaker = breaker(provider);
        return RestClient.builder().requestFactory(timeouts).requestInterceptor((request, body, execution) -> {
            guard.assertNoTransaction(provider);
            breaker.acquire();
            ClientHttpResponse response;
            try {
                response = execution.execute(request, body);
            } catch (IOException | RuntimeException e) {
                breaker.failure();
                throw e;
            }
            int status = response.getStatusCode().value();
            if (status == 429 || status >= 500) {
                breaker.failure();
            } else {
                breaker.success();
            }
            return response;
        });
    }

    public boolean isOpen(String provider) {
        return breaker(provider).isOpen();
    }

    private CircuitBreaker breaker(String provider) {
        return breakers.computeIfAbsent(provider, p -> new CircuitBreaker(p, props.circuitFailureThreshold(),
                props.circuitOpenSeconds(), clock));
    }
}

package com.nabd.edge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** The only outbound HTTP of the Edge: Core (/internal/v1) and the Comms Gateway. Hard timeouts, no retries here. */
final class Http {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
    private final Duration timeout;
    private final String token;

    Http(int timeoutMs, String token) {
        this.timeout = Duration.ofMillis(timeoutMs);
        this.token = token;
    }

    String get(String url) {
        return send(builder(url).GET().build());
    }

    String post(String url, String json) {
        return send(builder(url).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build());
    }

    private HttpRequest.Builder builder(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
        if (!token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    private String send(HttpRequest req) {
        try {
            HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new IllegalStateException("upstream status " + r.statusCode());
            }
            return r.body();
        } catch (IOException e) {
            throw new IllegalStateException("upstream unreachable", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}

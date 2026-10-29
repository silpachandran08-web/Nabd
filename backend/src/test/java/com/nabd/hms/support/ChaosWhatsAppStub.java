package com.nabd.hms.support;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OPS-045: an in-JVM stand-in for Meta's Cloud API with fault modes. It plays the part of the COM-010 provider stub for the Core
 * chaos suite (that stub lives in the Comms Gateway and is not in this tree). A send is counted by its first body parameter only
 * when the stub answers 2xx, and the decision is taken on arrival, so a count above 1 is a real duplicate send, not an artefact of
 * a fault flipping mid-request. Latency is applied before the answer; a caller that times out has still been counted.
 */
public final class ChaosWhatsAppStub {

    public enum Mode { OK, DOWN, STORM, FLAKY }

    public static final ChaosWhatsAppStub INSTANCE = start();

    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    public volatile Mode mode = Mode.OK;
    public final AtomicLong latencyMs = new AtomicLong();
    public final AtomicInteger requests = new AtomicInteger();
    public final AtomicInteger refused = new AtomicInteger();
    private final Map<String, AtomicInteger> sendsByMarker = new ConcurrentHashMap<>();

    private ChaosWhatsAppStub(HttpServer server) {
        this.server = server;
    }

    private static ChaosWhatsAppStub start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ChaosWhatsAppStub stub = new ChaosWhatsAppStub(s);
            s.createContext("/", stub::handle);
            s.setExecutor(Executors.newFixedThreadPool(32));
            s.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException("could not start the chaos WhatsApp stub", e);
        }
    }

    private void handle(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        int n = requests.incrementAndGet();
        byte[] in = ex.getRequestBody().readAllBytes();
        int status = switch (mode) {
            case OK -> 200;
            case DOWN -> 503;
            case STORM -> 429;
            case FLAKY -> n % 3 == 0 ? 500 : 200;
        };
        if (status == 200) {
            JsonNode body = json.readTree(in.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : in);
            String marker = body.path("template").path("components").path(0).path("parameters").path(0).path("text").asString("");
            sendsByMarker.computeIfAbsent(marker, k -> new AtomicInteger()).incrementAndGet();
        } else {
            refused.incrementAndGet();
        }
        try {
            Thread.sleep(latencyMs.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] out = (status == 200 ? "{\"messages\":[{\"id\":\"wamid.chaos-" + UUID.randomUUID() + "\"}]}" : "{\"error\":{\"message\":\"chaos\"}}")
                .getBytes(StandardCharsets.UTF_8);
        try {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            if (status == 429) {
                ex.getResponseHeaders().add("Retry-After", "1");
            }
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
        } catch (IOException clientGone) {
            // the caller timed out first; the send stays counted
        }
        ex.close();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        mode = Mode.OK;
        latencyMs.set(0);
        requests.set(0);
        refused.set(0);
        sendsByMarker.clear();
    }

    /** marker -> times the provider accepted it, for markers starting with the prefix. */
    public Map<String, Integer> sends(String prefix) {
        Map<String, Integer> out = new HashMap<>();
        sendsByMarker.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                out.put(k, v.get());
            }
        });
        return out;
    }
}

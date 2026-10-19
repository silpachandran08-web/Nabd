package com.nabd.hms.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OPS-014/OPS-012: an in-JVM stand-in for Meta's Cloud API (POST /{phone-number-id}/messages) with adjustable
 * latency. It counts every template send by its first body parameter (tests put a unique marker there), so a
 * duplicate delivery shows up as a count above 1. One instance per JVM: the test context's
 * app.whatsapp.api-base-url points at it, so every test class using it shares one Spring context.
 */
public final class SlowWhatsAppStub {

    public static final SlowWhatsAppStub INSTANCE = start();

    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    public final Map<String, AtomicInteger> sendsByMarker = new ConcurrentHashMap<>();
    public final AtomicLong latencyMs = new AtomicLong(0);
    public final AtomicInteger requests = new AtomicInteger();

    private SlowWhatsAppStub(HttpServer server) {
        this.server = server;
    }

    private static SlowWhatsAppStub start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SlowWhatsAppStub stub = new SlowWhatsAppStub(s);
            s.createContext("/", ex -> {
                stub.requests.incrementAndGet();
                byte[] in = ex.getRequestBody().readAllBytes();
                try {
                    Thread.sleep(stub.latencyMs.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                JsonNode body = stub.json.readTree(in.length == 0 ? "{}".getBytes() : in);
                String marker = body.path("template").path("components").path(0).path("parameters").path(0).path("text").asText("");
                stub.sendsByMarker.computeIfAbsent(marker, k -> new AtomicInteger()).incrementAndGet();
                byte[] out = ("{\"messages\":[{\"id\":\"wamid.stub-" + UUID.randomUUID() + "\"}]}").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                try {
                    ex.sendResponseHeaders(200, out.length);
                    ex.getResponseBody().write(out);
                } catch (IOException clientGone) {
                    // the caller timed out first; the send still "happened" at the provider and stays counted
                }
                ex.close();
            });
            s.setExecutor(Executors.newFixedThreadPool(64));
            s.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException("could not start the WhatsApp stub", e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset(long latency) {
        sendsByMarker.clear();
        requests.set(0);
        latencyMs.set(latency);
    }

    public int maxSendsPerMarker() {
        return sendsByMarker.values().stream().mapToInt(AtomicInteger::get).max().orElse(0);
    }
}

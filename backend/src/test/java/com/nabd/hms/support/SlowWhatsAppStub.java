package com.nabd.hms.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
    public final AtomicInteger status = new AtomicInteger(200);
    /** COM-003: templates the stub "holds", keyed metaName|language (registered on arrival of a 200-mode create). */
    public final Map<String, String> templates = new ConcurrentHashMap<>();
    public final AtomicInteger templateCreates = new AtomicInteger();

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
                if (ex.getRequestURI().getPath().endsWith("/message_templates")) {
                    stub.templateCall(ex, in);
                    return;
                }
                if (stub.status.get() == 200) { // counted on arrival: the provider has the send even if the caller times out
                    JsonNode body = stub.json.readTree(in.length == 0 ? "{}".getBytes() : in);
                    String marker = body.path("template").path("components").path(0).path("parameters").path(0).path("text").asString("");
                    stub.sendsByMarker.computeIfAbsent(marker, k -> new AtomicInteger()).incrementAndGet();
                }
                try {
                    Thread.sleep(stub.latencyMs.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (stub.status.get() != 200) {
                    byte[] err = "{\"error\":{\"message\":\"stub failure\"}}".getBytes(StandardCharsets.UTF_8);
                    try {
                        ex.sendResponseHeaders(stub.status.get(), err.length);
                        ex.getResponseBody().write(err);
                    } catch (IOException clientGone) {
                        // caller gave up
                    }
                    ex.close();
                    return;
                }
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

    private void templateCall(com.sun.net.httpserver.HttpExchange ex, byte[] in) throws IOException {
        String out;
        if ("POST".equals(ex.getRequestMethod())) {
            JsonNode body = json.readTree(in);
            String key = body.path("name").asString("") + "|" + body.path("language").asString("");
            String id = "tpl-" + UUID.randomUUID();
            if (status.get() == 200) { // like sends: counted on arrival, the provider has it even if the caller times out
                templates.put(key, id);
                templateCreates.incrementAndGet();
            }
            out = "{\"id\":\"" + id + "\",\"status\":\"PENDING\"}";
        } else {
            String name = ex.getRequestURI().getQuery().replaceAll(".*name=([^&]*).*", "$1");
            StringBuilder data = new StringBuilder();
            templates.forEach((k, id) -> {
                if (k.startsWith(name + "|")) {
                    data.append(data.length() > 0 ? "," : "").append("{\"id\":\"").append(id).append("\",\"status\":\"APPROVED\",\"language\":\"")
                            .append(k.substring(k.indexOf('|') + 1)).append("\"}");
                }
            });
            out = "{\"data\":[" + data + "]}";
        }
        try {
            Thread.sleep(latencyMs.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes = (status.get() == 200 ? out : "{\"error\":{\"message\":\"stub failure\"}}").getBytes(StandardCharsets.UTF_8);
        try {
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), bytes.length);
            ex.getResponseBody().write(bytes);
        } catch (IOException clientGone) {
            // the caller timed out first
        }
        ex.close();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset(long latency) {
        sendsByMarker.clear();
        templates.clear();
        templateCreates.set(0);
        requests.set(0);
        status.set(200);
        latencyMs.set(latency);
    }

    /** Sends whose marker starts with {@code prefix} (a slow request from an earlier test can land after reset). */
    public Map<String, Integer> sendsWithPrefix(String prefix) {
        Map<String, Integer> out = new java.util.HashMap<>();
        sendsByMarker.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                out.put(k, v.get());
            }
        });
        return out;
    }
}

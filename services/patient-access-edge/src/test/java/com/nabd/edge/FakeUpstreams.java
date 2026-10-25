package com.nabd.edge;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** In-process stand-ins for Core (/internal/v1 availability, ACC-038) and the Comms Gateway (message fetch, send). */
final class FakeUpstreams {

    final AtomicInteger coreCalls = new AtomicInteger();
    final Map<String, String> slotsByDoctor = new ConcurrentHashMap<>();   // doctorId -> JSON array of ISO instants
    final Map<String, String> messages = new ConcurrentHashMap<>();         // messageId -> JSON body
    final List<String> sent = new CopyOnWriteArrayList<>();
    volatile int coreDelayMs = 0;
    final HttpServer core;
    final HttpServer comms;

    FakeUpstreams() {
        try {
            core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            core.createContext("/internal/v1/tenants/", ex -> {
                coreCalls.incrementAndGet();
                try {
                    Thread.sleep(coreDelayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                String q = ex.getRequestURI().getQuery();
                String doctor = q.replaceAll(".*doctorId=([^&]+).*", "$1");
                String slots = slotsByDoctor.getOrDefault(doctor, "[]");
                reply(ex, 200, "{\"doctors\":[{\"doctorId\":\"" + doctor + "\",\"sessions\":[{\"id\":\"s1\",\"mode\":\"slots\",\"slots\":" + slots + "}]}],\"v\":1}");
            });
            comms = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            comms.createContext("/internal/v1/tenants/", ex -> {
                String path = ex.getRequestURI().getPath();
                if ("POST".equals(ex.getRequestMethod())) {
                    sent.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    reply(ex, 202, "{}");
                    return;
                }
                String body = messages.get(path.substring(path.lastIndexOf('/') + 1));
                reply(ex, body == null ? 404 : 200, body == null ? "{}" : body);
            });
            core.start();
            comms.start();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    String coreUrl() {
        return "http://127.0.0.1:" + core.getAddress().getPort();
    }

    String commsUrl() {
        return "http://127.0.0.1:" + comms.getAddress().getPort();
    }

    /** Registers an inbound message; text is free text (it must never reach logs, DB or Valkey keys). */
    void inbound(String messageId, String contactHash, String text, String buttonPayload) {
        messages.put(messageId, "{\"kind\":\"" + (buttonPayload == null ? "text" : "button") + "\",\"text\":\"" + text + "\",\"contactHash\":\""
                + contactHash + "\"" + (buttonPayload == null ? "" : ",\"buttonPayload\":\"" + buttonPayload + "\"") + "}");
    }

    static String id() {
        return UUID.randomUUID().toString();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }
}

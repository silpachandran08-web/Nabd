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
    /** IDC-005: the notice version Core reports, the status it answers a consent relay with, and the relayed bodies. */
    volatile String noticeVersion = "v1";
    volatile int consentStatus = 201;
    final List<String> relayed = new CopyOnWriteArrayList<>();
    /** ACC-033: the context Core reports, the queued answers to POST /check-ins (status, body; default 201 token 7 position 2) and the POSTs seen as "key body". */
    volatile String checkInContext = "{\"facilityCode\":\"ABCD2345\",\"geofenceRequired\":false,\"walkinAllowed\":false,\"candidates\":[]}";
    final java.util.concurrent.ConcurrentLinkedQueue<Object[]> checkInReplies = new java.util.concurrent.ConcurrentLinkedQueue<>();
    final List<String> checkIns = new CopyOnWriteArrayList<>();
    /** ACC-040/042 fakes. profiles: tenant id -> booking-profile JSON (default: one clinic, one doctor). households: phone hash -> member JSON objects. */
    final Map<String, String> profiles = new ConcurrentHashMap<>();
    final Map<String, List<String>> households = new ConcurrentHashMap<>();
    final Map<String, String> rawSessions = new ConcurrentHashMap<>();  // doctorId -> JSON array of sessions (with "date")
    final List<String> corePaths = new CopyOnWriteArrayList<>();         // every path Core was asked for
    final List<String> bookCalls = new CopyOnWriteArrayList<>();         // "<Idempotency-Key>|<body>"
    final List<String> dependantCalls = new CopyOnWriteArrayList<>();    // "<Idempotency-Key>|<hash>|<body>"
    /** confirmed | requested | taken | drop_once (commit, then lose the answer once) | drop_always (never answer) */
    volatile String bookMode = "confirmed";
    volatile int dependantStatus = 201;
    final Map<String, String> bookResults = new ConcurrentHashMap<>();   // Idempotency-Key -> stored answer
    /** ACC-041/044: a test may script Core. (METHOD + " " + path?query, body) -> {status, json}, or null to fall through. */
    volatile java.util.function.BiFunction<String, String, String[]> coreHook;
    final List<String> coreLog = new CopyOnWriteArrayList<>();
    final HttpServer core;
    final HttpServer comms;

    FakeUpstreams() {
        try {
            core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            core.createContext("/internal/v1/tenants/", ex -> {
                String path0 = ex.getRequestURI().getPath();
                var hook = coreHook;
                if (hook != null) {
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String line = ex.getRequestMethod() + " " + path0.replaceFirst("^/internal/v1/tenants/[^/]+", "") + (ex.getRequestURI().getQuery() == null ? "" : "?" + ex.getRequestURI().getQuery());
                    coreLog.add(line + " " + body + " key=" + ex.getRequestHeaders().getFirst("Idempotency-Key"));
                    String[] scripted = hook.apply(line, body);
                    if (scripted != null) {
                        reply(ex, Integer.parseInt(scripted[0]), scripted[1]);
                        return;
                    }
                }
                if (path0.endsWith("/privacy-notice/current")) {
                    reply(ex, 200, "{\"version\":\"" + noticeVersion + "\",\"language\":\"x\",\"path\":\"/v1/public/notices/clinic/" + noticeVersion + "?lang=x\"}");
                    return;
                }
                if (path0.endsWith("/check-ins/context")) {
                    reply(ex, 200, checkInContext);
                    return;
                }
                if (path0.endsWith("/check-ins")) {
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    checkIns.add(ex.getRequestHeaders().getFirst("Idempotency-Key") + " " + body);
                    Object[] r = checkInReplies.poll();
                    reply(ex, r == null ? 201 : (Integer) r[0], r == null ? "{\"queueEntryId\":\"e\",\"token\":7,\"position\":2}" : (String) r[1]);
                    return;
                }
                if (path0.endsWith("/patients/consents")) {
                    relayed.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    reply(ex, consentStatus, consentStatus == 201 ? "{\"consentIds\":[]}" : "{}");
                    return;
                }
                corePaths.add(path0);
                String[] seg = path0.split("/");
                String tenant = seg.length > 4 ? seg[4] : "";
                if (path0.endsWith("/booking-profile")) {
                    reply(ex, 200, profiles.getOrDefault(tenant, profiles.getOrDefault("*",
                            "{\"clinicName\":\"Test Clinic\",\"timeZone\":\"Asia/Kolkata\",\"doctors\":[]}")));
                    return;
                }
                if (path0.contains("/households/")) {
                    String hash = seg[6];
                    if ("POST".equals(ex.getRequestMethod())) {
                        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        dependantCalls.add(ex.getRequestHeaders().getFirst("Idempotency-Key") + "|" + hash + "|" + body);
                        if (dependantStatus == 400) {
                            reply(ex, 400, "{\"code\":\"NOT_A_MINOR\"}");
                        } else {
                            String ref = "child-ref-" + dependantCalls.size();
                            String first = body.replaceAll(".*\"firstName\":\"([^\"]+)\".*", "$1");
                            households.computeIfAbsent(hash, h -> new CopyOnWriteArrayList<>()).add(member(ref, first, 3, "dependent"));
                            reply(ex, 201, "{\"patientRef\":\"" + ref + "\"}");
                        }
                        return;
                    }
                    List<String> m = households.get(hash);
                    reply(ex, m == null || m.isEmpty() ? 404 : 200, m == null || m.isEmpty() ? "{}" : "{\"members\":[" + String.join(",", m) + "]}");
                    return;
                }
                if (path0.endsWith("/booking-requests")) {
                    String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    bookCalls.add(key + "|" + body);
                    if ("drop_always".equals(bookMode)) {
                        ex.close();
                        return;
                    }
                    String stored = bookResults.get(key);
                    if (stored == null && "taken".equals(bookMode)) {
                        reply(ex, 409, "{\"code\":\"SLOT_TAKEN\"}");
                        return;
                    }
                    if (stored == null) {
                        stored = "requested".equals(bookMode)
                                ? "{\"appointmentId\":\"" + UUID.randomUUID() + "\",\"status\":\"REQUESTED\",\"slaDueAt\":\"" + java.time.Instant.now().plusSeconds(600) + "\"}"
                                : "{\"appointmentId\":\"" + UUID.randomUUID() + "\",\"status\":\"CONFIRMED\"}";
                        bookResults.put(key, stored);
                        if ("drop_once".equals(bookMode)) {
                            bookMode = "confirmed";
                            ex.close(); // committed, answer lost
                            return;
                        }
                    }
                    reply(ex, 201, stored);
                    return;
                }
                coreCalls.incrementAndGet();
                try {
                    Thread.sleep(coreDelayMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                String q = ex.getRequestURI().getQuery();
                String doctor = q.replaceAll(".*doctorId=([^&]+).*", "$1");
                if (rawSessions.containsKey(doctor)) {
                    String from = q.replaceAll(".*from=([^&]+).*", "$1"), to = q.replaceAll(".*to=([^&]+).*", "$1");
                    var sessions = new tools.jackson.databind.ObjectMapper().readTree(rawSessions.get(doctor));
                    var kept = new StringBuilder();
                    for (var sn : sessions) {
                        String d = sn.path("date").asString("");
                        if (d.compareTo(from) >= 0 && d.compareTo(to) <= 0) {
                            kept.append(kept.length() == 0 ? "" : ",").append(sn);
                        }
                    }
                    reply(ex, 200, "{\"doctors\":[{\"doctorId\":\"" + doctor + "\",\"sessions\":[" + kept + "]}],\"v\":1}");
                    return;
                }
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

    /** ACC-033: a shared location as the Comms Gateway is assumed to deliver it: kind location, text "lat,lng,accuracyM". */
    void location(String messageId, String contactHash, String latLngAcc) {
        messages.put(messageId, "{\"kind\":\"location\",\"text\":\"" + latLngAcc + "\",\"contactHash\":\"" + contactHash + "\"}");
    }

    static String member(String ref, String first, int years, String relation) {
        return "{\"patientRef\":\"" + ref + "\",\"firstName\":\"" + first + "\",\"age\":\"x\",\"years\":" + years + ",\"relation\":\"" + relation + "\",\"isGuardian\":false}";
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

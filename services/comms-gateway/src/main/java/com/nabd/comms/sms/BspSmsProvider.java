package com.nabd.comms.sms;

import com.nabd.comms.provider.Provider;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * COM-026.1: the BSP adapter. POST {base-url}/sms with the DLT fields; 2xx {"id"} = accepted, 429 = rate limited, 5xx, I/O or an unreadable answer = transient,
 * any other 4xx = rejected (dead letter). The text is never logged.
 * shortcut: a neutral JSON contract (field names below); map them to the BSP procurement picks when its account exists (external).
 */
public class BspSmsProvider implements SmsProvider {

    static final Set<String> DELIVERED = Set.of("DELIVRD", "DELIVERED");
    static final Set<String> FAILED = Set.of("UNDELIV", "UNDELIVERED", "FAILED", "REJECTD", "REJECTED", "EXPIRED", "DND");

    private final String name;
    private final RestClient http;
    private final String apiKey;
    private final ObjectMapper json;

    public BspSmsProvider(String name, String baseUrl, String apiKey, ObjectMapper json) {
        var f = new JdkClientHttpRequestFactory();
        f.setReadTimeout(Duration.ofSeconds(5)); // OTP p95 < 10 s to handset: never wait longer than the BSP SLA
        this.name = name;
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
        this.apiKey = apiKey;
        this.json = json;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Provider.SendResult send(SmsRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reference", r.messageId().toString());
        body.put("to", r.to());
        body.put("sender", r.header());
        body.put("dltEntityId", r.dltEntityId());
        body.put("dltTemplateId", r.dltTemplateId());
        body.put("text", r.text());
        try {
            JsonNode res = http.post().uri("/sms").header("Authorization", "Bearer " + apiKey).contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body)).retrieve().body(JsonNode.class);
            String id = res == null ? null : res.path("id").asString(null);
            return id == null || id.isBlank() ? new Provider.Transient("no_id") : new Provider.Accepted(id);
        } catch (HttpClientErrorException.TooManyRequests e) {
            return new Provider.RateLimited("429");
        } catch (HttpClientErrorException e) {
            return new Provider.Rejected("http_" + e.getStatusCode().value());
        } catch (HttpServerErrorException e) {
            return new Provider.Transient("http_" + e.getStatusCode().value());
        } catch (RestClientException e) { // I/O, timeout or an unreadable answer: the outcome is unknown, never left in 'sending'
            return new Provider.Transient("io");
        }
    }

    /** One report or an array of reports: {"id", "status", "errorCode"?}. Intermediate states (SENT, ACCEPTED ...) are dropped. */
    @Override
    public List<Dlr> parseDlr(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not json");
        }
        List<Dlr> out = new ArrayList<>();
        for (JsonNode n : root.isArray() ? root : List.of(root)) {
            String id = n.path("id").asString(""), st = n.path("status").asString("").toUpperCase();
            if (id.isBlank() || id.length() > 100) {
                throw new IllegalArgumentException("no id");
            }
            String status = DELIVERED.contains(st) ? "delivered" : FAILED.contains(st) ? "failed" : null;
            if (status != null) {
                String err = n.path("errorCode").asString(null);
                out.add(new Dlr(id, status, err == null ? null : err.substring(0, Math.min(err.length(), 40))));
            }
        }
        return out;
    }

    /** COM-028: one MO or an array: {"id", "from", "text"}. from may be 10 digits or 91 + 10 digits, with or without '+'. */
    @Override
    public List<Mo> parseMo(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not json");
        }
        List<Mo> out = new ArrayList<>();
        for (JsonNode n : root.isArray() ? root : List.of(root)) {
            String id = n.path("id").asString(""), from = n.path("from").asString("").replaceAll("\\D", "");
            if (id.isBlank() || id.length() > 100 || from.isEmpty() || from.length() > 15) {
                throw new IllegalArgumentException("no id or sender");
            }
            out.add(new Mo(id, "+" + (from.length() == 10 ? "91" + from : from), n.path("text").asString("")));
        }
        return out;
    }

    /**
     * COM-028.2: POST {base-url}/dnd {"to"} -> {"dnd": true|false}. The number goes in the body, never the URL.
     * shortcut: any NCPR registration counts as DND (no per-category preferences); map to the BSP's scrub API when its account exists (external).
     */
    @Override
    public boolean isDnd(String to) {
        JsonNode res = http.post().uri("/dnd").header("Authorization", "Bearer " + apiKey).contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(Map.of("to", to))).retrieve().body(JsonNode.class);
        if (res == null || !res.path("dnd").isBoolean()) {
            throw new IllegalStateException("no dnd answer");
        }
        return res.path("dnd").asBoolean();
    }
}

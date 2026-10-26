package com.nabd.edge;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Comms Gateway API: message content is fetched on demand (events carry ids only) and never stored by the Edge. */
@Component
public class CommsClient {

    /** In-memory only. buttonPayload is the signed token (ButtonTokens), null for free text. */
    public record Inbound(String kind, String text, String buttonPayload, String contactHash) {
    }

    private final Http http;
    private final String base;
    private final ObjectMapper json;

    CommsClient(EdgeProperties p, ObjectMapper json) {
        this.http = new Http(p.coreTimeoutMs(), p.commsToken());
        this.base = p.commsUrl();
        this.json = json;
    }

    public Inbound fetch(UUID tenantId, String messageId) {
        JsonNode n = json.readTree(http.get(base + "/internal/v1/tenants/" + tenantId + "/messages/" + messageId));
        String button = n.path("buttonPayload").asString("");
        return new Inbound(n.path("kind").asString("text"), n.path("text").asString(""), button.isEmpty() ? null : button,
                n.path("contactHash").asString(""));
    }

    public void send(UUID tenantId, String channel, String contactHash, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channel", channel);
        m.put("contactHash", contactHash);
        m.put("text", text);
        http.post(base + "/internal/v1/tenants/" + tenantId + "/messages", json.writeValueAsString(m));
    }
}

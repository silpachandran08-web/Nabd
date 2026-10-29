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

    /** An interactive reply button: id is the signed payload that comes back in Inbound.buttonPayload. */
    public record SignedButton(String id, String title) {
    }

    public void send(UUID tenantId, String channel, String contactHash, String text) {
        send(tenantId, channel, contactHash, text, java.util.List.of());
    }

    public void send(UUID tenantId, String channel, String contactHash, String text, java.util.List<SignedButton> buttons) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channel", channel);
        m.put("contactHash", contactHash);
        m.put("text", text);
        if (!buttons.isEmpty()) {
            // WhatsApp reply buttons are at most 3; more options go out as a list message (max 10 rows), same id/title pairs
            m.put(buttons.size() > 3 ? "rows" : "buttons", buttons.stream().map(b -> Map.of("id", b.id(), "title", b.title())).toList());
        }
        http.post(base + "/internal/v1/tenants/" + tenantId + "/messages", json.writeValueAsString(m));
    }

    /** ACC-043 dependency: the verified WhatsApp number of a contact, for creating the patient (the Edge never stores it). Comms must serve
     * GET /internal/v1/tenants/{tid}/contacts/{contactHash}/phone -> {"phone":"+91..."}; null when it does not know the contact. */
    public String verifiedPhone(UUID tenantId, String contactHash) {
        try {
            String p = json.readTree(http.get(base + "/internal/v1/tenants/" + tenantId + "/contacts/" + contactHash + "/phone")).path("phone").asString("");
            return p.isEmpty() ? null : p;
        } catch (Http.UpstreamStatusException e) {
            if (e.status == 404) {
                return null;
            }
            throw e;
        }
    }
}

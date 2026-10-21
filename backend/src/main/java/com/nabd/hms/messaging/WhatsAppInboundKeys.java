package com.nabd.hms.messaging;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * COM-001: splits one Meta webhook body into one item per message / status / template event and derives each item's
 * dedupe key: msg:&lt;wamid&gt;, st:&lt;wamid&gt;:&lt;status&gt;, tpl:&lt;template id&gt;:&lt;event&gt;:&lt;timestamp&gt;, tplq:... for quality updates.
 * Fields without a natural id (phone_number_quality_update, account_update, ...) key on a hash of their value.
 * Pure: no I/O, no secrets.
 */
final class WhatsAppInboundKeys {

    /** body is the JSON that gets encrypted and stored: {field, kind, item|value}. */
    record Item(String key, String field, String numberId, ObjectNode body) {
    }

    private WhatsAppInboundKeys() {
    }

    static List<Item> split(JsonNode root, ObjectMapper mapper) {
        List<Item> out = new ArrayList<>();
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                String field = change.path("field").asString("");
                JsonNode value = change.path("value");
                String number = value.path("metadata").path("phone_number_id").asString(null);
                String ts = value.path("timestamp").asString(entry.path("time").asString("0"));
                switch (field) {
                    case "messages" -> {
                        for (JsonNode m : value.path("messages")) {
                            out.add(item(key("msg:", m.path("id").asString(""), m), field, number, "message", "item", m, mapper));
                        }
                        for (JsonNode s : value.path("statuses")) {
                            out.add(item(key("st:", s.path("id").asString("") + ":" + s.path("status").asString(""), s),
                                    field, number, "status", "item", s, mapper));
                        }
                    }
                    case "message_template_status_update" -> out.add(item(
                            key("tpl:", value.path("message_template_id").asString("") + ":" + value.path("event").asString("") + ":" + ts, value),
                            field, number, "template", "value", value, mapper));
                    case "message_template_quality_update" -> out.add(item(
                            key("tplq:", value.path("message_template_id").asString("") + ":" + value.path("new_quality_score").asString("") + ":" + ts, value),
                            field, number, "template", "value", value, mapper));
                    default -> out.add(item("h:" + field + ":" + sha256(value.toString()), field.isEmpty() ? "unknown" : field,
                            number, "other", "value", value, mapper));
                }
            }
        }
        return out;
    }

    /** A missing id (malformed but signed body) must not collapse unrelated items onto one key: fall back to a hash. */
    private static String key(String prefix, String id, JsonNode node) {
        return id.isBlank() || id.startsWith(":") || id.endsWith(":") ? "h:" + prefix + sha256(node.toString()) : prefix + id;
    }

    private static Item item(String key, String field, String number, String kind, String slot, JsonNode node, ObjectMapper mapper) {
        ObjectNode body = mapper.createObjectNode().put("field", field).put("kind", kind);
        body.set(slot, node);
        return new Item(key, field, number, body);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

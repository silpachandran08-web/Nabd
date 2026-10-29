package com.nabd.comms.quality;

import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * COM-021: publishes comms.sender.quality_changed-style alerts as the catalogued event {@code comms.sender.throttled.v1} on ev:&lt;region&gt;:comms
 * (CloudEvents envelope, one field "ce"). The id is the sender_quality_events row id (monotonic, the consumers' dedupe key).
 * A shared sender has no tenant: the nil UUID.
 */
public class QualityEvents {

    public static final String TYPE = "comms.sender.throttled.v1";
    static final UUID NO_TENANT = new UUID(0, 0);

    private final StringRedisTemplate valkey;
    private final ObjectMapper json;
    private final Clock clock;
    private final String region;

    public QualityEvents(StringRedisTemplate valkey, ObjectMapper json, Clock clock, String region) {
        this.valkey = valkey;
        this.json = json;
        this.clock = clock;
        this.region = region;
    }

    public void publish(long id, UUID senderId, UUID tenantId, String kind, String from, String to, String throttleMode) {
        UUID tenant = tenantId == null ? NO_TENANT : tenantId;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tenantId", tenant.toString());
        data.put("senderAccountId", senderId.toString());
        data.put("kind", kind);
        data.put("from", from == null ? "none" : from);
        data.put("to", to == null ? "none" : to);
        data.put("throttleMode", throttleMode);
        data.put("version", id);
        Map<String, Object> ce = new LinkedHashMap<>();
        ce.put("specversion", "1.0");
        ce.put("id", Long.toString(id));
        ce.put("source", "core/messaging");
        ce.put("type", TYPE);
        ce.put("time", clock.instant().toString());
        ce.put("subject", senderId.toString());
        ce.put("tenantid", tenant.toString());
        ce.put("region", region);
        ce.put("dataschema", "urn:nabd:event-schema:" + TYPE);
        ce.put("datacontenttype", "application/json");
        ce.put("data", data);
        valkey.opsForStream().add(StreamRecords.string(Map.of("ce", json.writeValueAsString(ce))).withStreamKey("ev:" + region + ":comms"));
    }
}

package com.nabd.comms.events;

import com.nabd.comms.consumer.CommsConsumerProperties;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * COM-023/COM-030: writes a CloudEvents 1.0 envelope (one field "ce") to ev:&lt;region&gt;:comms, the stream Core's consumers
 * read for domain "comms" (events.md section 7). At-least-once: callers publish inside their DB transaction, so a failed XADD
 * rolls the state change back and the work is retried; the event id is deterministic so consumers dedupe a repeat.
 * data must be PHI-free (ids, codes, timestamps).
 */
@Component
public class StreamEventPublisher {

    private final StringRedisTemplate valkey;
    private final CommsConsumerProperties props;
    private final ObjectMapper json;
    private final Clock clock;

    public StreamEventPublisher(StringRedisTemplate valkey, CommsConsumerProperties props, ObjectMapper json, Clock clock) {
        this.valkey = valkey;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    public void publish(String eventId, String type, UUID tenantId, Map<String, Object> data) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("specversion", "1.0");
        env.put("id", eventId);
        env.put("type", type);
        env.put("source", "nabd/comms-gateway");
        env.put("time", clock.instant().toString());
        env.put("tenantid", tenantId.toString());
        env.put("datacontenttype", "application/json");
        env.put("data", data);
        valkey.opsForStream().add(StreamRecords.newRecord().in(props.stream()).ofMap(Map.of("ce", json.writeValueAsString(env))));
    }
}

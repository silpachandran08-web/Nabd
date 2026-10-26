package com.nabd.edge;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumer group `patient-edge` on the Core/Comms event streams ev:<region>:comms and ev:<region>:scheduling (events.md section 7;
 * one field `ce` with the CloudEvents envelope). Two independent pollers, so a slow conversation step never delays a projection
 * invalidation (target: invalidated within 2 s of the booking). Entries are acknowledged after handling; a failing entry is retried
 * every 5 s and moved to ev:<region>:dlq after 5 attempts.
 */
@Component
public class StreamConsumer {

    static final String GROUP = "patient-edge";
    private static final Logger log = LoggerFactory.getLogger(StreamConsumer.class);
    private static final int MAX_ATTEMPTS = 5;

    private final StringRedisTemplate valkey;
    private final ObjectMapper json;
    private final EdgeProperties props;
    private final MeterRegistry meters;
    private final ConversationEngine engine;
    private final AvailabilityProjection projection;
    private final ConcurrentHashMap<String, Integer> attempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastPending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> groups = new ConcurrentHashMap<>();

    StreamConsumer(StringRedisTemplate valkey, ObjectMapper json, EdgeProperties props, MeterRegistry meters, ConversationEngine engine,
                   AvailabilityProjection projection) {
        this.valkey = valkey;
        this.json = json;
        this.props = props;
        this.meters = meters;
        this.engine = engine;
        this.projection = projection;
    }

    @Scheduled(fixedDelay = 100)
    void pollComms() {
        poll("ev:" + props.region() + ":comms", this::onComms);
    }

    @Scheduled(fixedDelay = 100)
    void pollScheduling() {
        poll("ev:" + props.region() + ":scheduling", this::onScheduling);
    }

    private void poll(String stream, java.util.function.Consumer<JsonNode> handler) {
        try {
            if (groups.get(stream) == null) {
                valkey.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
                    try {
                        c.streamCommands().xGroupCreate(stream.getBytes(StandardCharsets.UTF_8), GROUP, ReadOffset.from("0"), true);
                    } catch (RuntimeException e) {
                        if (!String.valueOf(e.getMessage()).contains("BUSYGROUP") && !String.valueOf(e.getCause()).contains("BUSYGROUP")) {
                            throw e;
                        }
                    }
                    return null;
                });
                groups.put(stream, true);
            }
            org.springframework.data.redis.connection.stream.Consumer me = org.springframework.data.redis.connection.stream.Consumer.from(GROUP, props.consumerName());
            long now = System.currentTimeMillis();
            if (now - lastPending.getOrDefault(stream, 0L) > 5_000) {
                lastPending.put(stream, now);
                handle(stream, handler, valkey.opsForStream().read(me, StreamReadOptions.empty().count(50), StreamOffset.create(stream, ReadOffset.from("0"))));
            }
            handle(stream, handler, valkey.opsForStream().read(me, StreamReadOptions.empty().count(50), StreamOffset.create(stream, ReadOffset.lastConsumed())));
        } catch (RuntimeException e) {
            if (String.valueOf(e.getMessage()).contains("NOGROUP") || String.valueOf(e.getCause()).contains("NOGROUP")) {
                groups.remove(stream);
            }
            meters.counter("edge_stream_poll_errors_total").increment();
        }
    }

    private void handle(String stream, java.util.function.Consumer<JsonNode> handler, List<MapRecord<String, Object, Object>> records) {
        if (records == null) {
            return;
        }
        for (MapRecord<String, Object, Object> r : records) {
            String entry = stream + "/" + r.getId().getValue();
            try {
                handler.accept(json.readTree(String.valueOf(r.getValue().get("ce"))));
                valkey.opsForStream().acknowledge(stream, GROUP, r.getId());
                attempts.remove(entry);
            } catch (RuntimeException e) {
                meters.counter("edge_event_failures_total").increment();
                if (attempts.merge(entry, 1, Integer::sum) >= MAX_ATTEMPTS) {
                    valkey.opsForStream().add("ev:" + props.region() + ":dlq",
                            Map.of("originalStream", stream, "entryId", r.getId().getValue(), "consumer", GROUP));
                    valkey.opsForStream().acknowledge(stream, GROUP, r.getId());
                    attempts.remove(entry);
                    meters.counter("edge_event_dlq_total").increment();
                    log.error("event moved to dlq: {}", entry);
                }
            }
        }
    }

    private void onComms(JsonNode ce) {
        if (!"comms.inbound.received.v1".equals(ce.path("type").asString(""))) {
            return;
        }
        JsonNode d = ce.path("data");
        // `messageId` per the ACC-039 spec; COM-011's published schema names the same id `inboundId`.
        String messageId = d.hasNonNull("messageId") ? d.path("messageId").asString("") : d.path("inboundId").asString("");
        if (messageId.isBlank()) {
            throw new IllegalArgumentException("inbound event without message id");
        }
        engine.process(tenant(ce), messageId, d.path("channel").asString("whatsapp"), d.path("contactHash").asString(""));
    }

    private void onScheduling(JsonNode ce) {
        switch (ce.path("type").asString("")) {
            case "scheduling.appointment.booked.v1", "scheduling.appointment.cancelled.v1", "scheduling.appointment.rescheduled.v1" ->
                    projection.invalidateDoctor(tenant(ce), UUID.fromString(ce.path("data").path("doctorId").asString("")));
            case "scheduling.booking_policy.published.v1" -> projection.invalidateTenant(tenant(ce));
            default -> { }
        }
    }

    private static UUID tenant(JsonNode ce) {
        String t = ce.hasNonNull("tenantid") ? ce.path("tenantid").asString("") : ce.path("data").path("tenantId").asString("");
        return UUID.fromString(t);
    }
}

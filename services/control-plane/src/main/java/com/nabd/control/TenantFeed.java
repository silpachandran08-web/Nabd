package com.nabd.control;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
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
 * The tenant directory feed: consumer group `control-plane` on Core's ev:<cell>:organization streams (events.md sections 7
 * and 8; one field `ce` with the CloudEvents envelope), the same mechanism the Edge and the Comms Gateway use. Core publishes
 * organization.tenant.status_changed.v1 on every lifecycle move (the first is provisioning -> trialing at go-live),
 * organization.tenant.region_pinned.v1 at go-live and organization.branch.created.v1 for a new branch, so no new event type
 * is needed. An entry is acknowledged after it is applied; a failing entry is retried every 5 s and moved to ev:<cell>:dlq
 * after 5 attempts. Core never calls this service: when it is down the entries wait in the stream.
 */
@Component
public class TenantFeed {

    static final String GROUP = "control-plane";
    private static final Logger log = LoggerFactory.getLogger(TenantFeed.class);
    private static final int MAX_ATTEMPTS = 5;

    private final StringRedisTemplate valkey;
    private final ObjectMapper json;
    private final ControlPlaneProperties props;
    private final MeterRegistry meters;
    private final TenantDirectory directory;
    private final ConcurrentHashMap<String, Integer> attempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastPending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> groups = new ConcurrentHashMap<>();

    TenantFeed(StringRedisTemplate valkey, ObjectMapper json, ControlPlaneProperties props, MeterRegistry meters, TenantDirectory directory) {
        this.valkey = valkey;
        this.json = json;
        this.props = props;
        this.meters = meters;
        this.directory = directory;
    }

    @Scheduled(fixedDelay = 1000)
    void poll() {
        for (String region : props.regions()) {
            poll(region, "ev:" + region + ":organization");
        }
    }

    private void poll(String region, String stream) {
        try {
            if (groups.get(stream) == null) {
                valkey.execute((RedisCallback<Object>) c -> {
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
            Consumer me = Consumer.from(GROUP, props.consumerName());
            long now = System.currentTimeMillis();
            if (now - lastPending.getOrDefault(stream, 0L) > 5_000) { // this consumer's own unacknowledged entries (failed or interrupted)
                lastPending.put(stream, now);
                handle(region, stream, valkey.opsForStream().read(me, StreamReadOptions.empty().count(100), StreamOffset.create(stream, ReadOffset.from("0"))));
            }
            handle(region, stream, valkey.opsForStream().read(me, StreamReadOptions.empty().count(100), StreamOffset.create(stream, ReadOffset.lastConsumed())));
        } catch (RuntimeException e) {
            if (String.valueOf(e.getMessage()).contains("NOGROUP") || String.valueOf(e.getCause()).contains("NOGROUP")) {
                groups.remove(stream);
            }
            meters.counter("control_feed_poll_errors_total").increment();
        }
    }

    private void handle(String region, String stream, List<MapRecord<String, Object, Object>> records) {
        if (records == null) {
            return;
        }
        for (MapRecord<String, Object, Object> r : records) {
            String entry = stream + "/" + r.getId().getValue();
            try {
                apply(json.readTree(String.valueOf(r.getValue().get("ce"))));
                valkey.opsForStream().acknowledge(stream, GROUP, r.getId());
                attempts.remove(entry);
            } catch (RuntimeException e) {
                meters.counter("control_feed_failures_total").increment();
                if (attempts.merge(entry, 1, Integer::sum) >= MAX_ATTEMPTS) {
                    valkey.opsForStream().add("ev:" + region + ":dlq",
                            Map.of("originalStream", stream, "entryId", r.getId().getValue(), "consumer", GROUP));
                    valkey.opsForStream().acknowledge(stream, GROUP, r.getId());
                    attempts.remove(entry);
                    meters.counter("control_feed_dlq_total").increment();
                    log.error("event moved to dlq: {}", entry); // the entry id only: never event data
                }
            }
        }
    }

    /** Other organization events are acknowledged without effect. */
    private void apply(JsonNode ce) {
        JsonNode d = ce.path("data");
        switch (ce.path("type").asString("")) {
            case "organization.tenant.status_changed.v1" -> {
                if (d.path("to").asString("").isBlank() || !d.path("version").isIntegralNumber()) {
                    throw new IllegalArgumentException("status event without to/version");
                }
                directory.status(tenant(ce), d.path("to").asString(""), d.path("version").asLong());
            }
            case "organization.tenant.region_pinned.v1" ->
                    directory.region(tenant(ce), d.path("regionCode").asString(""), d.path("cell").asString(""));
            case "organization.branch.created.v1" ->
                    directory.brand(tenant(ce), UUID.fromString(d.path("brandId").asString("")), UUID.fromString(d.path("ownerId").asString("")));
            default -> { }
        }
    }

    private static UUID tenant(JsonNode ce) {
        String t = ce.hasNonNull("tenantid") ? ce.path("tenantid").asString("") : ce.path("data").path("tenantId").asString("");
        return UUID.fromString(t);
    }
}

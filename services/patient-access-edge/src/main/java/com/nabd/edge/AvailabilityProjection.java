package com.nabd.edge;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Availability per doctor-day in Valkey (class B, ADR-04): 30 s TTL plus event invalidation. A miss, or Valkey being down, costs
 * one Core call per doctor-day (single flight), bounded to 2.5 s, so a slot reply stays under 3 s. Core stays authoritative.
 * Keys: edge:avail:{tenant}:doctor:day; index set edge:avail:{tenant}:idx:doctor lists a doctor's cached days for invalidation.
 */
@Component
public class AvailabilityProjection {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityProjection.class);
    private final StringRedisTemplate valkey;
    private final CoreClient core;
    private final ObjectMapper json;
    private final EdgeProperties props;
    private final MeterRegistry meters;
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> inflight = new ConcurrentHashMap<>();

    AvailabilityProjection(StringRedisTemplate valkey, CoreClient core, ObjectMapper json, EdgeProperties props, MeterRegistry meters) {
        this.valkey = valkey;
        this.core = core;
        this.json = json;
        this.props = props;
        this.meters = meters;
    }

    /** The doctor's sessions for the day, as Core returned them. */
    public JsonNode read(UUID tenantId, UUID doctorId, LocalDate day) {
        Timer.Sample t = Timer.start(meters);
        String key = key(tenantId, doctorId, day);
        try {
            try {
                String hit = valkey.opsForValue().get(key);
                if (hit != null) {
                    meters.counter("edge_projection_total", "result", "hit").increment();
                    return json.readTree(hit);
                }
            } catch (RuntimeException e) {
                meters.counter("edge_projection_total", "result", "valkey_error").increment();
            }
            meters.counter("edge_projection_total", "result", "miss").increment();
            CompletableFuture<JsonNode> f = inflight.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> refill(tenantId, doctorId, day, k)));
            try {
                return f.get(2500, TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException e) {
                throw new IllegalStateException("availability unavailable", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            } finally {
                inflight.remove(key, f);
            }
        } finally {
            t.stop(Timer.builder("edge_availability_read_seconds").publishPercentileHistogram().register(meters));
        }
    }

    private JsonNode refill(UUID tenantId, UUID doctorId, LocalDate day, String key) {
        JsonNode sessions = core.doctorDay(tenantId, doctorId, day);
        try {
            String idx = indexKey(tenantId, doctorId);
            valkey.opsForValue().set(key, json.writeValueAsString(sessions), Duration.ofSeconds(props.projectionTtlSeconds()));
            valkey.opsForSet().add(idx, key);
            valkey.expire(idx, Duration.ofSeconds(props.projectionTtlSeconds() * 2L));
        } catch (RuntimeException e) {
            log.warn("projection write skipped, valkey unavailable");
        }
        return sessions;
    }

    /** scheduling.appointment.{booked,cancelled,rescheduled}.v1: drop every cached day of that doctor. */
    public void invalidateDoctor(UUID tenantId, UUID doctorId) {
        String idx = indexKey(tenantId, doctorId);
        Set<String> keys = valkey.opsForSet().members(idx);
        if (keys != null && !keys.isEmpty()) {
            valkey.delete(keys);
        }
        valkey.delete(idx);
        meters.counter("edge_projection_invalidated_total", "scope", "doctor").increment();
    }

    /** scheduling.booking_policy.published.v1: rules changed, drop the tenant's whole projection. */
    public void invalidateTenant(UUID tenantId) {
        List<String> keys = new ArrayList<>();
        try (Cursor<String> c = valkey.scan(ScanOptions.scanOptions().match("edge:avail:{" + tenantId + "}:*").count(500).build())) {
            c.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            valkey.delete(keys);
        }
        meters.counter("edge_projection_invalidated_total", "scope", "tenant").increment();
    }

    /** Slot starts in a doctor-day. Assumes each session's `slots` is a list of ISO-8601 instants (ACC-038 leaves the shape open). */
    public static List<Instant> slotStarts(JsonNode sessions) {
        List<Instant> out = new ArrayList<>();
        for (JsonNode s : sessions) {
            for (JsonNode slot : s.path("slots")) {
                try {
                    out.add(Instant.parse(slot.asString("")));
                } catch (java.time.format.DateTimeParseException e) {
                    // not an instant: not offerable
                }
            }
        }
        return out;
    }

    private static String key(UUID t, UUID d, LocalDate day) {
        return "edge:avail:{" + t + "}:" + d + ":" + day;
    }

    private static String indexKey(UUID t, UUID d) {
        return "edge:avail:{" + t + "}:idx:" + d;
    }
}

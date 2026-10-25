package com.nabd.comms.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * COM-007.4: consumer group "comms" on ev:&lt;region&gt;:comms (events.md section 7). Idempotent on the CloudEvents id
 * (processed_events row in the same transaction as the handler), XACK after commit, retry while pending, dead letter to
 * ev:&lt;region&gt;:dlq (ids and an exception class name only) after maxAttempts. It never calls Core, so Core being down
 * does not stop it.
 */
@Component
public class CommsConsumer {

    public static final String GROUP = "comms";
    public enum Outcome { APPLIED, DUPLICATE, IGNORED, FAILED, DEAD_LETTERED }

    private static final Logger log = LoggerFactory.getLogger(CommsConsumer.class);
    private static final RedisScript<List> AUTOCLAIM = new DefaultRedisScript<>(
            "return redis.call('XAUTOCLAIM', KEYS[1], ARGV[1], ARGV[2], ARGV[3], '0-0', 'COUNT', ARGV[4], 'JUSTID')[2]", List.class);

    private final StringRedisTemplate valkey;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final CommsConsumerProperties props;
    private final List<CommsEventHandler> handlers;
    private final Counter dlq;
    private final Clock clock;
    private final String name = "comms-" + UUID.randomUUID();
    private final AtomicLong lagSeconds = new AtomicLong();
    private volatile boolean groupReady;

    CommsConsumer(StringRedisTemplate valkey, JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json,
                  CommsConsumerProperties props, List<CommsEventHandler> handlers, MeterRegistry meters, Clock clock) {
        this.valkey = valkey;
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
        this.props = props;
        this.handlers = handlers;
        this.clock = clock;
        this.dlq = Counter.builder("consumer.dlq").tag("group", GROUP).register(meters); // same names as Core's consumers
        Gauge.builder("consumer.lag.seconds", lagSeconds, AtomicLong::get).tags("group", GROUP, "stream", props.stream()).register(meters);
    }

    // OPS-013: no @SchedulerLock on purpose: every replica is a consumer of the group, Valkey splits the entries
    @Scheduled(fixedDelayString = "${comms.consumer.poll-interval-ms:200}")
    public void poll() {
        if (props.consumer().enabled()) {
            pollOnce();
        }
    }

    /** Our own pending entries (retries), then new ones, then entries stuck on a dead replica. */
    public List<Outcome> pollOnce() {
        List<Outcome> out = new ArrayList<>();
        try {
            ensureGroup();
            int n = props.consumer().batchSize();
            for (ReadOffset from : List.of(ReadOffset.from("0"), ReadOffset.lastConsumed())) {
                List<MapRecord<String, Object, Object>> recs = valkey.opsForStream().read(Consumer.from(GROUP, name),
                        StreamReadOptions.empty().count(n), StreamOffset.create(props.stream(), from));
                if (recs != null) {
                    recs.forEach(r -> out.add(deliver(r)));
                }
            }
            List<?> ids = valkey.execute(AUTOCLAIM, List.of(props.stream()), GROUP, name,
                    String.valueOf(props.consumer().claimIdleMs()), String.valueOf(n));
            for (Object id : ids == null ? List.<Object>of() : ids) {
                List<MapRecord<String, Object, Object>> r = valkey.opsForStream().range(props.stream(),
                        org.springframework.data.domain.Range.closed(String.valueOf(id), String.valueOf(id)));
                if (r == null || r.isEmpty()) {
                    valkey.opsForStream().acknowledge(props.stream(), GROUP, String.valueOf(id)); // trimmed away
                } else {
                    out.add(deliver(r.get(0)));
                }
            }
            refreshLag();
        } catch (RuntimeException e) { // Valkey down or restarting: the next pass retries, readiness reports it
            if (String.valueOf(e.getMessage()).contains("NOGROUP")) {
                groupReady = false;
            }
            log.warn("comms consumer pass failed: {}", e.getClass().getSimpleName());
        }
        return out;
    }

    Outcome deliver(MapRecord<String, Object, Object> r) {
        String entryId = r.getId().getValue();
        CommsEventHandler.Event e;
        try {
            e = parse(r.getValue().get("ce"));
        } catch (RuntimeException bad) { // deterministic: retrying cannot help
            deadLetter(entryId, "", 1, "malformed_envelope");
            return Outcome.DEAD_LETTERED;
        }
        List<CommsEventHandler> mine = handlers.stream().filter(h -> h.types().contains(e.type())).toList();
        String failKey = "comms:failures:{" + GROUP + "}:" + entryId;
        try {
            Outcome o = mine.isEmpty() ? Outcome.IGNORED : apply(mine, e);
            valkey.opsForStream().acknowledge(props.stream(), GROUP, entryId);
            valkey.delete(failKey);
            return o;
        } catch (RuntimeException ex) {
            Long n = valkey.opsForValue().increment(failKey);
            valkey.expire(failKey, Duration.ofDays(1));
            long attempts = n == null ? 1 : n;
            if (attempts >= props.consumer().maxAttempts()) {
                deadLetter(entryId, e.id(), attempts, ex.getClass().getSimpleName());
                valkey.delete(failKey);
                return Outcome.DEAD_LETTERED;
            }
            log.warn("comms entry {} failed (attempt {}): {}", entryId, attempts, ex.getClass().getSimpleName());
            return Outcome.FAILED; // stays pending; the next pass retries (shortcut: no backoff, upgrade when a sender needs one)
        }
    }

    private Outcome apply(List<CommsEventHandler> mine, CommsEventHandler.Event e) {
        return tx.execute(s -> {
            int rows = jdbc.update("INSERT INTO processed_events (consumer, event_id, tenant_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    GROUP, e.id(), e.tenantId());
            if (rows == 0) {
                return Outcome.DUPLICATE;
            }
            mine.forEach(h -> h.handle(e));
            return Outcome.APPLIED;
        });
    }

    private CommsEventHandler.Event parse(Object raw) {
        if (!(raw instanceof String text) || text.length() > 4096) {
            throw new IllegalArgumentException("missing or oversized envelope");
        }
        JsonNode n = json.readTree(text);
        String id = n.path("id").asString("");
        String type = n.path("type").asString("");
        if (!"1.0".equals(n.path("specversion").asString()) || id.isBlank() || id.length() > 64 || type.isBlank() || !n.path("data").isObject()) {
            throw new IllegalArgumentException("not a CloudEvents 1.0 envelope");
        }
        return new CommsEventHandler.Event(id, type, UUID.fromString(n.path("tenantid").asString()), n.get("data").toString());
    }

    private void deadLetter(String entryId, String eventId, long attempts, String reason) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("originalStream", props.stream());
        f.put("entryId", entryId);
        f.put("eventId", eventId);
        f.put("consumer", GROUP);
        f.put("attempts", String.valueOf(attempts));
        f.put("lastError", reason);
        valkey.opsForStream().add(StreamRecords.newRecord().in(props.dlq()).ofMap(f));
        valkey.opsForStream().acknowledge(props.stream(), GROUP, entryId);
        dlq.increment();
        log.error("event dead-lettered: stream={} entry={} event={} attempts={} reason={}", props.stream(), entryId, eventId, attempts, reason);
    }

    private void ensureGroup() {
        if (groupReady) {
            return;
        }
        try { // from 0: entries XADDed before the group existed are still delivered
            valkey.execute((RedisCallback<String>) c -> c.streamCommands()
                    .xGroupCreate(props.stream().getBytes(StandardCharsets.UTF_8), GROUP, ReadOffset.from("0"), true));
        } catch (RuntimeException e) {
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP") && !String.valueOf(e.getCause()).contains("BUSYGROUP")) {
                throw e;
            }
        }
        groupReady = true;
    }

    /** Age of the oldest entry not yet delivered to the group (0 when caught up). */
    private void refreshLag() {
        var g = valkey.opsForStream().groups(props.stream()).stream().filter(x -> GROUP.equals(x.groupName())).findFirst().orElse(null);
        if (g == null) {
            return;
        }
        String last = g.lastDeliveredId();
        int dash = last.indexOf('-');
        String next = last.substring(0, dash + 1) + (Long.parseLong(last.substring(dash + 1)) + 1);
        var oldest = valkey.opsForStream().range(props.stream(),
                org.springframework.data.domain.Range.rightUnbounded(org.springframework.data.domain.Range.Bound.inclusive(next)),
                org.springframework.data.redis.connection.Limit.limit().count(1));
        lagSeconds.set(oldest == null || oldest.isEmpty() ? 0 : Math.max(0, (clock.millis() - oldest.get(0).getId().getTimestamp()) / 1000));
    }
}

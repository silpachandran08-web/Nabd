package com.nabd.hms.shared.events.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nabd.hms.common.TenantScope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.connection.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OPS-017: runs every {@link EventConsumer} as a Valkey consumer group on ev:&lt;region&gt;:&lt;domain&gt; (docs/architecture/events.md
 * section 7). Each replica is one consumer of each group, so the polls carry no @SchedulerLock: Valkey splits new entries
 * across the replicas, and a replica that dies leaves its entries pending until another XAUTOCLAIMs them after claimIdleMs.
 * Per entry: idempotency (DB ledger or Valkey key), version gate, XACK; a failure stays pending and is retried with
 * exponential backoff; after maxAttempts it goes to ev:&lt;region&gt;:dlq (ids and a reason code only, never data).
 */
@Component
public class ConsumerGroups {

    public enum Outcome { APPLIED, DUPLICATE, STALE, IGNORED, FAILED, DEAD_LETTERED }

    /** The stream entry field holding the structured CloudEvents JSON (events.md section 2). */
    public static final String FIELD = "ce";

    private static final Logger log = LoggerFactory.getLogger(ConsumerGroups.class);
    private static final RedisScript<List> AUTOCLAIM = new DefaultRedisScript<>(
            "return redis.call('XAUTOCLAIM', KEYS[1], ARGV[1], ARGV[2], ARGV[3], '0-0', 'COUNT', ARGV[4], 'JUSTID')[2]", List.class);
    private static final int MAX_ENVELOPE = 4096;
    private static final int LAG_COUNT_CAP = 10_000;

    private final ObjectProvider<StringRedisTemplate> valkey;
    private final List<EventConsumer> consumers;
    private final AppliedEventsRepository ledger;
    private final TenantScope tenants;
    private final ObjectMapper json;
    private final ConsumerProperties props;
    private final MeterRegistry meters;
    private final Clock clock;
    private final String name;
    private final Set<String> groupsReady = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicReference<double[]>> lag = new HashMap<>();
    private volatile long lastWarnMs;

    @Autowired
    ConsumerGroups(ObjectProvider<StringRedisTemplate> valkey, ObjectProvider<EventConsumer> consumers, AppliedEventsRepository ledger,
                   TenantScope tenants, ObjectMapper json, ConsumerProperties props, MeterRegistry meters, Clock clock) {
        this(valkey, consumers.orderedStream().toList(), ledger, tenants, json, props, meters, clock,
                props.name().isBlank() ? hostName() : props.name());
    }

    ConsumerGroups(ObjectProvider<StringRedisTemplate> valkey, List<EventConsumer> consumers, AppliedEventsRepository ledger,
                   TenantScope tenants, ObjectMapper json, ConsumerProperties props, MeterRegistry meters, Clock clock, String name) {
        validate(consumers);
        this.valkey = valkey;
        this.consumers = consumers;
        this.ledger = ledger;
        this.tenants = tenants;
        this.json = json;
        this.props = props;
        this.meters = meters;
        this.clock = clock;
        this.name = name;
        for (EventConsumer c : consumers) {
            dlqCounter(c.group()); // exported at 0, so increase() sees the first dead letter
            for (String s : streams(c)) {
                AtomicReference<double[]> ref = new AtomicReference<>(new double[]{0, 0});
                lag.put(c.group() + "|" + s, ref);
                Gauge.builder("consumer.lag.events", ref, r -> r.get()[0]).tags("group", c.group(), "stream", s)
                        .description("entries in the stream not yet delivered to the group").register(meters);
                Gauge.builder("consumer.lag.seconds", ref, r -> r.get()[1]).tags("group", c.group(), "stream", s)
                        .description("age of the oldest entry not yet delivered to the group").register(meters);
            }
        }
    }

    /** NFR: a handler without an idempotency strategy (or a version gate without the DB ledger) never boots. */
    static void validate(List<EventConsumer> consumers) {
        Set<String> groups = new HashSet<>();
        for (EventConsumer c : consumers) {
            String g = c.group();
            if (g == null || !g.matches("[a-z][a-z0-9-]{1,40}")) {
                throw new IllegalStateException("event consumer " + c.getClass().getName() + ": group must match [a-z][a-z0-9-]{1,40} (OPS-017)");
            }
            if (!groups.add(g)) {
                throw new IllegalStateException("event consumer group " + g + " is registered twice (OPS-017)");
            }
            if (c.idempotency() == null) {
                throw new IllegalStateException("event consumer " + g + " has no idempotency strategy (OPS-017)");
            }
            if (c.types() == null || c.types().isEmpty()) {
                throw new IllegalStateException("event consumer " + g + " handles no event types (OPS-017)");
            }
            if (c.versionGated() && c.idempotency() != EventConsumer.Idempotency.DB_LEDGER) {
                throw new IllegalStateException("event consumer " + g + ": the version gate needs the DB_LEDGER strategy (OPS-017)");
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.events.consumer.poll-interval-ms:200}")
    public void poll() { // no @SchedulerLock on purpose: every replica is a consumer of every group
        if (props.enabled()) {
            pollOnce();
        }
    }

    /** One pass over every group and stream: new entries, own retries due, then entries stuck on a dead consumer. */
    public List<Outcome> pollOnce() {
        List<Outcome> out = new ArrayList<>();
        StringRedisTemplate v = valkey.getIfAvailable();
        if (v == null) {
            return out;
        }
        for (EventConsumer c : consumers) {
            for (String s : streams(c)) {
                try {
                    ensureGroup(v, s, c.group());
                    List<MapRecord<String, Object, Object>> fresh = v.opsForStream().read(Consumer.from(c.group(), name),
                            StreamReadOptions.empty().count(props.batchSize()), StreamOffset.create(s, ReadOffset.lastConsumed()));
                    if (fresh != null) {
                        fresh.forEach(r -> out.add(deliver(v, c, r)));
                    }
                    retryOwnPending(v, c, s, out);
                    claimStuck(v, c, s, out);
                } catch (RuntimeException e) {
                    if (String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage()).contains("NOGROUP")) {
                        groupsReady.remove(s + "|" + c.group()); // Valkey came back empty: recreate the group next pass
                    }
                    warn("consumer {} on {} failed this pass: {}", c.group(), s, e.getClass().getSimpleName());
                }
            }
        }
        return out;
    }

    private void retryOwnPending(StringRedisTemplate v, EventConsumer c, String s, List<Outcome> out) {
        for (PendingMessage p : v.opsForStream().pending(s, Consumer.from(c.group(), name), Range.unbounded(), props.batchSize())) {
            Duration wait = backoff(failures(v, c, s, p.getIdAsString()));
            if (p.getElapsedTimeSinceLastDelivery().compareTo(wait) >= 0) {
                // XCLAIM to ourselves resets the idle time, so another replica's XAUTOCLAIM leaves it alone while we work
                v.opsForStream().claim(s, c.group(), name, wait, p.getId()).forEach(r -> out.add(deliver(v, c, r)));
            }
        }
    }

    private void claimStuck(StringRedisTemplate v, EventConsumer c, String s, List<Outcome> out) {
        List<?> ids = v.execute(AUTOCLAIM, List.of(s), c.group(), name, String.valueOf(props.claimIdleMs()), String.valueOf(props.batchSize()));
        for (Object id : ids == null ? List.of() : ids) {
            String entryId = String.valueOf(id);
            List<MapRecord<String, Object, Object>> r = v.opsForStream().range(s, Range.closed(entryId, entryId));
            if (r == null || r.isEmpty()) {
                v.opsForStream().acknowledge(s, c.group(), entryId); // trimmed away (MAXLEN): nothing left to process
            } else {
                out.add(deliver(v, c, r.get(0)));
            }
        }
    }

    Outcome deliver(StringRedisTemplate v, EventConsumer c, MapRecord<String, Object, Object> r) {
        String s = r.getStream();
        String entryId = r.getId().getValue();
        EventConsumer.Event e;
        try {
            e = parse(s, entryId, r.getValue().get(FIELD));
        } catch (RuntimeException bad) { // deterministic: retrying cannot help
            deadLetter(v, c, s, entryId, "", 1, "malformed_envelope");
            v.opsForStream().acknowledge(s, c.group(), entryId);
            return Outcome.DEAD_LETTERED;
        }
        if (!c.types().contains(e.type())) {
            v.opsForStream().acknowledge(s, c.group(), entryId);
            return Outcome.IGNORED;
        }
        String failKey = failKey(c, s, entryId);
        try {
            Outcome o = apply(v, c, e);
            v.opsForStream().acknowledge(s, c.group(), entryId);
            v.delete(failKey);
            return o;
        } catch (RuntimeException ex) {
            Long n = v.opsForValue().increment(failKey);
            v.expire(failKey, Duration.ofDays(1));
            long attempts = n == null ? 1 : n;
            String reason = ex.getClass().getSimpleName(); // a reason code, never the message (may carry PHI)
            if (attempts >= props.maxAttempts()) {
                deadLetter(v, c, s, entryId, e.id(), attempts, reason);
                v.opsForStream().acknowledge(s, c.group(), entryId);
                v.delete(failKey);
                return Outcome.DEAD_LETTERED;
            }
            warn("consumer {} failed entry {} of {} (attempt {}): {}", c.group(), entryId, s, attempts, reason);
            return Outcome.FAILED;
        }
    }

    /** Idempotency then (for gated consumers) the version gate; the handler runs only on APPLIED. */
    Outcome apply(StringRedisTemplate v, EventConsumer c, EventConsumer.Event e) {
        if (c.idempotency() == EventConsumer.Idempotency.VALKEY_SET) {
            String key = "consumer:applied:{" + c.group() + "}:" + e.id();
            if (Boolean.TRUE.equals(v.hasKey(key))) {
                return Outcome.DUPLICATE;
            }
            c.handle(e);
            v.opsForValue().set(key, "1", Duration.ofSeconds(props.appliedTtlSeconds()));
            return Outcome.APPLIED;
        }
        return tenants.run(e.tenantId(), () -> { // ledger row and projection commit (or roll back) together
            Outcome o = ledger.claim(c.group(), e, c.versionGated());
            if (o == Outcome.APPLIED) {
                c.handle(e);
            }
            return o;
        });
    }

    /** Replays one ev:&lt;region&gt;:dlq entry through its group's idempotency and version gate; removes it on success. */
    public Replayed replay(String region, String dlqEntryId) {
        if (!props.regions().contains(region) || dlqEntryId == null || !dlqEntryId.matches("\\d{1,20}-\\d{1,20}")) {
            throw new IllegalArgumentException("unknown region or malformed DLQ entry id");
        }
        StringRedisTemplate v = valkey.getIfAvailable();
        if (v == null) {
            throw new IllegalStateException("Valkey is not configured (VALKEY_URL)");
        }
        String dlq = dlqStream(region);
        List<MapRecord<String, Object, Object>> found = v.opsForStream().range(dlq, Range.closed(dlqEntryId, dlqEntryId));
        if (found == null || found.isEmpty()) {
            throw new IllegalArgumentException("no entry " + dlqEntryId + " in " + dlq);
        }
        Map<Object, Object> f = found.get(0).getValue();
        String group = String.valueOf(f.get("consumer"));
        EventConsumer c = consumers.stream().filter(x -> x.group().equals(group)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no consumer group " + group));
        String stream = String.valueOf(f.get("originalStream"));
        String entryId = String.valueOf(f.get("entryId"));
        List<MapRecord<String, Object, Object>> orig = v.opsForStream().range(stream, Range.closed(entryId, entryId));
        if (orig == null || orig.isEmpty()) {
            throw new IllegalArgumentException("original entry " + entryId + " is no longer in " + stream);
        }
        EventConsumer.Event e = parse(stream, entryId, orig.get(0).getValue().get(FIELD));
        Outcome o = apply(v, c, e);
        v.opsForStream().delete(dlq, dlqEntryId);
        return new Replayed(group, stream, entryId, e.id(), e.tenantId(), o);
    }

    public record Replayed(String group, String stream, String entryId, String eventId, UUID tenantId, Outcome outcome) {
    }

    /** consumer_lag_events and consumer_lag_seconds per group and stream. Per replica, no @SchedulerLock on purpose: every pod exports the same value. */
    @Scheduled(fixedDelayString = "${app.events.consumer.lag-refresh-ms:15000}")
    public void refreshLag() {
        StringRedisTemplate v = valkey.getIfAvailable();
        if (v == null) {
            return;
        }
        for (EventConsumer c : consumers) {
            for (String s : streams(c)) {
                try {
                    StreamInfo.XInfoGroup g = v.opsForStream().groups(s).stream()
                            .filter(x -> c.group().equals(x.groupName())).findFirst().orElse(null);
                    if (g == null) {
                        continue;
                    }
                    String next = nextId(g.lastDeliveredId());
                    List<MapRecord<String, Object, Object>> oldest = v.opsForStream().range(s,
                            Range.rightUnbounded(Range.Bound.inclusive(next)), Limit.limit().count(1));
                    double events;
                    if (g.getRaw().get("lag") instanceof Number n) { // Valkey / Redis >= 7
                        events = n.doubleValue();
                    } else { // shortcut: Redis 6.2 (the test stand-in) has no lag field, count capped at 10,000
                        events = v.opsForStream().range(s, Range.rightUnbounded(Range.Bound.inclusive(next)),
                                Limit.limit().count(LAG_COUNT_CAP)).size();
                    }
                    double seconds = oldest == null || oldest.isEmpty() ? 0
                            : Math.max(0, (clock.millis() - oldest.get(0).getId().getTimestamp()) / 1000.0);
                    lag.get(c.group() + "|" + s).set(new double[]{events, seconds});
                } catch (RuntimeException e) {
                    warn("consumer lag of {} on {} not refreshed: {}", c.group(), s, e.getClass().getSimpleName());
                }
            }
        }
    }

    List<String> streams(EventConsumer c) {
        Set<String> out = new LinkedHashSet<>();
        for (String region : props.regions()) {
            for (String type : c.types()) {
                out.add("ev:" + region + ":" + type.substring(0, type.indexOf('.') < 0 ? type.length() : type.indexOf('.')));
            }
        }
        return List.copyOf(out);
    }

    static String dlqStream(String region) {
        return "ev:" + region + ":dlq";
    }

    private void ensureGroup(StringRedisTemplate v, String s, String group) {
        if (groupsReady.contains(s + "|" + group)) {
            return;
        }
        try { // from 0: entries XADDed before the group existed are still delivered (the ledger absorbs any replay)
            v.execute((RedisCallback<String>) conn -> conn.streamCommands()
                    .xGroupCreate(s.getBytes(StandardCharsets.UTF_8), group, ReadOffset.from("0"), true));
        } catch (RuntimeException e) {
            if (!String.valueOf(NestedExceptionUtils.getMostSpecificCause(e).getMessage()).contains("BUSYGROUP")) {
                throw e;
            }
        }
        groupsReady.add(s + "|" + group);
    }

    private EventConsumer.Event parse(String stream, String entryId, Object raw) {
        if (!(raw instanceof String text) || text.length() > MAX_ENVELOPE) {
            throw new IllegalArgumentException("missing or oversized envelope");
        }
        JsonNode n;
        try {
            n = json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("envelope is not JSON");
        }
        JsonNode data = n.path("data");
        String id = n.path("id").asText("");
        String type = n.path("type").asText("");
        if (!"1.0".equals(n.path("specversion").asText()) || id.isBlank() || id.length() > 64 || type.isBlank()
                || !data.isObject() || !data.path("version").isIntegralNumber()) {
            throw new IllegalArgumentException("not a CloudEvents 1.0 envelope with data.version");
        }
        UUID subject = n.hasNonNull("subject") ? UUID.fromString(n.get("subject").asText()) : null;
        return new EventConsumer.Event(stream, entryId, id, type, UUID.fromString(n.path("tenantid").asText()), subject,
                data.get("version").asLong(), data);
    }

    private void deadLetter(StringRedisTemplate v, EventConsumer c, String stream, String entryId, String eventId, long attempts, String reason) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("originalStream", stream);
        f.put("entryId", entryId);
        f.put("eventId", eventId);
        f.put("consumer", c.group());
        f.put("attempts", String.valueOf(attempts));
        f.put("lastError", reason);
        // shortcut: no MAXLEN on the DLQ (it should stay near empty; replay removes entries), add one if it ever grows
        v.opsForStream().add(StreamRecords.newRecord().in(dlqStream(stream.split(":")[1])).ofMap(f));
        dlqCounter(c.group()).increment();
        log.error("event dead-lettered: consumer={} stream={} entry={} event={} attempts={} reason={}",
                c.group(), stream, entryId, eventId, attempts, reason);
    }

    private Counter dlqCounter(String group) {
        return Counter.builder("consumer.dlq").tag("group", group).description("events moved to ev:<region>:dlq").register(meters);
    }

    private long failures(StringRedisTemplate v, EventConsumer c, String s, String entryId) {
        String n = v.opsForValue().get(failKey(c, s, entryId));
        return n == null ? 0 : Long.parseLong(n);
    }

    private static String failKey(EventConsumer c, String s, String entryId) {
        return "consumer:failures:{" + c.group() + "}:" + s + ":" + entryId;
    }

    /** No wait for an entry that never failed (left by a crash); then base * 2^(failures-1), capped. */
    Duration backoff(long failures) {
        if (failures <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(Math.min(props.retryMaxMs(), props.retryBaseMs() << Math.min(failures - 1, 20)));
    }

    private static String nextId(String id) {
        int dash = id.indexOf('-');
        return id.substring(0, dash + 1) + (Long.parseLong(id.substring(dash + 1)) + 1);
    }

    private void warn(String fmt, Object... args) {
        long now = clock.millis();
        if (now - lastWarnMs > 60_000) { // at most once a minute: polls run 5 times a second
            lastWarnMs = now;
            log.warn(fmt, args);
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "consumer-" + UUID.randomUUID();
        }
    }
}

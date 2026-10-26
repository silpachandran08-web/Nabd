package com.nabd.hms.realtime;

import com.nabd.hms.common.ApiException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ACC-021: the SSE connection registry and the Valkey side. A delta is XADDed to the tenant-day resume stream
 * {@code rt:{tenant}:<utc day>} (trimmed to 30 minutes, the SSE event id is the stream entry id) and PUBLISHed on
 * {@code t:<tenant>:rt}; every pod subscribes only to the channels of tenants that have a connection on it, so a delta
 * for tenant A is looked up in A's connection set and never touches B's. Without Valkey the endpoint still answers
 * (snapshot and heartbeats) but nothing is pushed.
 */
@Component
public class RealtimeHub {

    private static final Logger log = LoggerFactory.getLogger(RealtimeHub.class);
    private static final RedisScript<String> APPEND = new DefaultRedisScript<>(
            "local id = redis.call('XADD', KEYS[1], '*', 'topic', ARGV[1], 'd', ARGV[2])\n"
                    + "local ms = tonumber(string.match(id, '^(%d+)'))\n"
                    + "redis.call('XTRIM', KEYS[1], 'MINID', tostring(ms - tonumber(ARGV[3])))\n"
                    + "redis.call('EXPIRE', KEYS[1], ARGV[4])\n"
                    + "return id", String.class);
    private static final int REPLAY_MAX = 1000;

    private final ObjectProvider<StringRedisTemplate> valkey;
    private final ObjectProvider<LettuceConnectionFactory> factory;
    private final ObjectMapper json;
    private final Clock clock;
    private final Timer latency;
    private final long resumeWindowMs;
    private final long retentionMs;
    private final int maxPerUser;
    private final int maxTotal;
    private final Executor sender = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, Set<RealtimeConnection>> byTenant = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    // the channel names the tenant; the body never does, so a forged body cannot cross tenants
    private final MessageListener listener = (message, pattern) -> {
        String ch = new String(message.getChannel(), StandardCharsets.UTF_8);
        onMessage(UUID.fromString(ch.substring("t:".length(), ch.length() - ":rt".length())),
                new String(message.getBody(), StandardCharsets.UTF_8));
    };
    private RedisMessageListenerContainer container;

    @Autowired
    RealtimeHub(ObjectProvider<StringRedisTemplate> valkey, ObjectProvider<LettuceConnectionFactory> factory, ObjectMapper json,
                MeterRegistry meters, Clock clock,
                @Value("${app.realtime.resume-window-seconds:300}") long resumeWindowSeconds,
                @Value("${app.realtime.retention-seconds:1800}") long retentionSeconds,
                @Value("${app.realtime.max-streams-per-user:5}") int maxPerUser,
                @Value("${app.realtime.max-connections:2000}") int maxTotal) {
        this.valkey = valkey;
        this.factory = factory;
        this.json = json;
        this.clock = clock;
        this.resumeWindowMs = resumeWindowSeconds * 1000;
        this.retentionMs = retentionSeconds * 1000;
        this.maxPerUser = maxPerUser;
        this.maxTotal = maxTotal;
        this.latency = Timer.builder("realtime.delivery.latency")
                .description("stream append to socket write, per delta").publishPercentileHistogram().register(meters);
        Gauge.builder("realtime.sse.connections", total, AtomicInteger::get).description("open SSE connections on this pod").register(meters);
    }

    public boolean pushing() {
        return valkey.getIfAvailable() != null;
    }

    /**
     * Admits the connection (429 TOO_MANY_STREAMS over the per-user or per-pod cap), sends the snapshot or replays the
     * missed deltas, and starts live delivery. {@code lastEventId} resumes only inside the window and the same UTC day.
     */
    RealtimeConnection open(UUID tenantId, UUID staffId, UUID sessionId, Set<RealtimeTopic> topics, Instant expiresAt,
                            String lastEventId, RealtimeConnection.Sink sink) {
        RealtimeConnection c = new RealtimeConnection(tenantId, staffId, sessionId, topics, expiresAt, sink, sender,
                ms -> latency.record(ms, TimeUnit.MILLISECONDS));
        c.onClose = () -> unregister(c);
        register(c);
        try {
            long now = clock.millis();
            StreamId resume = StreamId.parse(lastEventId);
            boolean fresh = resume != null && now - resume.ms() <= resumeWindowMs && resume.ms() <= now + 1000
                    && day(resume.ms()).equals(day(now)) && pushing();
            StreamId from = fresh ? resume : cursor(tenantId, now);
            if (!fresh) {
                c.sendSnapshot(from, snapshot(topics, from));
            }
            c.replay(since(tenantId, from, topics), from); // also closes the gap between cursor and the subscription
            return c;
        } catch (RuntimeException e) {
            unregister(c);
            throw e;
        }
    }

    private synchronized void register(RealtimeConnection c) {
        Set<RealtimeConnection> set = byTenant.get(c.tenantId);
        long mine = set == null ? 0 : set.stream().filter(x -> x.staffId.equals(c.staffId) && !x.closed()).count();
        if (mine >= maxPerUser || total.get() >= maxTotal) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_STREAMS", "Too many open streams",
                    "Close another tab or board before opening a new stream.");
        }
        if (set == null) {
            set = new CopyOnWriteArraySet<>();
            byTenant.put(c.tenantId, set);
            subscribe(c.tenantId);
        }
        set.add(c);
        total.incrementAndGet();
    }

    synchronized void unregister(RealtimeConnection c) {
        Set<RealtimeConnection> set = byTenant.get(c.tenantId);
        if (set != null && set.remove(c)) {
            total.decrementAndGet();
            if (set.isEmpty()) {
                byTenant.remove(c.tenantId);
                unsubscribe(c.tenantId);
            }
        }
        c.close();
    }

    /** Called by the sse-fanout consumer: append to the resume stream, then publish to every pod. */
    public void publish(UUID tenantId, RealtimeTopic topic, String deltaJson) {
        StringRedisTemplate v = valkey.getIfAvailable();
        if (v == null) {
            return;
        }
        String id = v.execute(APPEND, List.of(streamKey(tenantId, clock.millis())), topic.wire(), deltaJson,
                String.valueOf(retentionMs), String.valueOf(retentionMs / 1000 + 300));
        v.convertAndSend(channel(tenantId), json.createObjectNode().put("i", id).put("t", topic.wire()).put("d", deltaJson).toString());
    }

    /** Role or session change: close the staff member's streams (null = every stream of the tenant) so they reconnect and are re-checked. */
    public void closeStreams(UUID tenantId, UUID staffIdOrNull) {
        StringRedisTemplate v = valkey.getIfAvailable();
        if (v == null) {
            return;
        }
        v.convertAndSend(channel(tenantId), json.createObjectNode().put("ctl", "close")
                .put("staff", staffIdOrNull == null ? "" : staffIdOrNull.toString()).toString());
    }

    void onMessage(UUID tenant, String body) {
        try {
            JsonNode n = json.readTree(body);
            Set<RealtimeConnection> set = byTenant.get(tenant);
            if (set == null) {
                return;
            }
            if ("close".equals(n.path("ctl").asString(""))) {
                String staff = n.path("staff").asString("");
                set.stream().filter(c -> staff.isEmpty() || c.staffId.toString().equals(staff)).forEach(this::unregister);
                return;
            }
            RealtimeTopic topic = RealtimeTopic.parse(n.path("t").asString("")).orElse(null);
            StreamId id = StreamId.parse(n.path("i").asString(""));
            if (topic == null || id == null) {
                return;
            }
            String data = n.path("d").asString("");
            for (RealtimeConnection c : set) {
                if (c.topics.contains(topic)) { // never sent to a connection without the topic's permission
                    c.live(id, data);
                }
            }
        } catch (RuntimeException e) {
            log.warn("realtime message dropped: {}", e.getClass().getSimpleName()); // log-phi-ok: exception class name
        }
    }

    /** Every 25 s: heartbeat to every stream (proxies keep the connection), close streams whose token has expired. */
    // OPS-013: no @SchedulerLock on purpose: every pod serves its own connections
    @Scheduled(fixedDelayString = "${app.realtime.heartbeat-ms:25000}")
    public void heartbeat() {
        Instant now = clock.instant();
        String data = json.createObjectNode().put("type", "heartbeat").put("id", "hb").put("v", now.toEpochMilli()).toString();
        for (Set<RealtimeConnection> set : byTenant.values()) {
            for (RealtimeConnection c : set) {
                if (c.closed() || c.expiresAt != null && !c.expiresAt.isAfter(now)) {
                    unregister(c); // the client reconnects with a fresh token, which re-checks permissions
                } else {
                    c.heartbeat(data);
                }
            }
        }
    }

    private String snapshot(Set<RealtimeTopic> topics, StreamId cursor) {
        // names and rows come from the board reads (ACC-022); the snapshot only says "load now" and where deltas continue
        var n = json.createObjectNode().put("type", "snapshot").put("id", cursor.toString()).put("v", cursor.ms());
        var arr = n.putArray("topics");
        topics.forEach(t -> arr.add(t.wire()));
        return n.toString();
    }

    private StreamId cursor(UUID tenantId, long now) {
        StringRedisTemplate v = valkey.getIfAvailable();
        if (v != null) {
            try {
                var last = v.opsForStream().reverseRange(streamKey(tenantId, now), Range.unbounded(), Limit.limit().count(1));
                if (last != null && !last.isEmpty()) {
                    return StreamId.parse(last.get(0).getId().getValue());
                }
            } catch (RuntimeException e) {
                log.warn("realtime cursor unavailable: {}", e.getClass().getSimpleName()); // log-phi-ok: exception class name
            }
        }
        return new StreamId(now, 0);
    }

    private List<String[]> since(UUID tenantId, StreamId from, Set<RealtimeTopic> topics) {
        StringRedisTemplate v = valkey.getIfAvailable();
        List<String[]> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        try {
            var rows = v.opsForStream().range(streamKey(tenantId, from.ms()), Range.rightUnbounded(Range.Bound.inclusive(from.toString())),
                    Limit.limit().count(REPLAY_MAX));
            for (var r : rows == null ? List.<org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object>>of() : rows) {
                RealtimeTopic t = RealtimeTopic.parse(String.valueOf(r.getValue().get("topic"))).orElse(null);
                if (t != null && topics.contains(t)) {
                    out.add(new String[]{r.getId().getValue(), String.valueOf(r.getValue().get("d"))});
                }
            }
        } catch (RuntimeException e) {
            log.warn("realtime replay unavailable: {}", e.getClass().getSimpleName()); // log-phi-ok: exception class name
        }
        return out;
    }

    private void subscribe(UUID tenantId) {
        LettuceConnectionFactory f = factory.getIfAvailable();
        if (f == null) {
            return;
        }
        if (container == null) {
            container = new RedisMessageListenerContainer();
            container.setConnectionFactory(f);
            container.afterPropertiesSet();
            container.start();
        }
        container.addMessageListener(listener, new ChannelTopic(channel(tenantId)));
    }

    private void unsubscribe(UUID tenantId) {
        if (container != null) {
            container.removeMessageListener(listener, new ChannelTopic(channel(tenantId)));
        }
    }

    @PreDestroy
    void stop() throws Exception {
        if (container != null) {
            container.destroy();
        }
    }

    static String channel(UUID tenantId) {
        return "t:" + tenantId + ":rt";
    }

    private static String day(long ms) {
        return Instant.ofEpochMilli(ms).toString().substring(0, 10);
    }

    private static String streamKey(UUID tenantId, long ms) {
        return "rt:{" + tenantId + "}:" + day(ms);
    }

    int connections() {
        return total.get();
    }
}

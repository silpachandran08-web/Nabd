package com.nabd.comms.schedule;

import com.nabd.comms.consumer.CommsConsumerProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class ValkeyDueIndex implements DueIndex {

    private final StringRedisTemplate valkey;
    private final String key;

    public ValkeyDueIndex(StringRedisTemplate valkey, CommsConsumerProperties props) {
        this.valkey = valkey;
        this.key = "comms:sched:{" + props.region() + "}";
    }

    @Override
    public void add(UUID tenantId, UUID messageId, Instant dueAt) {
        valkey.opsForZSet().add(key, tenantId + "/" + messageId, dueAt.toEpochMilli());
    }

    @Override
    public void remove(UUID tenantId, UUID messageId) {
        valkey.opsForZSet().remove(key, tenantId + "/" + messageId);
    }

    @Override
    public List<Entry> due(Instant now, int limit) {
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> hits =
                valkey.opsForZSet().rangeByScoreWithScores(key, 0, now.toEpochMilli(), 0, limit);
        List<Entry> out = new ArrayList<>();
        for (var t : hits == null ? Set.<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>>of() : hits) {
            String[] p = t.getValue().split("/");
            out.add(new Entry(UUID.fromString(p[0]), UUID.fromString(p[1]), Instant.ofEpochMilli(t.getScore().longValue())));
        }
        return out;
    }

    @Override
    public void rebuild(List<Entry> all) {
        // add-only on purpose: replacing the set would lose a send scheduled while the DB was being read; stale members
        // (already settled) are dropped by the tick when it finds the row no longer 'scheduled'
        for (int i = 0; i < all.size(); i += 1000) {
            var batch = new java.util.HashSet<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>>();
            for (Entry e : all.subList(i, Math.min(i + 1000, all.size()))) {
                batch.add(org.springframework.data.redis.core.ZSetOperations.TypedTuple.of(e.tenantId() + "/" + e.messageId(), (double) e.dueAt().toEpochMilli()));
            }
            valkey.opsForZSet().add(key, batch);
        }
    }
}

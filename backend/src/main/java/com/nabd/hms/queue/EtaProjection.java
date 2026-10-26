package com.nabd.hms.queue;

import com.nabd.hms.queue.EtaEngine.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ACC-017: the Valkey projection {@code t:{tid}:eta:{doctor}:{date}} (hash, TTL 12 h, ids / positions / epoch seconds only: no names,
 * no phone numbers) and the delta channel {@code t:{tid}:q:{doctor}:{date}}. The monotonic v lives in its own counter key. Without
 * Valkey, or when a command fails, every method degrades (empty / no write): the API computes live instead.
 */
@Component
public class EtaProjection {

    static final Duration TTL = Duration.ofHours(12);
    private static final Logger log = LoggerFactory.getLogger(EtaProjection.class);
    /** Hash fields that are not patients: v of the last write and the newcomer's p50 (what a patient checking in now would be told). */
    static final String NEWCOMER = "w";

    record Snapshot(long v, Map<String, long[]> byKey, Long newcomerP50) {
    }

    private final ObjectProvider<StringRedisTemplate> valkey;

    private final tools.jackson.databind.ObjectMapper json;

    EtaProjection(ObjectProvider<StringRedisTemplate> valkey, tools.jackson.databind.ObjectMapper json) {
        this.valkey = valkey;
        this.json = json;
    }

    static String hashKey(UUID tid, UUID doctor, LocalDate day) {
        return "t:" + tid + ":eta:" + doctor + ":" + day;
    }

    static String channel(UUID tid, UUID doctor, LocalDate day) {
        return "t:" + tid + ":q:" + doctor + ":" + day;
    }

    static String key(UUID entryId, UUID appointmentId) {
        return entryId != null ? "e:" + entryId : "a:" + appointmentId;
    }

    /** Replaces the hash, bumps v, publishes the changed rows (and rows that left) as one delta. Returns v, or -1 without Valkey. */
    long write(UUID tid, UUID doctor, LocalDate day, List<Result> results, Instant newcomerP50) {
        StringRedisTemplate v = valkey.getIfAvailable();
        if (v == null) {
            return -1;
        }
        try {
            String hk = hashKey(tid, doctor, day);
            Map<Object, Object> old = v.opsForHash().entries(hk);
            Map<String, String> now = new LinkedHashMap<>();
            for (Result r : results) {
                now.put(key(r.entryId(), r.appointmentId()), r.position() + "|" + r.p50At().getEpochSecond() + "|" + r.p80At().getEpochSecond()
                        + "|" + (r.appointmentId() == null ? "" : r.appointmentId()) + "|" + (r.entryId() == null ? "" : r.entryId()));
            }
            List<Map<String, Object>> changes = new ArrayList<>();
            now.forEach((k, val) -> {
                if (!val.equals(old.get(k))) {
                    changes.add(change(k, val));
                }
            });
            old.forEach((k, val) -> {
                if (!NEWCOMER.equals(k) && !now.containsKey(k.toString())) {
                    Map<String, Object> c = change(k.toString(), val.toString());
                    c.put("position", 0);
                    c.put("p50At", null);
                    c.put("p80At", null);
                    changes.add(c);
                }
            });
            if (newcomerP50 != null) {
                now.put(NEWCOMER, String.valueOf(newcomerP50.getEpochSecond()));
            }
            v.delete(hk);
            if (!now.isEmpty()) {
                v.opsForHash().putAll(hk, now);
                v.expire(hk, TTL);
            }
            Long ver = v.opsForValue().increment(hk + ":v");
            v.expire(hk + ":v", TTL);
            long version = ver == null ? 0 : ver;
            if (!changes.isEmpty()) {
                Map<String, Object> delta = new LinkedHashMap<>();
                delta.put("tenantId", tid);
                delta.put("doctorId", doctor);
                delta.put("clinicDate", day);
                delta.put("v", version);
                delta.put("changes", changes);
                v.convertAndSend(channel(tid, doctor, day), json.writeValueAsString(delta));
            }
            return version;
        } catch (RuntimeException e) {
            log.warn("ETA projection write failed for doctor {}; readers compute live", doctor);
            return -1;
        }
    }

    private static Map<String, Object> change(String k, String val) {
        String[] f = val.split("\\|", -1);
        Map<String, Object> c = new LinkedHashMap<>();
        if (f.length > 4 && !f[4].isEmpty()) c.put("entryId", f[4]);
        if (f.length > 3 && !f[3].isEmpty()) c.put("appointmentId", f[3]);
        c.put("position", Integer.parseInt(f[0]));
        c.put("p50At", Instant.ofEpochSecond(Long.parseLong(f[1])).toString());
        c.put("p80At", Instant.ofEpochSecond(Long.parseLong(f[2])).toString());
        return c;
    }

    Optional<Snapshot> read(UUID tid, UUID doctor, LocalDate day) {
        StringRedisTemplate v = valkey.getIfAvailable();
        if (v == null) {
            return Optional.empty();
        }
        try {
            String hk = hashKey(tid, doctor, day);
            Map<Object, Object> h = v.opsForHash().entries(hk);
            if (h.isEmpty()) {
                return Optional.empty();
            }
            Map<String, long[]> out = new LinkedHashMap<>();
            Long w = null;
            for (var e : h.entrySet()) {
                String k = e.getKey().toString();
                if (NEWCOMER.equals(k)) {
                    w = Long.parseLong(e.getValue().toString());
                    continue;
                }
                String[] f = e.getValue().toString().split("\\|", -1);
                out.put(k, new long[]{Long.parseLong(f[0]), Long.parseLong(f[1]), Long.parseLong(f[2])});
            }
            String ver = v.opsForValue().get(hk + ":v");
            return Optional.of(new Snapshot(ver == null ? 0 : Long.parseLong(ver), out, w));
        } catch (RuntimeException e) {
            log.warn("ETA projection read failed for doctor {}; computing live", doctor);
            return Optional.empty();
        }
    }
}

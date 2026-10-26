package com.nabd.hms.auth;

import com.nabd.hms.common.SessionRevocation;
import com.nabd.hms.config.AuthProperties;
import com.nabd.hms.shared.cache.CacheKeyPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * OPS-020: Valkey in front of SessionLivenessChecker (still the one revocation check; same answers, fewer DB trips).
 * <ul>
 *   <li>Key {@code nabd:sess:g:<sid>} (CacheKeyPolicy; sessions.id/revoked_at are operational, one Valkey per region
 *       cell). Value "0" = revoked (TTL = access-token life), "1" = live (TTL = min(60 s, token's remaining life)).</li>
 *   <li>Every revoke statement calls {@link #revoked} with the ids it revoked; after commit it SETs "0", overwriting
 *       "1", and a live marker is only ever written SET NX, so a check that raced the revocation cannot undo it.</li>
 *   <li>Valkey absent, failing, or in the 2 s back-off after a failure: the DB check (fail closed for revoked sessions),
 *       {@code nabd_session_cache_fallback_total} + an ERROR line at most once a minute (alert in
 *       infra/observability/session-cache.rules.yml). Revocations that could not be written are retried on the next
 *       successful call.</li>
 *   <li>Fail closed across replicas (QA-7 D4): a revoking pod cut off from Valkey cannot overwrite the other pods' "1".
 *       So every revocation also inserts its ids into session_cache_revocations (V1700) in the revoking transaction,
 *       and every pod re-reads the last minute of those ids at least every 750 ms (scheduled every 250 ms, and on the
 *       request path when that is stale); a "1" for such an id is not trusted: the DB decides and the "0" is
 *       re-written. A pod that cannot read that table trusts no "1" at all.</li>
 *   <li>Values are "0"/"1" only; nothing read from Valkey is logged or used as a metric tag.</li>
 * </ul>
 */
@Component
class SessionLivenessCache implements SessionRevocation.RevokedSessions {

    private static final Logger log = LoggerFactory.getLogger(SessionLivenessCache.class);
    private static final List<String> SOURCE = List.of("sessions.id", "sessions.revoked_at");
    private static final long LIVE_MAX_SECONDS = 60;
    private static final long SKIP_NANOS_AFTER_FAILURE = TimeUnit.SECONDS.toNanos(2);
    private static final long LOG_EVERY_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final int MAX_PENDING = 10_000;
    private static final long RECENT_MAX_AGE_NANOS = TimeUnit.MILLISECONDS.toNanos(750);

    private final ObjectProvider<StringRedisTemplate> valkey;
    private final CacheKeyPolicy keys;
    private final MeterRegistry meters;
    private final Duration revokedTtl;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private volatile Set<UUID> recentlyRevoked = Set.of();
    private volatile long recentReadAt = System.nanoTime() - RECENT_MAX_AGE_NANOS - 1;
    private final Queue<UUID> pending = new ConcurrentLinkedQueue<>();
    private volatile long skipUntil = System.nanoTime();
    private volatile long nextLog = System.nanoTime();

    SessionLivenessCache(ObjectProvider<StringRedisTemplate> valkey, CacheKeyPolicy keys, MeterRegistry meters, AuthProperties auth,
                         Clock clock, JdbcTemplate jdbc) {
        this.clock = clock;
        this.jdbc = jdbc;
        this.valkey = valkey;
        this.keys = keys;
        this.meters = meters;
        this.revokedTtl = Duration.ofMinutes(auth.accessTokenTtlMinutes());
    }

    /** Tenant staff sessions only; {@code db} is SessionLivenessChecker.isActive and decides on every miss. */
    boolean isActive(UUID sessionId, Instant tokenExpiresAt, BooleanSupplier db) {
        StringRedisTemplate t = valkey.getIfAvailable();
        if (t == null) {
            return db.getAsBoolean();
        }
        if (System.nanoTime() - skipUntil < 0) {
            fallback();
            return db.getAsBoolean();
        }
        String key = key(sessionId);
        String v;
        try {
            flushPending(t);
            v = t.opsForValue().get(key);
        } catch (RuntimeException e) {
            failed(e);
            return db.getAsBoolean();
        }
        if (v != null && !("1".equals(v) && !liveMarkerTrusted(sessionId))) {
            meters.counter("nabd.session_cache.lookups", "outcome", "hit").increment();
            return "1".equals(v);
        }
        if (v != null) { // a "1" for a session revoked in the last minute: the DB decides, and repairs the marker
            boolean active = db.getAsBoolean();
            if (!active) {
                write(List.of(sessionId));
            }
            return active;
        }
        meters.counter("nabd.session_cache.lookups", "outcome", "miss").increment();
        boolean active = db.getAsBoolean();
        long remaining = tokenExpiresAt == null ? LIVE_MAX_SECONDS : Duration.between(clock.instant(), tokenExpiresAt).getSeconds();
        if (active && remaining > 0) {
            try {
                t.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(Math.min(LIVE_MAX_SECONDS, remaining)));
            } catch (RuntimeException e) {
                failed(e);
            }
        }
        return active;
    }

    @Override
    public void revoked(Collection<UUID> sessionIds) {
        if (sessionIds.isEmpty() || valkey.getIfAvailable() == null) {
            return;
        }
        List<UUID> ids = List.copyOf(sessionIds);
        // in the revoking transaction: commits (or rolls back) with the revocation, whatever Valkey does afterwards
        jdbc.update("DELETE FROM session_cache_revocations WHERE revoked_at < now() - interval '10 minutes'");
        jdbc.batchUpdate("INSERT INTO session_cache_revocations (session_id) VALUES (?) "
                + "ON CONFLICT (session_id) DO UPDATE SET revoked_at = now()", ids.stream().map(id -> new Object[]{id}).toList());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write(ids); // a rolled-back revocation must not sign anyone out
                }
            });
        } else {
            write(ids);
        }
    }

    private void write(List<UUID> ids) {
        StringRedisTemplate t = valkey.getIfAvailable();
        try {
            for (UUID id : ids) {
                t.opsForValue().set(key(id), "0", revokedTtl);
            }
        } catch (RuntimeException e) {
            failed(e);
            ids.stream().limit(Math.max(0, MAX_PENDING - pending.size())).forEach(pending::add);
        }
    }

    /** Per pod, no @SchedulerLock on purpose: each pod keeps its own view of the last minute of revocations. */
    @Scheduled(fixedDelayString = "${app.cache.session-revocations-refresh-ms:250}")
    public void refreshRecentlyRevoked() {
        if (valkey.getIfAvailable() == null) {
            return; // no cache: every check goes to the DB anyway
        }
        long at = System.nanoTime();
        try {
            // 65 s > the 60 s live TTL: covers every "1" written before the revocation
            recentlyRevoked = Set.copyOf(jdbc.queryForList("SELECT session_id FROM session_cache_revocations "
                    + "WHERE revoked_at > now() - interval '65 seconds'", UUID.class));
            recentReadAt = at;
        } catch (RuntimeException e) {
            log.debug("recent session revocations not read: {}", e.getClass().getSimpleName()); // stays stale: no "1" trusted // log-phi-ok: exception class name
        }
    }

    private boolean liveMarkerTrusted(UUID sessionId) {
        if (System.nanoTime() - recentReadAt > RECENT_MAX_AGE_NANOS) {
            refreshRecentlyRevoked();
        }
        return System.nanoTime() - recentReadAt <= RECENT_MAX_AGE_NANOS && !recentlyRevoked.contains(sessionId);
    }

    private void flushPending(StringRedisTemplate t) {
        for (UUID id; (id = pending.peek()) != null; pending.poll()) {
            t.opsForValue().set(key(id), "0", revokedTtl);
        }
    }

    private String key(UUID sessionId) {
        return keys.key("sess", null, SOURCE, false, sessionId.toString());
    }

    private void failed(RuntimeException e) {
        skipUntil = System.nanoTime() + SKIP_NANOS_AFTER_FAILURE;
        log.debug("valkey session cache call failed: {}", e.getClass().getSimpleName()); // log-phi-ok: exception class name
        fallback();
    }

    private void fallback() {
        meters.counter("nabd.session_cache.fallback").increment();
        long now = System.nanoTime();
        if (now - nextLog >= 0) {
            nextLog = now + LOG_EVERY_NANOS;
            log.error("session revocation cache unavailable: checking sessions in Postgres (Valkey down)");
        }
    }
}

package com.nabd.hms.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * OPS-020: organization.config.changed.v1 drops the tenant's cached zone on EVERY instance. The outbox hands the event
 * to one instance only, so that instance evicts locally and publishes the tenant id on a Valkey pub/sub channel that
 * every instance subscribes to. Pub/sub, not an outbox consumer group: OPS-017's groups are in another branch, and this
 * is one PUBLISH on the connection PLT-008 already gives us. A lost message (Valkey down) costs at most the cache TTL
 * (app.cache.tenant-config-ttl-seconds, 30 s). The message is the tenant id only, never a config value.
 */
@Component
public class TenantConfigEvictions implements OutboxEventHandler {

    static final String CHANNEL = "nabd:cfg:changed";
    private static final Logger log = LoggerFactory.getLogger(TenantConfigEvictions.class);

    private final ClinicClock clinicClock;
    private final ObjectProvider<StringRedisTemplate> valkey;
    private final ObjectProvider<RedisConnectionFactory> valkeyFactory;
    private volatile RedisConnection subscription;

    public TenantConfigEvictions(ClinicClock clinicClock, ObjectProvider<StringRedisTemplate> valkey,
                                 ObjectProvider<RedisConnectionFactory> valkeyFactory) {
        this.clinicClock = clinicClock;
        this.valkey = valkey;
        this.valkeyFactory = valkeyFactory;
    }

    @Override
    public String eventType() {
        return DomainEventType.ORGANIZATION_CONFIG_CHANGED.type();
    }

    @Override
    public void handle(OutboxEvent event) {
        if (event.tenantId() == null) {
            return; // tenant config only
        }
        clinicClock.evict(event.tenantId());
        StringRedisTemplate t = valkey.getIfAvailable();
        if (t == null) {
            return; // no Valkey (single instance / dev): the local eviction is all there is
        }
        try {
            t.convertAndSend(CHANNEL, event.tenantId().toString());
        } catch (RuntimeException e) {
            log.warn("config change of tenant {} not broadcast (Valkey unavailable); other instances refresh within the cache TTL", event.tenantId());
        }
    }

    /** Per instance on purpose (no SchedulerLock): every pod keeps its own subscription; re-subscribes after a failure. */
    @Scheduled(fixedDelayString = "${app.cache.subscribe-retry-ms:5000}")
    public void ensureSubscribed() {
        RedisConnectionFactory factory = valkeyFactory.getIfAvailable();
        RedisConnection current = subscription;
        if (factory == null || current != null && !current.isClosed() && current.isSubscribed()) {
            return;
        }
        if (current != null) {
            try {
                current.close();
            } catch (RuntimeException ignored) {
                // already broken
            }
        }
        subscription = null;
        try {
            RedisConnection c = factory.getConnection();
            c.subscribe((message, pattern) -> evict(message.getBody()), CHANNEL.getBytes(StandardCharsets.UTF_8));
            subscription = c;
        } catch (RuntimeException e) {
            log.debug("config invalidation subscription not established yet (Valkey unavailable)");
        }
    }

    private void evict(byte[] tenantId) {
        try {
            clinicClock.evict(UUID.fromString(new String(tenantId, StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException e) {
            log.warn("ignored a malformed config invalidation message"); // never echo the payload
        }
    }
}

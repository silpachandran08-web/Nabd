package com.nabd.hms.common;

import com.nabd.hms.shared.cache.ConfigCache;
import com.nabd.hms.shared.events.consumer.EventConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * OPS-020: organization.config.changed.v1 drops the tenant's cached zone on EVERY instance. The event reaches one
 * instance only: the outbox dispatcher in a region without Valkey, or (VALKEY_URL set: the relay owns domain rows, V1680)
 * the OPS-017 'cache-invalidator' group on ev:&lt;region&gt;:organization, which delivers each entry to ONE replica. That
 * instance evicts locally and publishes the tenant id on a Valkey pub/sub channel every instance subscribes to: the
 * fan-out to all. A lost message (Valkey down) costs at most the cache TTL (app.cache.tenant-config-ttl-seconds, 30 s).
 * The message is the tenant id only, never a config value.
 */
@Component
public class TenantConfigEvictions implements OutboxEventHandler, EventConsumer {

    static final String CHANNEL = "nabd:cfg:changed";
    private static final Logger log = LoggerFactory.getLogger(TenantConfigEvictions.class);

    private final ClinicClock clinicClock;
    private final ObjectProvider<StringRedisTemplate> valkey;
    private final ObjectProvider<RedisConnectionFactory> valkeyFactory;
    private final ConfigCache configCache;
    private volatile RedisConnection subscription;

    public TenantConfigEvictions(ClinicClock clinicClock, ObjectProvider<StringRedisTemplate> valkey,
                                 ObjectProvider<RedisConnectionFactory> valkeyFactory) {
        this(clinicClock, valkey, valkeyFactory, null);
    }

    @Autowired
    public TenantConfigEvictions(ClinicClock clinicClock, ObjectProvider<StringRedisTemplate> valkey,
                                 ObjectProvider<RedisConnectionFactory> valkeyFactory, ConfigCache configCache) {
        this.clinicClock = clinicClock;
        this.valkey = valkey;
        this.valkeyFactory = valkeyFactory;
        this.configCache = configCache;
    }

    @Override
    public String eventType() {
        return DomainEventType.ORGANIZATION_CONFIG_CHANGED.type();
    }

    @Override
    public void handle(OutboxEvent event) {
        changed(event.eventType(), event.tenantId());
    }

    @Override
    public String group() {
        return "cache-invalidator";
    }

    @Override
    public Set<String> types() {
        return Set.of(eventType(), DomainEventType.CATALOGUE_CHANGED.type()); // OPS-021: + the cached price catalogue
    }

    @Override
    public Idempotency idempotency() {
        return Idempotency.VALKEY_SET; // stateless: an eviction is idempotent anyway
    }

    @Override
    public void handle(Event event) {
        changed(event.type(), event.tenantId());
    }

    /** OPS-021: a versioned domain (price catalogue) bumps its version first (only now, after commit), then broadcasts; the zone just evicts. */
    void changed(String type, UUID tenantId) {
        ConfigCache.Domain domain = ConfigCache.Domain.forEvent(type);
        if (domain == null || configCache == null) {
            evictEverywhere(tenantId, null);
        } else if (tenantId != null) {
            configCache.invalidate(tenantId, domain); // throws if Valkey is down: the group/outbox retries, nothing is lost
            evictEverywhere(tenantId, domain);
        }
    }

    private void evictEverywhere(UUID tenantId, ConfigCache.Domain domain) {
        if (tenantId == null) {
            return; // tenant config only
        }
        if (domain == null) {
            clinicClock.evict(tenantId);
        }
        StringRedisTemplate t = valkey.getIfAvailable();
        if (t == null) {
            return; // no Valkey (single instance / dev): the local eviction is all there is
        }
        try {
            t.convertAndSend(CHANNEL, domain == null ? tenantId.toString() : tenantId + "/" + domain.name());
        } catch (RuntimeException e) {
            log.warn("config change of tenant {} not broadcast (Valkey unavailable); other instances refresh within the cache TTL", tenantId);
        }
    }

    /** Per instance, no @SchedulerLock on purpose: every pod keeps its own subscription; re-subscribes after a failure. */
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

    private void evict(byte[] message) {
        try {
            String[] m = new String(message, StandardCharsets.UTF_8).split("/"); // "<tenant>" (zone) or "<tenant>/<DOMAIN>" (OPS-021)
            UUID tenantId = UUID.fromString(m[0]);
            if (m.length == 1) {
                clinicClock.evict(tenantId);
            } else if (configCache != null) {
                configCache.evictLocal(tenantId, ConfigCache.Domain.valueOf(m[1]));
            }
        } catch (IllegalArgumentException e) {
            log.warn("ignored a malformed config invalidation message"); // never echo the payload
        }
    }

    /** Regions without Valkey: the outbox dispatcher delivers the catalogue event (DomainEventHandlers skips it) to the same logic. */
    @Component
    static class CatalogueChangedHandler implements OutboxEventHandler {
        private final TenantConfigEvictions evictions;

        CatalogueChangedHandler(TenantConfigEvictions evictions) {
            this.evictions = evictions;
        }

        @Override
        public String eventType() {
            return DomainEventType.CATALOGUE_CHANGED.type();
        }

        @Override
        public void handle(OutboxEvent event) {
            evictions.changed(event.eventType(), event.tenantId());
        }
    }
}

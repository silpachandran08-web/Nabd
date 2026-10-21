package com.nabd.hms.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.nabd.hms.shared.cache.CacheKeyPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The one place a clinic's calendar is defined. A doctor's "09:00–13:00", a queue_date, "today's"
 * nursing list, a package expiring "today" — all mean the clinic's wall clock (tenants.timezone),
 * never the JVM's zone, the DB session's zone, or a hard-coded UTC. Mixing those was two real bugs:
 * nursing lists missing check-ins after midnight, and session caps never matching a booking because
 * "09:00" was read as 09:00 UTC (14:30 in India).
 *
 * <p>Pure helpers that need "today" (age checks) take it as a parameter from their caller instead.
 * ClinicDatesUsageTest keeps LocalDate.now(), CURRENT_DATE and ::date out of production code.
 */
@Component
public class ClinicClock {

    private static final Logger log = LoggerFactory.getLogger(ClinicClock.class);

    private static final ZoneId UTC = ZoneId.of("UTC"); // id "UTC", not "Z" — it is passed to SQL AT TIME ZONE

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CacheKeyPolicy keys;
    private final long ttlNanos;
    /** OPS-020: tenant zone/region per instance; evicted on organization.config.changed.v1 (TenantConfigEvictions), TTL as backstop. */
    private final Map<String, Cached> rows = new ConcurrentHashMap<>();

    private record Cached(String[] row, long loadedAt) {
    }

    ClinicClock(JdbcTemplate jdbc, Clock clock) {
        this(jdbc, clock, null, 0);
    }

    @Autowired
    ClinicClock(JdbcTemplate jdbc, Clock clock, CacheKeyPolicy keys,
                @Value("${app.cache.tenant-config-ttl-seconds:30}") long ttlSeconds) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.keys = keys;
        this.ttlNanos = keys == null ? 0 : TimeUnit.SECONDS.toNanos(ttlSeconds);
    }

    /** OPS-020: drop this tenant's cached zone/region (its configuration changed). */
    public void evict(UUID tenantId) {
        if (ttlNanos > 0) {
            rows.remove(key(tenantId));
        }
    }

    /** The current instant from the injected Clock (PLT-028), so tests can freeze it. */
    public Instant now() {
        return clock.instant();
    }

    /** tenants carries no RLS (V6), so this works before or after TenantContext.set. */
    public ZoneId zone(UUID tenantId) {
        String[] row = tenantRow(tenantId);
        return resolve(row[0], tenantId);
    }

    /** The tenant's region_code (IN, AE, SA, QA), or null for an unknown tenant; e.g. the default phone country (ACC-028). */
    public String region(UUID tenantId) {
        return tenantRow(tenantId)[1];
    }

    private String[] tenantRow(UUID tenantId) {
        String key = ttlNanos > 0 ? key(tenantId) : null;
        Cached c = key == null ? null : rows.get(key);
        long now = System.nanoTime();
        if (c != null && now - c.loadedAt() < ttlNanos) {
            return c.row();
        }
        String[] row = load(tenantId);
        if (row == null) {
            return new String[2]; // unknown tenant: never cached
        }
        if (key != null) {
            // shortcut: a load racing an eviction can put the old row back until the TTL; a version check if that ever matters
            rows.put(key, new Cached(row, now));
        }
        return row;
    }

    private String[] load(UUID tenantId) {
        // a list of String[] because a null timezone in a List<String> made findFirst() throw (500 on /schedule/day)
        // timezone is NOT NULL (V1991); the region default is the safety net for a row written with triggers off
        return jdbc.query("SELECT COALESCE(t.timezone, r.default_timezone), t.region_code FROM tenants t LEFT JOIN regions r ON r.code = t.region_code WHERE t.id = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, tenantId).stream().findFirst().orElse(null);
    }

    /** Built by CacheKeyPolicy: tenants.timezone/region are operational, so this never refuses (ADR-04 gate). */
    private String key(UUID tenantId) {
        return keys.key("tenant-zone", tenantId, List.of("tenants.timezone", "tenants.region_code"), false, "row");
    }

    public LocalDate today(UUID tenantId) {
        return today(zone(tenantId));
    }

    public LocalDate today(ZoneId zone) {
        return LocalDate.now(clock.withZone(zone));
    }

    /** The instant a clinic day starts — the lower bound of a "that day" timestamptz range. */
    public static Instant startOf(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).toInstant();
    }

    /** Setup writes go through this; anything it rejects never reaches tenants.timezone. */
    public static boolean isValidZone(String tz) {
        if (tz == null || tz.isBlank() || !tz.contains("/") && !"UTC".equals(tz)) {
            return false; // region IDs only — "IST" or "+05:30" would silently ignore DST rules / be ambiguous
        }
        try {
            ZoneId.of(tz);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    /**
     * The stored zone (the region default was applied by SQL when it was missing). Legacy rows predate validation, so a
     * bad or blank value degrades to UTC rather than failing requests.
     */
    static ZoneId resolve(String tz, UUID tenantId) {
        if (isValidZone(tz)) {
            return ZoneId.of(tz);
        }
        if (tz == null || tz.isBlank()) {
            return UTC; // unknown tenant
        }
        log.warn("tenant {} has unusable timezone '{}', using UTC", tenantId, tz);
        return UTC;
    }
}

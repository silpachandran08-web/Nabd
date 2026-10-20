package com.nabd.hms.common;

import com.nabd.hms.shared.cache.CacheKeyPolicy;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/** OPS-020 tests: a second instance's ClinicClock (package-private constructor) with its own zone cache. */
public final class Ops020Replica {

    private Ops020Replica() {
    }

    public static ClinicClock clinicClock(JdbcTemplate jdbc, CacheKeyPolicy keys, long ttlSeconds) {
        return new ClinicClock(jdbc, Clock.systemUTC(), keys, ttlSeconds);
    }
}

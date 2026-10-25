package com.nabd.edge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sweeper: expired holds, conversations past 24 h, processed message ids older than 7 days (edge_purge_expired, V3). */
@Component
public class Maintenance {

    private static final Logger log = LoggerFactory.getLogger(Maintenance.class);
    private final JdbcTemplate jdbc;

    Maintenance(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // OPS-013: no @SchedulerLock on purpose: the sweep is idempotent deletes, safe on every replica
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void sweep() {
        try {
            Integer n = jdbc.queryForObject("select edge_purge_expired()", Integer.class);
            log.debug("edge sweep removed {} rows", n);
        } catch (RuntimeException e) {
            log.warn("edge sweep failed");
        }
    }
}

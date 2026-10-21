package com.nabd.hms.shared.ops;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OPS-014: outbox backlog gauges per region from one aggregate query (outbox_stats, V1320) every 15 s:
 * outbox_pending_events, outbox_dead_events, outbox_oldest_pending_age_seconds. The only label is region (a regions.code):
 * no tenant label at all, so no cardinality growth and nothing identifying (OPS-025 rule for /actuator/prometheus).
 */
@Component
public class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private record Stats(long pending, long dead, double ageSeconds) {
    }

    private final JdbcTemplate jdbc;
    private final Map<String, AtomicReference<Stats>> byRegion = new HashMap<>();

    OutboxMetrics(JdbcTemplate jdbc, MeterRegistry registry, com.nabd.hms.organization.api.RegionRules regions) {
        this.jdbc = jdbc;
        for (String region : regions.all().stream().map(com.nabd.hms.organization.api.RegionRules.Region::code).toList()) {
            AtomicReference<Stats> ref = new AtomicReference<>(new Stats(0, 0, 0));
            byRegion.put(region, ref);
            Gauge.builder("outbox.pending.events", ref, r -> r.get().pending()).tag("region", region)
                    .description("pending + processing outbox events").register(registry);
            Gauge.builder("outbox.dead.events", ref, r -> r.get().dead()).tag("region", region)
                    .description("dead-lettered outbox events").register(registry);
            Gauge.builder("outbox.oldest.pending.age.seconds", ref, r -> r.get().ageSeconds()).tag("region", region)
                    .description("age of the oldest unfinished outbox event").register(registry);
        }
    }

    // OPS-013: no @SchedulerLock on purpose: per-replica gauges, every scraped pod must refresh its own
    @Scheduled(fixedDelayString = "${app.outbox.metrics-interval-ms:15000}")
    public void refresh() {
        try {
            jdbc.query("SELECT * FROM outbox_stats()", rs -> {
                AtomicReference<Stats> ref = byRegion.get(rs.getString("region"));
                if (ref != null) {
                    ref.set(new Stats(rs.getLong("pending"), rs.getLong("dead"), rs.getDouble("oldest_pending_age_seconds")));
                }
            });
        } catch (RuntimeException e) {
            log.warn("outbox metrics refresh failed; gauges keep their last values", e);
        }
    }
}

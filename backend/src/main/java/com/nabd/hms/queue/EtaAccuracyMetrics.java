package com.nabd.hms.queue;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.common.TenantScope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * ACC-019: nabd_eta_mae_seconds, nabd_eta_p80_coverage (rolling window of recent calls) and nabd_eta_visits_today per tenant.
 * Cardinality is bounded: the label is a hash of the tenant id (nothing identifying), only the TOP-N tenants by visits today
 * get their own series and the rest are folded into tenant="other" (volume-weighted). The drift alert
 * (infra/observability/eta-accuracy.rules.yml) fires on coverage below 70% only where nabd_eta_visits_today >= 30.
 * shortcut: one transaction per tenant every refresh, fine for hundreds of tenants; move to a single cross-tenant aggregate function beyond that.
 */
@Component
public class EtaAccuracyMetrics {

    private static final Logger log = LoggerFactory.getLogger(EtaAccuracyMetrics.class);
    /** Fewer calls than this in the window say nothing about drift: coverage is reported as 1. */
    static final int MIN_WINDOW_CALLS = 5;

    record Row(String tenantHash, EtaAccuracyRepository.Live live) {
    }

    private final JdbcTemplate jdbc;
    private final TenantScope tenantScope;
    private final EtaAccuracyRepository repo;
    private final ClinicClock clock;
    private final int topN;
    private final Duration window;
    private final String salt;
    private final MultiGauge mae;
    private final MultiGauge coverage;
    private final MultiGauge visits;

    EtaAccuracyMetrics(JdbcTemplate jdbc, TenantScope tenantScope, EtaAccuracyRepository repo, ClinicClock clock, MeterRegistry registry,
                       @Value("${app.eta-accuracy.metrics-top-n:50}") int topN,
                       @Value("${app.eta-accuracy.window-minutes:180}") int windowMinutes,
                       @Value("${app.eta-accuracy.tenant-label-salt:eta}") String salt) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.repo = repo;
        this.clock = clock;
        this.topN = topN;
        this.window = Duration.ofMinutes(windowMinutes);
        this.salt = salt;
        this.mae = MultiGauge.builder("nabd.eta.mae.seconds").description("mean absolute ETA error today (seconds), per tenant hash").register(registry);
        this.coverage = MultiGauge.builder("nabd.eta.p80.coverage").description("share of recent calls at or before the promised p80 time").register(registry);
        this.visits = MultiGauge.builder("nabd.eta.visits.today").description("visits called today that had an ETA (confidence gate)").register(registry);
    }

    // OPS-013: no @SchedulerLock on purpose: per-replica gauges, every scraped pod must refresh its own
    @Scheduled(fixedDelayString = "${app.eta-accuracy.metrics-interval-ms:300000}", initialDelayString = "${app.eta-accuracy.metrics-interval-ms:300000}")
    public void refresh() {
        try {
            publish(collect());
        } catch (RuntimeException e) {
            log.warn("eta accuracy metrics refresh failed; gauges keep their last values", e);
        }
    }

    List<Row> collect() {
        Instant from = clock.now().minus(window);
        List<Row> rows = new ArrayList<>();
        for (UUID tenantId : jdbc.queryForList("SELECT id FROM tenants", UUID.class)) {
            var live = tenantScope.run(tenantId, () -> repo.live(tenantId, clock.clinicDate(tenantId), from));
            if (live.visitsToday() > 0) {
                rows.add(new Row(hash(tenantId), live));
            }
        }
        return rows;
    }

    void publish(List<Row> rows) {
        List<Row> sorted = rows.stream().sorted(Comparator.comparingInt((Row r) -> r.live().visitsToday()).reversed()).toList();
        List<Row> top = sorted.subList(0, Math.min(topN, sorted.size()));
        List<Row> rest = sorted.subList(top.size(), sorted.size());
        List<MultiGauge.Row<?>> m = new ArrayList<>(), c = new ArrayList<>(), v = new ArrayList<>();
        for (Row r : top) {
            add(m, c, v, r.tenantHash(), r.live().dayMaeSeconds(), windowCoverage(r.live()), r.live().visitsToday());
        }
        if (!rest.isEmpty()) {
            int total = rest.stream().mapToInt(r -> r.live().visitsToday()).sum();
            double maeW = rest.stream().mapToDouble(r -> r.live().dayMaeSeconds() * r.live().visitsToday()).sum() / total;
            double covW = rest.stream().mapToDouble(r -> windowCoverage(r.live()) * r.live().visitsToday()).sum() / total;
            add(m, c, v, "other", maeW, covW, total);
        }
        mae.register(m, true);
        coverage.register(c, true);
        visits.register(v, true);
    }

    private static double windowCoverage(EtaAccuracyRepository.Live l) {
        return l.windowN() < MIN_WINDOW_CALLS ? 1.0 : l.windowP80();
    }

    private static void add(List<MultiGauge.Row<?>> m, List<MultiGauge.Row<?>> c, List<MultiGauge.Row<?>> v,
                            String tenant, double maeSeconds, double cov, int n) {
        Tags tags = Tags.of("tenant", tenant);
        m.add(MultiGauge.Row.of(tags, maeSeconds));
        c.add(MultiGauge.Row.of(tags, cov));
        v.add(MultiGauge.Row.of(tags, n));
    }

    String hash(UUID tenantId) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((salt + tenantId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.nabd.hms.reports;

import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.organization.api.RegionRules;
import com.nabd.hms.shared.events.consumer.EventConsumer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * OPS-010: the 'reporting-rollup' consumer group. Any visit/invoice/payment event dirties one clinic day of one tenant; the
 * day is recomputed from source and upserted (tenant_daily_metrics, doctor_daily_metrics), so a replay writes the same
 * numbers and the ledger row (DB_LEDGER, same tenant transaction) makes a duplicate a no-op. A day closed in day_closes is
 * immutable: a late event for it changes nothing and counts in reporting_late_event_total; a reopened day recomputes again.
 * shortcut: no scheduling.appointment.no_show.v1 exists, no_shows comes from the recompute; no invoice-created event
 * exists, so billed for a past day catches up on the next event of that day (today's figure is always read live).
 */
@Component
public class RollupProjector implements EventConsumer {

    static final Set<String> TYPES = Set.of("visit.completed.v1", "visit.queue_entry.created.v1", "billing.payment.recorded.v1", "billing.invoice.paid.v1");

    private final ReportsRepository repo;
    private final ClinicClock clock;
    private final RegionRules regions;
    private final MeterRegistry meters;

    RollupProjector(ReportsRepository repo, ClinicClock clock, RegionRules regions, MeterRegistry meters) {
        this.repo = repo;
        this.clock = clock;
        this.regions = regions;
        this.meters = meters;
        meters.counter("reporting.late.event"); // exported at 0 so increase() sees the first one
    }

    @Override
    public String group() {
        return "reporting-rollup";
    }

    @Override
    public Set<String> types() {
        return TYPES;
    }

    @Override
    public Idempotency idempotency() {
        return Idempotency.DB_LEDGER;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(Event e) {
        ZoneId zone = clock.zone(e.tenantId());
        Optional<LocalDate> day = switch (e.type()) {
            case "billing.payment.recorded.v1" -> paymentId(e.data()).flatMap(id -> repo.paymentRecordedAt(e.tenantId(), id))
                    .map(t -> t.atZone(zone).toLocalDate());
            case "billing.invoice.paid.v1" -> Optional.of(clock.today(zone));
            default -> queueDate(e.data().path("queueDate"));
        };
        if (day.isEmpty()) {
            return;
        }
        if (repo.dayClosed(e.tenantId(), day.get())) {
            meters.counter("reporting.late.event").increment();
            return;
        }
        recompute(e.tenantId(), day.get(), e.id());
    }

    private static Optional<UUID> paymentId(tools.jackson.databind.JsonNode data) {
        try {
            return Optional.of(UUID.fromString(data.path("paymentId").asString("")));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** The wire form of a LocalDate is "2026-01-31" or, with Jackson's timestamp default, [2026,1,31]; anything else is ignored. */
    private static Optional<LocalDate> queueDate(tools.jackson.databind.JsonNode n) {
        if (n.isArray() && n.size() == 3) {
            return Optional.of(LocalDate.of(n.get(0).asInt(), n.get(1).asInt(), n.get(2).asInt()));
        }
        try {
            return n.isString() ? Optional.of(LocalDate.parse(n.asString())) : Optional.empty();
        } catch (java.time.format.DateTimeParseException ex) {
            return Optional.empty();
        }
    }

    /** Recomputes one clinic day from source, closed or not: the reopen path, the parity check and the historic backfill use it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recompute(UUID tenantId, LocalDate day, String eventId) {
        ZoneId zone = clock.zone(tenantId);
        String currency = regions.region(java.util.Optional.ofNullable(clock.region(tenantId)).orElse("IN")).currency();
        repo.recomputeTenantDay(tenantId, day, ClinicClock.startOf(day, zone), ClinicClock.startOf(day.plusDays(1), zone), clock.now(), currency, eventId);
    }
}

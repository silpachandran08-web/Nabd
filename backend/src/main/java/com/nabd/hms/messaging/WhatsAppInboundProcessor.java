package com.nabd.hms.messaging;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * COM-001: claims stored webhook rows (lease, fenced finish, dead-letter after {@link WhatsAppInboundService#MAX_ATTEMPTS}) and
 * hands each to WhatsAppInboundService. poll() is also called directly by tests. No payload or error text is logged.
 */
@Component
class WhatsAppInboundProcessor {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppInboundProcessor.class);
    private static final int BATCH = 50;
    private static final int LEASE_SECONDS = 60;
    private static final long STATS_TTL_MS = 5_000;

    private final WhatsAppInboundService service;
    private final Clock clock;
    private volatile WhatsAppInboundRepository.Stats stats = new WhatsAppInboundRepository.Stats(0, 0, 0);
    private volatile long statsAt;

    WhatsAppInboundProcessor(WhatsAppInboundService service, MeterRegistry meters, Clock clock) {
        this.service = service;
        this.clock = clock;
        Gauge.builder("whatsapp.inbound.pending", () -> stats().pending()).register(meters);
        Gauge.builder("whatsapp.inbound.dead", () -> stats().dead()).register(meters);
        Gauge.builder("whatsapp.inbound.oldest.age.seconds", () -> stats().oldestSeconds()).register(meters);
    }

    private WhatsAppInboundRepository.Stats stats() {
        long now = clock.millis();
        if (now - statsAt > STATS_TTL_MS) {
            try {
                stats = service.stats();
            } catch (RuntimeException e) {
                log.warn("whatsapp inbound stats unavailable: {}", e.getClass().getSimpleName()); // log-phi-ok: exception class name
            }
            statsAt = now;
        }
        return stats;
    }

    @Scheduled(fixedDelayString = "${app.whatsapp.inbound.poll-interval-ms:1000}")
    @SchedulerLock(name = "whatsapp-inbound", lockAtMostFor = "PT2M") // leases still fence a run that outlives it
    public void poll() {
        for (WhatsAppInboundRepository.Row row : service.claim(BATCH, LEASE_SECONDS)) {
            try {
                service.process(row);
            } catch (Exception e) {
                log.warn("whatsapp inbound event {} failed on attempt {}: {}", row.id(), row.attempts(), e.getClass().getSimpleName()); // log-phi-ok: exception class name
                try {
                    service.release(row, e.getClass().getSimpleName());
                } catch (RuntimeException r) {
                    log.warn("whatsapp inbound event {} could not be released; the lease will expire", row.id());
                }
            }
        }
    }

}

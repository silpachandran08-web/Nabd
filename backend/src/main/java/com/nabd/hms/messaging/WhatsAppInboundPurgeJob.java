package com.nabd.hms.messaging;

import com.nabd.hms.shared.BatchedPurge;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** COM-001: nightly retention. The encrypted raw body goes after 30 days; the dedupe key and outcome stay 90 days. */
@Component
class WhatsAppInboundPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppInboundPurgeJob.class);
    private final WhatsAppInboundService service;
    private final int bodyDays;
    private final int keyDays;
    private final int batch;
    private final long pauseMs;
    private final int maxBatches;

    WhatsAppInboundPurgeJob(WhatsAppInboundService service,
                            @Value("${app.purge.whatsapp-inbound-body-days:30}") int bodyDays,
                            @Value("${app.purge.whatsapp-inbound-key-days:90}") int keyDays,
                            @Value("${app.purge.batch-size:10000}") int batch,
                            @Value("${app.purge.pause-ms:200}") long pauseMs,
                            @Value("${app.purge.max-batches:500}") int maxBatches) {
        this.service = service;
        this.bodyDays = bodyDays;
        this.keyDays = keyDays;
        this.batch = batch;
        this.pauseMs = pauseMs;
        this.maxBatches = maxBatches;
    }

    @Scheduled(cron = "${app.purge.cron:0 15 3 * * *}", zone = "${app.purge.zone:Asia/Kolkata}")
    @SchedulerLock(name = "whatsapp-inbound-purge", lockAtMostFor = "PT2H")
    public void purge() {
        log.info("whatsapp_inbound_events purge touched {} rows", run());
    }

    /** Also called by tests. */
    int run() {
        return BatchedPurge.run(b -> service.purge(bodyDays, keyDays, b), batch, pauseMs, maxBatches);
    }
}

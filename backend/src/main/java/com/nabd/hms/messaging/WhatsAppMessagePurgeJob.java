package com.nabd.hms.messaging;

import com.nabd.hms.shared.BatchedPurge;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OPS-013: nightly purge of whatsapp_messages older than app.purge.messages-months (24 by default; the per-jurisdiction
 * value still needs DPO confirmation), batched like AuthPurgeJob via purge_whatsapp_messages (V1541).
 */
@Component
public class WhatsAppMessagePurgeJob {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppMessagePurgeJob.class);
    private final JdbcTemplate jdbc;
    private final int months;
    private final int batch;
    private final long pauseMs;
    private final int maxBatches;

    WhatsAppMessagePurgeJob(JdbcTemplate jdbc,
                            @Value("${app.purge.messages-months:24}") int months,
                            @Value("${app.purge.batch-size:10000}") int batch,
                            @Value("${app.purge.pause-ms:200}") long pauseMs,
                            @Value("${app.purge.max-batches:500}") int maxBatches) {
        this.jdbc = jdbc;
        this.months = months;
        this.batch = batch;
        this.pauseMs = pauseMs;
        this.maxBatches = maxBatches;
    }

    @Scheduled(cron = "${app.purge.cron:0 15 3 * * *}", zone = "${app.purge.zone:Asia/Kolkata}")
    @SchedulerLock(name = "messages-purge", lockAtMostFor = "PT2H")
    public void purge() {
        int n = BatchedPurge.run(b -> jdbc.queryForObject("SELECT purge_whatsapp_messages(?, ?)", Integer.class, months, b), batch, pauseMs, maxBatches);
        log.info("whatsapp_messages purge removed {} rows", n);
    }
}

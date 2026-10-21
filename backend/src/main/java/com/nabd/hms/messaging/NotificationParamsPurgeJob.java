package com.nabd.hms.messaging;

import com.nabd.hms.shared.BatchedPurge;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** COM-004 NFR: render params are erased 30 days after the send window closed (purge_notification_params, V1870). */
@Component
public class NotificationParamsPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationParamsPurgeJob.class);
    private final JdbcTemplate jdbc;
    private final int days;
    private final int batch;
    private final long pauseMs;
    private final int maxBatches;

    NotificationParamsPurgeJob(JdbcTemplate jdbc,
                               @Value("${app.purge.notification-params-days:30}") int days,
                               @Value("${app.purge.batch-size:10000}") int batch,
                               @Value("${app.purge.pause-ms:200}") long pauseMs,
                               @Value("${app.purge.max-batches:500}") int maxBatches) {
        this.jdbc = jdbc;
        this.days = days;
        this.batch = batch;
        this.pauseMs = pauseMs;
        this.maxBatches = maxBatches;
    }

    @Scheduled(cron = "${app.purge.cron:0 15 3 * * *}", zone = "${app.purge.zone:Asia/Kolkata}")
    @SchedulerLock(name = "notification-params-purge", lockAtMostFor = "PT2H")
    public void purge() {
        int n = purgeNow();
        log.info("notification_requests params purge cleared {} rows", n);
    }

    int purgeNow() {
        return BatchedPurge.run(b -> jdbc.queryForObject("SELECT purge_notification_params(?, ?)", Integer.class, days, b), batch, pauseMs, maxBatches);
    }
}

package com.nabd.hms.common;

import com.nabd.hms.shared.BatchedPurge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** OPS-014: keeps outbox_events flat. Deletes done rows after 7 days and dead rows after 90 (dead rows are kept
 * longer for investigation). OPS-013: retention, batch (10k), pause and the nightly window (03:15 cell-local) come from
 * the shared app.purge.* settings, like the other purge jobs; each batch is its own statement. */
@Component
public class OutboxPurgeJob {

    static final int BATCH = 10_000; // OPS-013 default of app.purge.batch-size

    private static final Logger log = LoggerFactory.getLogger(OutboxPurgeJob.class);
    private final OutboxRepository repo;
    private final int doneDays;
    private final int deadDays;
    private final int batch;
    private final long pauseMs;
    private final int maxBatches;

    OutboxPurgeJob(OutboxRepository repo,
                   @Value("${app.purge.outbox-done-days:7}") int doneDays,
                   @Value("${app.purge.outbox-dead-days:90}") int deadDays,
                   @Value("${app.purge.batch-size:" + BATCH + "}") int batch,
                   @Value("${app.purge.pause-ms:200}") long pauseMs,
                   @Value("${app.purge.max-batches:500}") int maxBatches) {
        this.repo = repo;
        this.doneDays = doneDays;
        this.deadDays = deadDays;
        this.batch = batch;
        this.pauseMs = pauseMs;
        this.maxBatches = maxBatches;
    }

    @Scheduled(cron = "${app.purge.cron:0 15 3 * * *}", zone = "${app.purge.zone:Asia/Kolkata}")
    @SchedulerLock(name = "outbox-purge", lockAtMostFor = "PT2H")
    public void scheduled() {
        purge(batch); // OPS-013: a failure propagates (Spring logs it, retries next run) so job_failures_total counts it
    }

    /** Returns rows deleted. Each batch is its own statement (auto-commit). */
    public int purge(int batchSize) {
        int total = BatchedPurge.run(b -> repo.purgeBatch(doneDays, deadDays, b), batchSize, pauseMs, maxBatches);
        if (total > 0) log.info("outbox purge removed {} finished events", total);
        return total;
    }
}

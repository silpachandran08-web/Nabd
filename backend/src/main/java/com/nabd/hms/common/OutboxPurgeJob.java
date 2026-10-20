package com.nabd.hms.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** OPS-014: keeps outbox_events flat. Deletes done rows after 7 days and dead rows after 90 (dead rows are kept
 * longer for investigation), BATCH rows per statement so no single delete holds locks for long. */
@Component
public class OutboxPurgeJob {

    static final int DONE_DAYS = 7;
    static final int DEAD_DAYS = 90;
    static final int BATCH = 5000;
    private static final int MAX_BATCHES_PER_RUN = 200; // 1M rows/run; the rest waits for the next hour

    private static final Logger log = LoggerFactory.getLogger(OutboxPurgeJob.class);
    private final OutboxRepository repo;

    OutboxPurgeJob(OutboxRepository repo) {
        this.repo = repo;
    }

    @Scheduled(cron = "${app.outbox.purge-cron:0 23 * * * *}")
    @SchedulerLock(name = "outbox-purge", lockAtMostFor = "PT30M")
    public void scheduled() {
        purge(BATCH); // OPS-013: a failure propagates (Spring logs it, retries next run) so job_failures_total counts it
    }

    /** Returns rows deleted. Each batch is its own statement (auto-commit). */
    public int purge(int batch) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            int n = repo.purgeBatch(DONE_DAYS, DEAD_DAYS, batch);
            total += n;
            if (n < batch) {
                break;
            }
        }
        if (total > 0) log.info("outbox purge removed {} finished events", total);
        return total;
    }
}

package com.nabd.hms.shared.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** PLT-026: hourly delete of idempotency_keys rows past their 24 h expiry. */
@Component
public class IdempotencyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);
    private final IdempotencyStore store;

    IdempotencyPurgeJob(IdempotencyStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${app.idempotency.purge-interval-ms:3600000}", initialDelayString = "${app.idempotency.purge-interval-ms:3600000}")
    @SchedulerLock(name = "idempotency-purge", lockAtMostFor = "PT30M")
    public void purge() {
        try {
            int n = store.purgeExpired();
            if (n > 0) log.info("idempotency purge removed {} expired keys", n);
        } catch (RuntimeException e) {
            log.warn("idempotency purge failed; retries next interval", e);
        }
    }
}

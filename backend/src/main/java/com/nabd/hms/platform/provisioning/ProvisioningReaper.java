package com.nabd.hms.platform.provisioning;

import org.slf4j.Logger;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

/**
 * PLT-058: a step left 'running' for more than 10 minutes (crashed or hung node) is failed with error_detail 'timeout',
 * so advance() can claim it again. A retry then sees the step's idempotency key if its effect had committed.
 * One replica runs it at a time (OPS-013 ShedLock); the UPDATE is idempotent anyway.
 */
@Component
class ProvisioningReaper {

    static final Duration STUCK_AFTER = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(ProvisioningReaper.class);

    private final ProvisioningRepository repo;
    private final Clock clock;
    private final TransactionTemplate tx;

    ProvisioningReaper(ProvisioningRepository repo, Clock clock, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${app.provisioning.reaper-interval-ms:60000}", initialDelayString = "${app.provisioning.reaper-interval-ms:60000}")
    @SchedulerLock(name = "provisioning-reaper", lockAtMostFor = "PT10M")
    public void scheduled() {
        try {
            reap();
        } catch (RuntimeException e) {
            log.warn("provisioning reaper failed; retries next interval", e);
        }
    }

    int reap() {
        int n = tx.execute(s -> repo.failStuckSteps(clock.instant().minus(STUCK_AFTER)));
        if (n > 0) log.warn("provisioning reaper failed {} step(s) stuck in running for over {}", n, STUCK_AFTER);
        return n;
    }
}

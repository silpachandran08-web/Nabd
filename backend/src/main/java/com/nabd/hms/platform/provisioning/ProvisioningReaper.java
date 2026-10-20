package com.nabd.hms.platform.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * PLT-058: a step left 'running' for more than 10 minutes (crashed or hung node) is failed with error_detail 'timeout',
 * so advance() can claim it again. A retry then sees the step's idempotency key if its effect had committed.
 * shortcut: plain @Scheduled, every node runs it (the UPDATE is idempotent); add @SchedulerLock once OPS-013 merges.
 */
@Component
class ProvisioningReaper {

    static final Duration STUCK_AFTER = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(ProvisioningReaper.class);

    private final ProvisioningRepository repo;
    private final Clock clock;

    ProvisioningReaper(ProvisioningRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.provisioning.reaper-interval-ms:60000}", initialDelayString = "${app.provisioning.reaper-interval-ms:60000}")
    public void scheduled() {
        try {
            reap();
        } catch (RuntimeException e) {
            log.warn("provisioning reaper failed; retries next interval", e);
        }
    }

    int reap() {
        int n = repo.failStuckSteps(clock.instant().minus(STUCK_AFTER));
        if (n > 0) log.warn("provisioning reaper failed {} step(s) stuck in running for over {}", n, STUCK_AFTER);
        return n;
    }
}

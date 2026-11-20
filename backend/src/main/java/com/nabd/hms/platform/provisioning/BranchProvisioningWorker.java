package com.nabd.hms.platform.provisioning;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PLT-022: runs an owner's add-branch job step by step off the request thread (which is bound to the owner's current clinic
 * and so cannot write the new one). Stops at the first step that does not finish; the owner then sees that step and retries.
 * shortcut: no sweeper. A job orphaned by a restart stays 'running' until the owner presses Retry (steps are claimed by
 * compare-and-set and the PLT-058 reaper frees a stuck one); add a sweeper like TrialConversionWorker's if that shows up.
 */
@Component
class BranchProvisioningWorker {

    private static final Logger log = LoggerFactory.getLogger(BranchProvisioningWorker.class);

    private final ProvisioningService provisioning;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "plt-branch-provisioning");
        t.setDaemon(true);
        return t;
    });

    BranchProvisioningWorker(ProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    void kick(UUID jobId) {
        executor.submit(() -> runToEnd(jobId));
    }

    void runToEnd(UUID jobId) {
        try {
            for (int i = 0; i < OwnerBranchService.STEPS.size(); i++) { // each pass runs one step
                if (!"running".equals(provisioning.advance(jobId, null, null).status())) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            log.warn("add-branch job {} stopped: {}", jobId, e.getClass().getSimpleName());
        }
    }
}

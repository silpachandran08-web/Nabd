package com.nabd.hms.shared.phiread;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** PLT-039: every minute, seal the finished minutes' reads into chained phi_read_batches and keep next months' partitions ready. */
@Component
public class PhiReadSealJob {

    private static final Logger log = LoggerFactory.getLogger(PhiReadSealJob.class);
    private final PhiReadRepository repo;

    PhiReadSealJob(PhiReadRepository repo) {
        this.repo = repo;
    }

    @Scheduled(fixedDelayString = "${app.phi-read.seal-interval-ms:60000}", initialDelayString = "${app.phi-read.seal-interval-ms:60000}")
    @SchedulerLock(name = "phi-read-seal", lockAtMostFor = "PT5M")
    public void scheduled() { // ShedLock cannot lock a method returning a primitive
        run();
    }

    public int run() {
        try {
            repo.ensurePartitions();
            return repo.seal();
        } catch (RuntimeException e) {
            log.warn("phi read sealing failed; unsealed rows are picked up next run: {}", e.toString());
            return 0;
        }
    }
}

package com.nabd.hms.shared.files;

import com.nabd.hms.common.TenantScope;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * PLT-029: scans a completed upload on one background thread, right after the complete call commits.
 * shortcut: no sweep for rows left in pending_scan by a crash; add an @SchedulerLock job when the cell runs more than one replica.
 */
@Component
class FileScanWorker {

    private static final Logger log = LoggerFactory.getLogger(FileScanWorker.class);

    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "file-scan");
        t.setDaemon(true);
        return t;
    });
    private final TenantScope tenantScope;
    private final FileService service;

    FileScanWorker(TenantScope tenantScope, @Lazy FileService service) {
        this.tenantScope = tenantScope;
        this.service = service;
    }

    void submit(UUID tenantId, UUID fileId) {
        pool.execute(() -> {
            try {
                tenantScope.run(tenantId, () -> service.scan(fileId));
            } catch (RuntimeException e) {
                log.error("file scan failed (tenant {}, file {}): {}", tenantId, fileId, e.toString()); // ids only
            }
        });
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }
}

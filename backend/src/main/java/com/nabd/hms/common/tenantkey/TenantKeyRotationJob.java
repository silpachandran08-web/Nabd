package com.nabd.hms.common.tenantkey;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * PLT-045: rotates a region's KMS key when it is older than app.kms.rotation.max-age-days and re-wraps the tenants'
 * DEKs to the newest key version (also finishes a pass that stopped half way). Reads keep working throughout: each
 * DEK row switches wrapped_dek and kms_key_ref in one statement. Off unless app.kms.rotation.enabled=true.
 */
@Component
public class TenantKeyRotationJob {

    private static final Logger log = LoggerFactory.getLogger(TenantKeyRotationJob.class);

    private final TenantKeyService keys;
    private final Clock clock;
    private final boolean enabled;
    private final long maxAgeDays;

    TenantKeyRotationJob(TenantKeyService keys, Clock clock, @Value("${app.kms.rotation.enabled:false}") boolean enabled,
                         @Value("${app.kms.rotation.max-age-days:365}") long maxAgeDays) {
        this.keys = keys;
        this.clock = clock;
        this.enabled = enabled;
        this.maxAgeDays = maxAgeDays;
    }

    @Scheduled(fixedDelayString = "${app.kms.rotation.interval-ms:3600000}", initialDelayString = "${app.kms.rotation.interval-ms:3600000}")
    @SchedulerLock(name = "tenant-key-rotation", lockAtMostFor = "PT1H")
    public void scheduled() {
        if (enabled && keys.enabled()) runOnce();
    }

    /** Returns the number of DEKs re-wrapped. */
    public int runOnce() {
        int rewrapped = 0;
        for (TenantKeyService.RegionKeyInfo r : keys.regionKeys()) {
            Instant since = r.lastRotatedAt() != null ? r.lastRotatedAt() : r.createdAt();
            rewrapped += Duration.between(since, clock.instant()).toDays() >= maxAgeDays ? keys.rotateRegionKey(r.region()) : 0;
        }
        rewrapped += keys.rewrapAll();
        log.info("tenant key rotation pass re-wrapped {} data key(s)", rewrapped);
        return rewrapped;
    }
}

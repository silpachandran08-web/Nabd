package com.nabd.hms.shared.jobs;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** OPS-013: logs the run a replica skipped because another replica holds the job's lock. */
final class SkipLoggingLockProvider implements LockProvider {

    private static final Logger log = LoggerFactory.getLogger(SkipLoggingLockProvider.class);
    private static final long LOG_EVERY_MS = 60_000; // 1-2 s pollers skip constantly on a standby replica
    private final LockProvider delegate;
    private final Clock clock;
    private final Map<String, Long> lastLogged = new ConcurrentHashMap<>();

    SkipLoggingLockProvider(LockProvider delegate, Clock clock) {
        this.delegate = delegate;
        this.clock = clock;
    }

    @Override
    public Optional<SimpleLock> lock(LockConfiguration config) {
        Optional<SimpleLock> lock = delegate.lock(config);
        if (lock.isEmpty()) {
            long now = clock.millis();
            Long prev = lastLogged.get(config.getName());
            if (prev == null || now - prev >= LOG_EVERY_MS) {
                lastLogged.put(config.getName(), now);
                log.info("job {} skipped: locked by another replica", config.getName());
            }
        }
        return lock;
    }
}

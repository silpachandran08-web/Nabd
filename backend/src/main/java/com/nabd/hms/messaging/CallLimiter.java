package com.nabd.hms.messaging;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** COM-003: a bulkhead (max concurrent calls) plus a per-second rate cap for one class of provider calls, so a flood of
 * sends can never starve OTP or template admin. Never waits: when full it throws {@link ProviderBusyException}.
 * shortcut: fixed one-second window, not a sliding one (bursts up to 2x at a window edge); fine against Meta's
 * per-second throughput tiers, swap for Resilience4j's RateLimiter if that ever matters (new dependency needs an ADR). */
final class CallLimiter {

    private final String name;
    private final Semaphore slots;
    private final int perSecond;
    private long windowStart;
    private int used;

    CallLimiter(String name, int maxConcurrent, int perSecond) {
        this.name = name;
        this.slots = new Semaphore(maxConcurrent);
        this.perSecond = perSecond;
    }

    <T> T run(Supplier<T> call) {
        if (!allowRate() || !slots.tryAcquire()) {
            throw new ProviderBusyException(name);
        }
        try {
            return call.get();
        } finally {
            slots.release();
        }
    }

    private synchronized boolean allowRate() {
        long now = System.currentTimeMillis() / 1000;
        if (now != windowStart) {
            windowStart = now;
            used = 0;
        }
        return ++used <= perSecond;
    }
}

package com.nabd.hms.messaging;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** COM-003 unit: the bulkhead and rate cap that keep one class of Meta calls from starving another. */
class CallLimiterTest {

    @Test
    void aFullBulkheadRefusesAtOnceWhileAnotherClassStillRuns() throws Exception {
        CallLimiter sends = new CallLimiter("sends", 1, 1000);
        CallLimiter otp = new CallLimiter("otp", 1, 1000);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread slow = new Thread(() -> sends.run(() -> {
            inside.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        slow.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> sends.run(() -> "second send")).isInstanceOf(ProviderBusyException.class);
        assertThat(otp.run(() -> "otp ok")).isEqualTo("otp ok");
        release.countDown();
        slow.join();
        assertThat(sends.run(() -> "free again")).isEqualTo("free again");
    }

    @Test
    void theRateCapRefusesTheCallAfterTheLimitWithinOneSecond() {
        CallLimiter l = new CallLimiter("sends", 10, 3);
        long second = System.currentTimeMillis() / 1000;
        int ok = 0;
        int refused = 0;
        for (int i = 0; i < 6; i++) {
            try {
                l.run(() -> "x");
                ok++;
            } catch (ProviderBusyException e) {
                refused++;
            }
        }
        if (System.currentTimeMillis() / 1000 == second) { // no window edge crossed while looping
            assertThat(ok).isEqualTo(3);
            assertThat(refused).isEqualTo(3);
        }
    }
}

package com.nabd.hms.shared.jobs;

import com.nabd.hms.shared.BatchedPurge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OPS-013: job metrics on success and failure (no Spring context), and the batch loop. */
class JobRegistryTest {

    public static class Jobs {
        boolean fail;

        @SchedulerLock(name = "t-job")
        public void run() {
            if (fail) throw new IllegalStateException("boom");
        }
    }

    @Test
    void successSetsLastSuccessAndDuration_failureCountsAndRethrows() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        JobRegistry registry = new JobRegistry(meters, Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC));
        Jobs target = new Jobs();
        ProxyFactory pf = new ProxyFactory(target);
        pf.addAdvice((MethodInterceptor) registry::record);
        Jobs job = (Jobs) pf.getProxy();

        job.run();
        assertThat(meters.get("job.last.success.timestamp").tag("job", "t-job").gauge().value()).isEqualTo(1792454400.0);
        assertThat(meters.get("job.duration").tag("job", "t-job").timer().count()).isEqualTo(1);
        assertThat(meters.get("job.failures").tag("job", "t-job").counter().count()).isZero();

        target.fail = true;
        assertThatThrownBy(job::run).hasMessage("boom");
        assertThat(meters.get("job.failures").tag("job", "t-job").counter().count()).isEqualTo(1);
        assertThat(meters.get("job.duration").tag("job", "t-job").timer().count()).isEqualTo(2);
        assertThat(meters.get("job.last.success.timestamp").tag("job", "t-job").gauge().value()).isEqualTo(1792454400.0);
    }

    @Test
    void batchLoopStopsOnShortBatchOrMaxBatches() {
        List<Integer> remaining = new ArrayList<>(List.of(25_000));
        int deleted = BatchedPurge.run(b -> {
            int n = Math.min(b, remaining.get(0));
            remaining.set(0, remaining.get(0) - n);
            return n;
        }, 10_000, 0, 500);
        assertThat(deleted).isEqualTo(25_000);
        assertThat(BatchedPurge.run(b -> b, 10_000, 0, 3)).isEqualTo(30_000); // capped, rest waits for next night
    }
}

package com.nabd.hms.shared.jobs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OPS-013: metrics for every @SchedulerLock job, labelled job=<lock name> (bounded, no tenant data, OPS-025):
 * job_last_success_timestamp (epoch seconds), job_duration_seconds, job_failures_total. A job counts as failed only
 * when its method throws; jobs that catch and log their own errors report success.
 */
@Component
public class JobRegistry {

    private final MeterRegistry meters;
    private final Clock clock;
    private final Map<String, AtomicLong> lastSuccess = new ConcurrentHashMap<>();

    JobRegistry(MeterRegistry meters, Clock clock) {
        this.meters = meters;
        this.clock = clock;
    }

    Object record(MethodInvocation inv) throws Throwable {
        SchedulerLock lock = AnnotatedElementUtils.findMergedAnnotation(inv.getMethod(), SchedulerLock.class);
        String job = lock == null ? inv.getMethod().getName() : lock.name();
        AtomicLong last = lastSuccess.computeIfAbsent(job, j -> {
            AtomicLong v = new AtomicLong();
            Gauge.builder("job.last.success.timestamp", v, AtomicLong::get).tag("job", j)
                    .description("epoch seconds of the job's last successful run (0 = none since start)").register(meters);
            return v;
        });
        Counter failures = Counter.builder("job.failures").tag("job", job).description("runs that threw").register(meters);
        Timer.Sample sample = Timer.start(meters);
        try {
            Object result = inv.proceed();
            last.set(clock.instant().getEpochSecond());
            return result;
        } catch (Throwable t) {
            failures.increment();
            throw t;
        } finally {
            sample.stop(Timer.builder("job.duration").tag("job", job).description("job run time").register(meters));
        }
    }
}

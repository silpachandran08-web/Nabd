package com.nabd.hms.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** OPS-041: runs N tasks on a fixed pool (one thread each) released together by a latch, so the race window is as wide as possible. */
public final class ConcurrencyHarness {

    private ConcurrencyHarness() {
    }

    public static <T> List<T> race(List<Callable<T>> tasks) {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workers did not all start");
            }
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException("race failed", e);
        } finally {
            pool.shutdownNow();
        }
    }

    public static <T> List<T> raceSame(int n, Callable<T> task) {
        return race(java.util.Collections.nCopies(n, task));
    }
}

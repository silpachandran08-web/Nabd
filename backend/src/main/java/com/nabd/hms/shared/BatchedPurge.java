package com.nabd.hms.shared;

import java.util.function.IntUnaryOperator;

/** OPS-013: retention purge loop (module API of shared, used by the owning modules' purge jobs). Each batch is its own statement (auto-commit), with a pause so autovacuum keeps up. */
public final class BatchedPurge {

    private BatchedPurge() {
    }

    /** Calls purgeBatch(batch) until a batch comes back short or maxBatches ran; returns rows deleted. */
    public static int run(IntUnaryOperator purgeBatch, int batch, long pauseMs, int maxBatches) {
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int n = purgeBatch.applyAsInt(batch);
            total += n;
            if (n < batch) {
                break;
            }
            if (pauseMs > 0) {
                try {
                    Thread.sleep(pauseMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return total;
    }
}

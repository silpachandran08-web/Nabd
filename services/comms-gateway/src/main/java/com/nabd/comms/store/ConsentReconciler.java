package com.nabd.comms.store;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * COM-008: nightly reconciliation of the snapshot against Core's consent export (GET /internal/v1/consents:export, paged).
 * Corrects every row whose granted flag differs and counts it in comms_consent_drift_total{tenant is not a tag: ids stay out of metrics}.
 */
public class ConsentReconciler {

    public record Item(UUID patientId, String purpose, boolean granted, long version) {}

    public record Page(List<Item> items, String nextCursor) {}

    /** The HTTP client for the export endpoint (internal identity); a fake in tests. */
    public interface ExportSource {
        Page page(UUID tenantId, String cursor);
    }

    private final ConsentSnapshotStore store;
    private final ExportSource source;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;

    public ConsentReconciler(ConsentSnapshotStore store, ExportSource source, TransactionTemplate tx, MeterRegistry metrics) {
        this.store = store;
        this.source = source;
        this.tx = tx;
        this.metrics = metrics;
    }

    /** @return the number of corrected rows (drift) */
    public long reconcile(UUID tenantId) {
        long drift = 0;
        String cursor = null;
        do {
            Page page = source.page(tenantId, cursor);
            drift += tx.execute(s -> page.items().stream()
                    .filter(i -> store.force(tenantId, i.patientId(), i.purpose(), i.granted(), i.version())).count());
            cursor = page.nextCursor();
        } while (cursor != null);
        metrics.counter("comms_consent_drift_total").increment(drift);
        return drift;
    }
}

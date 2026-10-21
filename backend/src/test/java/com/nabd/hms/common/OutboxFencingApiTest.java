package com.nabd.hms.common;

import com.nabd.hms.shared.ops.OutboxMetrics;
import com.nabd.hms.support.ApiTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-014: fencing on every completion, batched retention purge, and the per-region backlog gauges. */
@ExtendWith(OutputCaptureExtension.class)
class OutboxFencingApiTest extends ApiTestBase {

    @Autowired
    private OutboxRepository repo;
    @Autowired
    private OutboxEventProcessor processor;
    @Autowired
    private OutboxPurgeJob purgeJob;
    @Autowired
    private OutboxMetrics metrics;
    @Autowired
    private MeterRegistry meters;
    @LocalManagementPort
    private int mgmtPort;

    private double fencingRejected() {
        return meters.counter("outbox.fencing.rejected").count();
    }

    private long insert(SeededTenant t, String status, int attempts, String extraCols, String extraVals) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject(
                "INSERT INTO outbox_events (tenant_id, event_type, payload, status, attempts" + extraCols + ") "
                        + "VALUES (?, ?, '{}'::jsonb, ?, ?" + extraVals + ") RETURNING id",
                Long.class, t.id(), TestEchoOutboxHandler.EVENT_TYPE, status, attempts));
    }

    private Map<String, Object> row(SeededTenant t, long id) {
        return inTenantTx(t.id(), () -> jdbc.queryForMap("SELECT status, attempts, last_error FROM outbox_events WHERE id = ?", id));
    }

    /** A worker claimed attempt 1, its lease ran out and another worker reclaimed the event as attempt 2. */
    private OutboxEvent[] staleAndOwner(SeededTenant t) {
        long id = insert(t, "processing", 1, ", locked_until", ", now() - interval '1 second'");
        List<OutboxEvent> claimed = repo.claim(20, 60);
        OutboxEvent owner = claimed.stream().filter(e -> e.id() == id).findFirst().orElseThrow();
        assertThat(owner.attempts()).isEqualTo(2);
        return new OutboxEvent[]{new OutboxEvent(id, t.id(), TestEchoOutboxHandler.EVENT_TYPE, "{}", 1), owner};
    }

    @Test
    void staleWorkerMarkDoneUpdatesNothingAndIsCounted(CapturedOutput output) throws Exception {
        SeededTenant t = seedTenant();
        OutboxEvent[] e = staleAndOwner(t);
        double before = fencingRejected();

        processor.runHandler(e[0]); // the stale worker finishes late

        assertThat(row(t, e[0].id())).containsEntry("status", "processing").containsEntry("attempts", 2);
        assertThat(fencingRejected()).isEqualTo(before + 1);
        assertThat(output).contains("outbox_fencing_rejected_total: markDone on event " + e[0].id());

        processor.runHandler(e[1]); // the real owner still completes it
        assertThat(row(t, e[1].id())).containsEntry("status", "done");
    }

    @Test
    void staleMarkFailedOrMarkDeadCannotResetADoneEvent() throws Exception {
        SeededTenant t = seedTenant();
        OutboxEvent[] e = staleAndOwner(t);
        processor.runHandler(e[1]);
        double before = fencingRejected();

        processor.recordFailure(e[0], new RuntimeException("provider timed out"));
        OutboxEvent staleAtMax = new OutboxEvent(e[0].id(), t.id(), e[0].eventType(), "{}", 8);
        processor.recordFailure(staleAtMax, new RuntimeException("provider timed out"));

        assertThat(row(t, e[0].id())).containsEntry("status", "done").containsEntry("last_error", null);
        assertThat(fencingRejected()).isEqualTo(before + 2);
    }

    @Test
    void leaseRenewalIsFencedToTheCurrentOwner() {
        SeededTenant t = seedTenant();
        OutboxEvent[] e = staleAndOwner(t);
        Instant lockedBefore = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT locked_until FROM outbox_events WHERE id = ?", java.sql.Timestamp.class, e[1].id())).toInstant();

        assertThat(repo.extendLeases(List.of(e[0]), 600)).isEmpty();
        assertThat(repo.extendLeases(List.of(e[0], e[1]), 600)).containsExactly(e[1].id());
        Instant lockedAfter = inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT locked_until FROM outbox_events WHERE id = ?", java.sql.Timestamp.class, e[1].id())).toInstant();
        assertThat(lockedAfter).isAfter(lockedBefore.plusSeconds(500));
    }

    @Test
    void purgeDeletesOldDoneAndDeadRowsInBatchesAndKeepsTheRest() {
        SeededTenant t = seedTenant();
        String old = ", updated_at";
        long done8 = insert(t, "done", 1, old, ", now() - interval '8 days'");
        insert(t, "done", 1, old, ", now() - interval '8 days'");
        insert(t, "done", 1, old, ", now() - interval '30 days'");
        long done6 = insert(t, "done", 1, old, ", now() - interval '6 days'");
        long dead91 = insert(t, "dead", 8, old, ", now() - interval '91 days'");
        insert(t, "dead", 8, old, ", now() - interval '200 days'");
        long dead89 = insert(t, "dead", 8, old, ", now() - interval '89 days'");
        long pendingOld = insert(t, "pending", 0, old, ", now() - interval '100 days'");

        int purged = purgeJob.purge(2); // 5 eligible rows, batch of 2: three statements

        assertThat(purged).isGreaterThanOrEqualTo(5);
        List<Long> left = inTenantTx(t.id(), () -> jdbc.queryForList("SELECT id FROM outbox_events WHERE tenant_id = ? ORDER BY id", Long.class, t.id()));
        assertThat(left).containsExactly(done6, dead89, pendingOld).doesNotContain(done8, dead91);
    }

    @Test
    void steadyTrafficStaysFlatAtSevenDaysOfDoneRows() {
        // 10 finished events per day for 14 days: after the purge only the last 7 days remain, i.e. the table is bounded
        // by 7 x daily volume however long the system runs (the 7-day soak itself runs outside this sandbox).
        SeededTenant t = seedTenant();
        for (int d = 0; d < 14; d++) {
            int day = d;
            inTenantTx(t.id(), () -> jdbc.update("""
                    INSERT INTO outbox_events (tenant_id, event_type, payload, status, attempts, updated_at)
                    SELECT ?, 'test.echo', '{}'::jsonb, 'done', 1, now() - make_interval(days => ?, hours => 1) FROM generate_series(1, 10)
                    """, t.id(), day));
        }
        purgeJob.purge(OutboxPurgeJob.BATCH);
        purgeJob.purge(OutboxPurgeJob.BATCH); // a second run (next hour) removes nothing more
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ?", Integer.class, t.id())))
                .isEqualTo(70);
    }

    @Test
    void backlogGaugesArePerRegionWithNoTenantLabel() throws Exception {
        SeededTenant t = seedTenant(); // region IN
        insert(t, "pending", 0, ", created_at", ", now() - interval '120 seconds'");
        insert(t, "pending", 0, "", "");
        insert(t, "processing", 1, ", locked_until", ", now() + interval '1 minute'");
        insert(t, "dead", 8, "", "");
        insert(t, "done", 1, "", "");

        metrics.refresh();

        assertThat(meters.get("outbox.pending.events").tag("region", "IN").gauge().value()).isEqualTo(3); // leftovers were settled to done
        assertThat(meters.get("outbox.dead.events").tag("region", "IN").gauge().value()).isGreaterThanOrEqualTo(1);
        assertThat(meters.get("outbox.oldest.pending.age.seconds").tag("region", "IN").gauge().value()).isBetween(119.0, 600.0);
        assertThat(meters.get("outbox.pending.events").tag("region", "SA").gauge().value()).isZero();

        HttpResponse<String> scrape = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + mgmtPort + "/actuator/prometheus")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(scrape.body()).contains("outbox_pending_events{region=\"IN\"} 3.0", "outbox_pending_events{region=\"SA\"} 0.0",
                "outbox_dead_events{region=\"IN\"}", "outbox_oldest_pending_age_seconds{region=\"IN\"}", "outbox_fencing_rejected_total");
        assertThat(scrape.body().lines().filter(l -> l.startsWith("outbox_") && l.contains("tenant"))).isEmpty();
    }
}

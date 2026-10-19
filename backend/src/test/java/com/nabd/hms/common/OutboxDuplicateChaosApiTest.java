package com.nabd.hms.common;

import com.nabd.hms.config.OutboxProperties;
import com.nabd.hms.shared.ops.OutboxLag;
import com.nabd.hms.support.SlowProviderTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPS-014 / QA-29 chaos test: two dispatcher instances drain the same outbox while the (real, HTTP) WhatsApp client
 * talks to a slow provider stub, and the stub counts deliveries per message. Scaled for a 2-CPU sandbox: the spec's
 * 1,000 messages x 12 s latency vs a 60 s lease becomes 200 messages x 100 ms vs a 1 s lease with batches of 20,
 * which keeps the property that matters: one batch (2 s) outlives its lease (1 s).
 */
class OutboxDuplicateChaosApiTest extends SlowProviderTestBase {

    private static final int MESSAGES = 200;

    @Autowired
    private OutboxRepository repo;
    @Autowired
    private OutboxEventProcessor processor;
    @Autowired
    private OutboxLag lag;
    @Autowired
    private MeterRegistry meters;

    private SeededTenant queueMessages(int n) {
        SeededTenant t = seedTenant();
        inTenantTx(t.id(), () -> {
            for (int i = 0; i < n; i++) {
                var id = jdbc.queryForObject("""
                        INSERT INTO whatsapp_messages (tenant_id, to_phone, template_name, language, params)
                        VALUES (?, '+919800000000', 'appointment_reminder', 'en', jsonb_build_array(?::text)) RETURNING id
                        """, java.util.UUID.class, t.id(), "m-" + i);
                jdbc.update("INSERT INTO outbox_events (tenant_id, event_type, payload) VALUES (?, 'whatsapp.send', jsonb_build_object('messageId', ?::text))",
                        t.id(), id.toString());
            }
        });
        return t;
    }

    private int unfinished(SeededTenant t) {
        return inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND status <> 'done'", Integer.class, t.id()));
    }

    /** b polls with smaller batches so it comes back while a is still mid-batch (equal batches would run in lockstep). */
    private void drainWithTwoDispatchers(SeededTenant t, OutboxProperties props) throws Exception {
        OutboxDispatcher a = new OutboxDispatcher(repo, processor, props, lag);
        OutboxDispatcher b = new OutboxDispatcher(repo, processor, new OutboxProperties(0, 5, props.leaseSeconds(),
                props.perEventTimeoutMs(), props.extendLeasePerEvent()), lag);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        List<Future<?>> runs = new ArrayList<>();
        for (OutboxDispatcher d : List.of(a, b)) {
            runs.add(pool.submit(() -> {
                while (unfinished(t) > 0 && System.nanoTime() < deadline) {
                    d.poll();
                    Thread.sleep(10);
                }
                return null;
            }));
        }
        for (Future<?> f : runs) {
            f.get(200, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(unfinished(t)).as("every event finished within the deadline").isZero();
    }

    private void assertEachMessageSentExactlyOnce(SeededTenant t) {
        assertThat(STUB.sendsByMarker).hasSize(MESSAGES);
        assertThat(STUB.maxSendsPerMarker()).as("duplicate deliveries at the provider").isEqualTo(1);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM whatsapp_messages WHERE tenant_id = ? AND status = 'sent' AND wamid IS NOT NULL", Integer.class, t.id())))
                .isEqualTo(MESSAGES);
    }

    @Test
    void leasesExpireMidBatchYetNoMessageIsSentTwice() throws Exception {
        // Worst case: no lease renewal and a declared 40 ms per-event timeout the provider does not honour, so leases
        // expire mid-batch and the other instance reclaims. Row lock + fencing must still give 0 duplicates.
        STUB.reset(100);
        SeededTenant t = queueMessages(MESSAGES);
        double fencedBefore = meters.counter("outbox.fencing.rejected").count();

        drainWithTwoDispatchers(t, new OutboxProperties(0, 20, 1, 40, false));

        assertEachMessageSentExactlyOnce(t);
        assertThat(meters.counter("outbox.fencing.rejected").count() - fencedBefore)
                .as("the race really happened: stale completions were fenced out").isPositive();
    }

    @Test
    void shippedModeRenewsLeasesSoNothingIsReclaimed() throws Exception {
        // extend-lease-per-event (the application.yml default): waiting events keep their lease while the provider is slow
        STUB.reset(100);
        SeededTenant t = queueMessages(MESSAGES);

        drainWithTwoDispatchers(t, new OutboxProperties(0, 20, 3, 900, true));

        assertEachMessageSentExactlyOnce(t);
        assertThat(inTenantTx(t.id(), () -> jdbc.queryForObject(
                "SELECT max(attempts) FROM outbox_events WHERE tenant_id = ?", Integer.class, t.id())))
                .as("no event needed a second claim").isEqualTo(1);
    }
}

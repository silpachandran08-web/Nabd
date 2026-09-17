package com.nabd.hms.common;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** NB-007's acceptance criterion, proven directly: "zero message loss under forced consumer
 * failure" — both forms of it. dispatcher.poll() is called synchronously rather than waiting on
 * the real @Scheduled timer, so this is deterministic with no sleep/Awaitility needed. */
class OutboxDispatcherApiTest extends ApiTestBase {

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private EventPublisher publisher;

    @Autowired
    private TestEchoOutboxHandler handler;

    @Test
    void zeroMessageLossUnderForcedConsumerFailure() {
        SeededTenant tenant = seedTenant();

        // Case 1: the dispatcher process is killed mid-processing (lease never completes) —
        // simulate by directly writing a 'processing' row with an already-expired lease, exactly
        // the state a real claim_outbox_events() call leaves behind if the JVM dies before
        // markDone/recordFailure ever runs.
        inTenantTx(tenant.id(), () -> jdbc.update("""
                INSERT INTO outbox_events (tenant_id, event_type, payload, status, attempts, locked_until)
                VALUES (?, ?, '{}'::jsonb, 'processing', 1, now() - interval '1 minute')
                """, tenant.id(), TestEchoOutboxHandler.EVENT_TYPE));

        dispatcher.poll(); // must reclaim the stuck row and deliver it

        // Every direct read/write against outbox_events (RLS-protected) needs app.tenant_id set
        // first, same as the insert above — TenantContext's SET LOCAL only lives for the
        // transaction that set it, so each call below needs its own inTenantTx.
        Map<String, Object> reclaimed = inTenantTx(tenant.id(), () -> jdbc.queryForMap(
                "SELECT status, attempts FROM outbox_events WHERE tenant_id = ?", tenant.id()));
        assertThat(reclaimed.get("status")).isEqualTo("done");
        assertThat(reclaimed.get("attempts")).isEqualTo(2);

        // Case 2: the handler itself throws — must not be lost, just retried with backoff, then
        // eventually delivered.
        handler.failNextInvocations(1);
        inTenantTx(tenant.id(), () -> publisher.publish(tenant.id(), TestEchoOutboxHandler.EVENT_TYPE, Map.of("k", "v")));

        dispatcher.poll(); // attempt 1 fails, goes back to pending with backoff
        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE tenant_id = ? AND attempts = 1",
                String.class, tenant.id()))).isEqualTo("pending");

        inTenantTx(tenant.id(), () -> jdbc.update(
                "UPDATE outbox_events SET next_attempt_at = now() WHERE tenant_id = ? AND status = 'pending'", tenant.id()));
        dispatcher.poll(); // attempt 2 succeeds

        assertThat(inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE tenant_id = ? AND payload->>'k' = 'v'",
                String.class, tenant.id()))).isEqualTo("done");
    }
}

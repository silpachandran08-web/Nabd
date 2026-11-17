package com.nabd.hms.billing;

import com.nabd.hms.common.DomainEventType;
import com.nabd.hms.common.EventPublisher;
import com.nabd.hms.common.OutboxDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-035: checkout settles the visit's bill (billing.invoice.settled.v1) and completes the visit only through queue.api.VisitCommands,
 * in one transaction; the visit module consumes the event (status + realtime board source event within 2 s, redelivery a no-op).
 */
class Plt035VisitSettlementApiTest extends S10QaBillingSupport {

    @Autowired EventPublisher publisher;
    @Autowired OutboxDispatcher dispatcher;

    private String status(Env e, String entry) {
        return inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT status FROM queue_entries WHERE id = ?", String.class, UUID.fromString(entry)));
    }

    private long mark(Env e) {
        Long m = inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT COALESCE(max(id), 0) FROM outbox_events WHERE tenant_id = ?", Long.class, e.tenant().id()));
        return m == null ? 0 : m;
    }

    /** [id, type, data json] of the tenant's outbox rows after the mark, oldest first. */
    private List<Map<String, Object>> since(Env e, long mark) {
        return inTenantTx(e.tenant().id(), () -> jdbc.queryForList(
                "SELECT id, envelope->>'type' AS type, envelope->'data' AS data FROM outbox_cloudevents WHERE tenant_id = ? AND id > ? ORDER BY id",
                e.tenant().id(), mark));
    }

    private static List<String> types(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> (String) r.get("type")).toList();
    }

    @Test
    void terminalCheckoutPublishesSettledThenCompletesTheVisitInTheSameTransaction() {
        Env e = env();
        String entry = atCheckout(e);
        long m = mark(e);
        ResponseEntity<Map> r = checkout(e.token(), entry, List.of(line("SVC", 1)));
        assertThat(r.getStatusCode().is2xxSuccessful()).as(String.valueOf(r.getBody())).isTrue();
        assertThat(status(e, entry)).isEqualTo("completed");

        List<String> t = types(since(e, m));
        assertThat(t).contains("billing.invoice.settled.v1", "visit.queue_entry.changed.v1", "visit.completed.v1");
        assertThat(t.indexOf("billing.invoice.settled.v1")).isLessThan(t.indexOf("visit.completed.v1")); // settled first, then completed
        String data = since(e, m).stream().filter(x -> x.get("type").equals("billing.invoice.settled.v1")).findFirst().orElseThrow().get("data").toString();
        assertThat(data).contains("\"invoiceId\": \"" + r.getBody().get("id") + "\"", "\"visitId\": \"" + entry + "\"",
                "\"settledBy\": \"" + e.owner().id() + "\"", "\"settledAt\"");
        assertThat(num(r.getBody().get("paid"))).isZero(); // settled at checkout; the balance is carried as dues
    }

    @Test
    void interimBillingStopAdvancesThroughVisitCommandsWithoutSettling() {
        Env e = env();
        String entry = atInterimBilling(e);
        long m = mark(e);
        assertThat(checkout(e.token(), entry, List.of(line("CONS", 1))).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(status(e, entry)).isNotIn("billing_pending", "completed");
        assertThat(types(since(e, m))).doesNotContain("billing.invoice.settled.v1", "visit.completed.v1").contains("visit.queue_entry.changed.v1");
    }

    @Test
    void settledEventCompletesAVisitStillAtCheckoutAndFeedsTheBoardWithin2s_redeliveryIsANoOp() {
        Env e = env();
        String entry = atCheckout(e);
        UUID t = e.tenant().id();
        long m = mark(e);
        inTenantTx(t, () -> publisher.publish(t, DomainEventType.INVOICE_SETTLED, UUID.randomUUID(), Map.of("invoiceId", UUID.randomUUID(),
                "visitId", UUID.fromString(entry), "settledAt", Instant.now(), "settledBy", e.owner().id())));

        long t0 = System.nanoTime();
        for (int i = 0; i < 20 && !"completed".equals(status(e, entry)); i++) dispatcher.poll();
        double seconds = (System.nanoTime() - t0) / 1e9;
        assertThat(status(e, entry)).isEqualTo("completed");
        assertThat(seconds).isLessThan(2.0);
        // the board's source event (sse-fanout routes visit.queue_entry.changed.v1 to the queue topic)
        assertThat(since(e, m)).anySatisfy(x -> {
            assertThat(x.get("type")).isEqualTo("visit.queue_entry.changed.v1");
            assertThat(x.get("data").toString()).contains("\"entryId\": \"" + entry + "\"", "\"status\": \"completed\"");
        });

        // a redelivery (or the normal case: checkout already completed the visit) changes nothing
        long after = mark(e);
        inTenantTx(t, () -> publisher.publish(t, DomainEventType.INVOICE_SETTLED, UUID.randomUUID(), Map.of("invoiceId", UUID.randomUUID(),
                "visitId", UUID.fromString(entry), "settledAt", Instant.now(), "settledBy", e.owner().id())));
        String undelivered = "SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND event_type = 'billing.invoice.settled.v1' AND status <> 'done'";
        for (int i = 0; i < 20 && count(e, undelivered, t) > 0; i++) dispatcher.poll();
        assertThat(count(e, undelivered, t)).isZero(); // delivered, not dead-lettered
        assertThat(types(since(e, after))).containsExactly("billing.invoice.settled.v1");
        assertThat(status(e, entry)).isEqualTo("completed");
    }
}

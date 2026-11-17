package com.nabd.hms.billing;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** S24 QA (t1) PLT-035: one settled + one completed event per visit under replay and concurrency; desk numbers; visit-length source. */
@SuppressWarnings({"unchecked", "rawtypes"})
class S24QaPlt035CheckoutApiTest extends S10QaBillingSupport {

    private int events(Env e, String type, String entry) {
        return inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM outbox_cloudevents WHERE tenant_id = ? AND envelope->>'type' = ? "
                + "AND envelope::text LIKE ?", Integer.class, e.tenant().id(), type, "%" + entry + "%"));
    }

    private ResponseEntity<Map> checkoutWithKey(Env e, String entry, String key) {
        return exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, withKey(e.token(), key, Map.of("lineItems", List.of(line("SVC", 1)))), Map.class);
    }

    // P35-A02: same-key replay and two concurrent checkouts of one visit: one invoice, one settled, one completed
    @Test
    void p35a02_replayAndConcurrentCheckoutSettleOnce() throws Exception {
        Env e = env();
        String entry = atCheckout(e);
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> a = checkoutWithKey(e, entry, key);
        ResponseEntity<Map> b = checkoutWithKey(e, entry, key);
        ResponseEntity<Map> c = checkoutWithKey(e, entry, UUID.randomUUID().toString());
        System.out.println("P35-A02 replay first=" + a.getStatusCode() + " sameKey=" + b.getStatusCode() + " sameInvoice=" + (a.getBody().get("id") != null && a.getBody().get("id").equals(b.getBody().get("id")))
                + " newKey=" + c.getStatusCode() + " settled=" + events(e, "billing.invoice.settled.v1", entry) + " completed=" + events(e, "visit.completed.v1", entry));
        assertThat(a.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(b.getBody().get("id")).isEqualTo(a.getBody().get("id"));
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM invoices WHERE queue_entry_id = ?::uuid", Integer.class, entry))).isEqualTo(1);
        assertThat(events(e, "billing.invoice.settled.v1", entry)).isEqualTo(1);
        assertThat(events(e, "visit.completed.v1", entry)).isEqualTo(1);

        String entry2 = atCheckout(e);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 2; i++) fs.add(pool.submit(() -> {
            go.await();
            return checkoutWithKey(e, entry2, UUID.randomUUID().toString()).getStatusCode().value();
        }));
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : fs) codes.add(f.get());
        pool.shutdown();
        int invoices = inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM invoices WHERE queue_entry_id = ?::uuid", Integer.class, entry2));
        System.out.println("P35-A02 concurrent=" + codes + " invoices=" + invoices + " settled=" + events(e, "billing.invoice.settled.v1", entry2)
                + " completed=" + events(e, "visit.completed.v1", entry2));
        assertThat(invoices).isEqualTo(1);
        assertThat(events(e, "billing.invoice.settled.v1", entry2)).isEqualTo(1);
        assertThat(events(e, "visit.completed.v1", entry2)).isEqualTo(1);
    }

    // P35-A04 + A05: desk summary moves one visit from in-flow to completed; the completed transition carries the doctor (visit-length source)
    @Test
    void p35a04_a05_deskSummaryAndVisitLengthSource() {
        Env e = env();
        String done = atCheckout(e);
        String waiting = atCheckout(e);
        Map<String, Object> before = (Map<String, Object>) exchange("/v1/reports/desk-summary", HttpMethod.GET, authed(e.token()), Map.class).getBody().get("visits");
        assertThat(checkout(e.token(), done, List.of(line("SVC", 1))).getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> deskAfter = exchange("/v1/reports/desk-summary", HttpMethod.GET, authed(e.token()), Map.class).getBody();
        Map<String, Object> after = (Map<String, Object>) deskAfter.get("visits");
        List<Map<String, Object>> transitions = inTenantTx(e.tenant().id(), () -> jdbc.queryForList(
                "SELECT doctor_id, occurred_at >= (SELECT created_at FROM queue_entries WHERE id = ?::uuid) AS after_checkin FROM queue_entry_events "
                        + "WHERE queue_entry_id = ?::uuid AND kind = 'transition' AND to_status = 'completed'", done, done));
        System.out.println("P35-A04 desk visits before=" + before + " after=" + after + " unbilled=" + deskAfter.get("unbilled")
                + " | P35-A05 completed transitions=" + transitions);
        assertThat(((Number) after.get("total")).intValue()).isEqualTo(((Number) before.get("total")).intValue());
        assertThat(((Number) after.get("completed")).intValue()).isEqualTo(((Number) before.get("completed")).intValue() + 1);
        assertThat(((Number) after.get("inFlow")).intValue()).isEqualTo(((Number) before.get("inFlow")).intValue() - 1);
        assertThat(String.valueOf(deskAfter.get("unbilled"))).doesNotContain(done);
        assertThat(transitions).singleElement().satisfies(t -> {
            assertThat(t.get("doctor_id")).isEqualTo(e.owner().id());
            assertThat(t.get("after_checkin")).isEqualTo(true);
        });
        assertThat(waiting).isNotNull();
    }
}

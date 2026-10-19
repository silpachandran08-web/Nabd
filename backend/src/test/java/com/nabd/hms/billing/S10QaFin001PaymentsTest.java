package com.nabd.hms.billing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent QA (S10) for FIN-001: payments are atomic and the invoice balance stays collectable. Written from the story. */
class S10QaFin001PaymentsTest extends S10QaBillingSupport {

    @Autowired PaymentConsistencyJob consistencyJob;

    private <T> List<T> parallel(int n, java.util.function.IntFunction<Callable<T>> work) throws Exception {
        ExecutorService ex = Executors.newFixedThreadPool(n);
        CyclicBarrier gate = new CyclicBarrier(n);
        List<Future<T>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<T> c = work.apply(i);
            fs.add(ex.submit(() -> { gate.await(); return c.call(); }));
        }
        List<T> out = new ArrayList<>();
        for (Future<T> f : fs) out.add(f.get());
        ex.shutdown();
        return out;
    }

    private BigDecimal sumPayments(Env e, String invoiceId) {
        return inTenantTx(e.tenant().id(), () -> jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM invoice_payments WHERE invoice_id = ?::uuid", BigDecimal.class, invoiceId));
    }

    private BigDecimal paidColumn(Env e, String invoiceId) {
        return inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT paid FROM invoices WHERE id = ?::uuid", BigDecimal.class, invoiceId));
    }

    private int paymentRows(Env e, String invoiceId) {
        return count(e, "SELECT count(*) FROM invoice_payments WHERE invoice_id = ?::uuid", invoiceId);
    }

    // AC1
    @Test
    void twoCashiersPostingAtTheSameInstantBothLand() throws Exception {
        Env e = env();
        String inv = invoiceOf(e, "SVC"); // 500
        String c1 = tokenFor(e, "Cashier1", true, false, true, false, false);
        String c2 = tokenFor(e, "Cashier2", true, false, true, false, false);
        List<ResponseEntity<Map>> rs = parallel(2, i -> () -> i == 0 ? pay(c1, inv, "cash", 300) : pay(c2, inv, "upi", 200));
        assertThat(rs).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        ResponseEntity<Map> g = getInvoice(e.token(), inv);
        assertThat(num(g.getBody().get("paid"))).isEqualTo(500.0);
        assertThat(g.getBody().get("status")).isEqualTo("paid");
        assertThat(paidColumn(e, inv)).isEqualByComparingTo(sumPayments(e, inv));
        assertThat(paymentRows(e, inv)).isEqualTo(2);
    }

    @Test
    void tenCashiersPostingFiftyEachOn500AllLand() throws Exception {
        Env e = env();
        String inv = invoiceOf(e, "SVC");
        List<ResponseEntity<Map>> rs = parallel(10, i -> () -> pay(e.token(), inv, "cash", 50));
        assertThat(rs).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(paidColumn(e, inv)).isEqualByComparingTo("500");
        assertThat(sumPayments(e, inv)).isEqualByComparingTo("500");
        assertThat(getInvoice(e.token(), inv).getBody().get("status")).isEqualTo("paid");
    }

    @Test
    void racingOverpaymentsNeverExceedTheBalance() throws Exception {
        Env e = env();
        String inv = invoiceOf(e, "SVC");
        List<ResponseEntity<Map>> rs = parallel(8, i -> () -> pay(e.token(), inv, "cash", 100)); // 800 offered against 500
        long ok = rs.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
        long refused = rs.stream().filter(r -> r.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY).count();
        assertThat(ok).isEqualTo(5);
        assertThat(refused).isEqualTo(3);
        assertThat(paidColumn(e, inv)).isEqualByComparingTo("500");
        assertThat(sumPayments(e, inv)).isEqualByComparingTo("500");
    }

    // AC2 (J11)
    @Test
    void invoicePaidAtInterimCheckpointStaysCollectableAfterProcedureIsAppended() {
        long t0 = System.currentTimeMillis();
        Env e = env();
        String entry = atInterimBilling(e);
        ResponseEntity<Map> interim = checkout(e.token(), entry, List.of(line("CONS", 1))); // 400
        assertThat(interim.getStatusCode().is2xxSuccessful()).isTrue();
        String inv = (String) interim.getBody().get("id");
        ResponseEntity<Map> p1 = pay(e.token(), inv, "cash", 400);
        assertThat(p1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(p1.getBody().get("status")).isEqualTo("paid");
        move(e, entry, "vitals_done", "in_consult", "checkout_pending");

        ResponseEntity<Map> ext = checkout(e.token(), entry, List.of(line("XRAY", 1))); // +1200
        assertThat(ext.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(ext.getBody().get("id")).isEqualTo(inv);
        assertThat(ext.getBody().get("status")).isEqualTo("partial");
        assertThat(num(ext.getBody().get("balanceDue"))).isEqualTo(1200.0);
        assertThat(num(ext.getBody().get("total"))).isEqualTo(1600.0);

        ResponseEntity<Map> p2 = pay(e.token(), inv, "card", 1200);
        assertThat(p2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(p2.getBody().get("status")).isEqualTo("paid");
        assertThat(num(p2.getBody().get("balanceDue"))).isZero();
        assertThat(System.currentTimeMillis() - t0).as("J11 under 90 s").isLessThan(90_000);
    }

    // AC3
    @Test
    void overpaymentIs422WithCurrentBalanceAndWritesNothing() {
        Env e = env();
        String inv = invoiceOf(e, "SVC");
        assertThat(pay(e.token(), inv, "cash", 300).getStatusCode()).isEqualTo(HttpStatus.CREATED); // balance 200
        ResponseEntity<Map> over = pay(e.token(), inv, "cash", 250);
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(type(over)).containsIgnoringCase("overpayment");
        assertThat(String.valueOf(over.getBody())).contains("200");
        assertThat(num(over.getBody().get("balance"))).isEqualTo(200.0);
        assertThat(paymentRows(e, inv)).isEqualTo(1);
        assertThat(paidColumn(e, inv)).isEqualByComparingTo("300");
    }

    @Test
    void boundaryExactBalanceAccepted_OnePaiseOverRefused_PaidInvoiceRefusesMore() {
        Env e = env();
        String inv = invoiceOf(e, "SVC");
        assertThat(pay(e.token(), inv, "cash", "500.01").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(pay(e.token(), inv, "cash", "499.99").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(getInvoice(e.token(), inv).getBody().get("status")).isEqualTo("partial");
        assertThat(pay(e.token(), inv, "cash", "0.02").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(pay(e.token(), inv, "cash", "0.01").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(getInvoice(e.token(), inv).getBody().get("status")).isEqualTo("paid");
        assertThat(pay(e.token(), inv, "cash", "0.01").getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(paymentRows(e, inv)).isEqualTo(2);
        assertThat(paidColumn(e, inv)).isEqualByComparingTo("500.00");
    }

    @Test
    void invalidPaymentBodiesAre400AndWriteNothing() {
        Env e = env();
        String inv = invoiceOf(e, "SVC");
        for (Object amount : new Object[]{0, -5, "10.005", "0.001"}) {
            assertThat(pay(e.token(), inv, "cash", amount).getStatusCode()).as("amount " + amount).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(pay(e.token(), inv, "bitcoin", 10).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/billing/invoices/" + inv + "/payments", HttpMethod.POST, withKey(e.token(), UUID.randomUUID().toString(), Map.of("method", "cash")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange("/v1/billing/invoices/" + inv + "/payments", HttpMethod.POST,
                withKey(e.token(), UUID.randomUUID().toString(), Map.of("method", "cash", "amount", 10, "reference", "x".repeat(101))), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(paymentRows(e, inv)).isZero();
    }

    @Test
    void referenceIsStoredAndEveryMethodIsAccepted() {
        Env e = env();
        String inv = invoiceOf(e, "SVC");
        for (String m : List.of("cash", "card", "upi", "other")) {
            ResponseEntity<Map> r = exchange("/v1/billing/invoices/" + inv + "/payments", HttpMethod.POST,
                    withKey(e.token(), UUID.randomUUID().toString(), Map.of("method", m, "amount", 100, "reference", "REF-" + m)), Map.class);
            assertThat(r.getStatusCode()).as(m).isEqualTo(HttpStatus.CREATED);
        }
        List<String> refs = inTenantTx(e.tenant().id(), () -> jdbc.queryForList("SELECT reference FROM invoice_payments WHERE invoice_id = ?::uuid ORDER BY reference", String.class, inv));
        assertThat(refs).containsExactly("REF-card", "REF-cash", "REF-other", "REF-upi");
    }

    // AC4: status follows paid vs total on every change
    @Test
    void statusIsDerivedUnpaidPartialPaidAndFollowsATotalReduction() {
        Env e = env();
        String entry = atInterimBilling(e);
        ResponseEntity<Map> first = checkout(e.token(), entry, List.of(line("SVC", 1))); // 500
        String inv = (String) first.getBody().get("id");
        assertThat(first.getBody().get("status")).isEqualTo("unpaid");
        assertThat(pay(e.token(), inv, "cash", 300).getBody().get("status")).isEqualTo("partial");
        move(e, entry, "vitals_done", "in_consult", "checkout_pending");
        // a 200 discount at the next checkpoint brings total to 300 == paid
        ResponseEntity<Map> disc = checkout(e.token(), entry, List.of(), "discounts", List.of(Map.of("kind", "amount", "value", 200, "reasonCode", "goodwill")));
        assertThat(disc.getStatusCode().is2xxSuccessful()).as(String.valueOf(disc.getBody())).isTrue();
        assertThat(disc.getBody().get("status")).isEqualTo("paid");
        assertThat(num(disc.getBody().get("balanceDue"))).isZero();
        assertThat(inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT status FROM invoices WHERE id = ?::uuid", String.class, inv))).isEqualTo("paid");
    }

    @Test
    void totalCannotBeCutBelowWhatIsAlreadyPaid() {
        Env e = env();
        String entry = atInterimBilling(e);
        String inv = (String) checkout(e.token(), entry, List.of(line("SVC", 1))).getBody().get("id");
        assertThat(pay(e.token(), inv, "cash", 500).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        move(e, entry, "vitals_done", "in_consult", "checkout_pending");
        ResponseEntity<Map> r = checkout(e.token(), entry, List.of(), "discounts", List.of(Map.of("kind", "amount", "value", 100, "reasonCode", "goodwill")));
        assertThat(r.getStatusCode().is4xxClientError()).as(String.valueOf(r.getBody())).isTrue();
        ResponseEntity<Map> g = getInvoice(e.token(), inv);
        assertThat(num(g.getBody().get("total"))).isEqualTo(500.0);
        assertThat(g.getBody().get("status")).isEqualTo("paid");
        assertThat(count(e, "SELECT count(*) FROM invoice_discounts WHERE invoice_id = ?::uuid", inv)).isZero();
    }

    // status words for closed invoices
    @Test
    void voidCancelledAndRefundedInvoicesRefusePaymentsWith409() {
        Env e = env();
        String voidInv = invoiceOf(e, "SVC");
        assertThat(exchange("/v1/billing/invoices/" + voidInv + "/void", HttpMethod.POST, authedJsonBody(e.token(), Map.of("reason", "test")), Map.class)
                .getStatusCode().is2xxSuccessful()).isTrue();
        ResponseEntity<Map> v = pay(e.token(), voidInv, "cash", 10);
        assertThat(v.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(type(v)).contains("not-payable");

        for (String st : List.of("cancelled", "refunded")) {
            String inv = invoiceOf(e, "SVC");
            try (Connection c = superuserConnection(); PreparedStatement ps = c.prepareStatement("UPDATE invoices SET status = ? WHERE id = ?")) {
                ps.setString(1, st);
                ps.setObject(2, UUID.fromString(inv));
                ps.executeUpdate();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            ResponseEntity<Map> r = pay(e.token(), inv, "cash", 10);
            assertThat(r.getStatusCode()).as(st).isEqualTo(HttpStatus.CONFLICT);
            assertThat(type(r)).as(st).contains("not-payable");
            assertThat(paymentRows(e, inv)).isZero();
        }
    }

    // auth, tenant isolation, day close
    @Test
    void paymentNeedsBillingEditAndNeverCrossesTenants() {
        Env a = env();
        Env b = env();
        String inv = invoiceOf(a, "SVC");
        String viewOnly = tokenFor(a, "ViewOnly", true, false, false, false, false);
        assertThat(pay(viewOnly, inv, "cash", 10).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(getInvoice(viewOnly, inv).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pay(b.token(), inv, "cash", 10).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getInvoice(b.token(), inv).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(pay(a.token(), UUID.randomUUID().toString(), "cash", 10).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(paymentRows(a, inv)).isZero();
    }

    @Test
    void closedDayRefusesPaymentWith409DayClosed() {
        Env e = env();
        String inv = invoiceOf(e, "SVC");
        ResponseEntity<Map> close = exchange("/v1/billing/day-close", HttpMethod.POST, authedJsonBody(e.token(), Map.of("countedCash", 0, "note", "qa close")), Map.class);
        assertThat(close.getStatusCode().is2xxSuccessful()).as(String.valueOf(close.getBody())).isTrue();
        ResponseEntity<Map> r = pay(e.token(), inv, "cash", 10);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(type(r)).endsWith("day-closed");
    }

    // idempotency of the endpoint itself
    @Test
    void sameKeyReplaysTheOriginal201AndOnlyOnePaymentExists() {
        Env e = env();
        String inv = invoiceOf(e, "SVC");
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> first = pay(e.token(), inv, "upi", 100, key);
        ResponseEntity<Map> again = pay(e.token(), inv, "upi", 100, key);
        ResponseEntity<Map> third = pay(e.token(), inv, "upi", 100, key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(third.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(again.getBody()).isEqualTo(first.getBody());
        assertThat(paymentRows(e, inv)).isEqualTo(1);
        assertThat(num(getInvoice(e.token(), inv).getBody().get("balanceDue"))).isEqualTo(400.0);
    }

    @Test
    void missingKeyIs400AndReusedKeyWithDifferentAmountIs422() {
        Env e = env();
        String inv = invoiceOf(e, "SVC");
        ResponseEntity<Map> none = pay(e.token(), inv, "cash", 100, null);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(none.getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        String key = UUID.randomUUID().toString();
        assertThat(pay(e.token(), inv, "cash", 100, key).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> reused = pay(e.token(), inv, "cash", 150, key);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(reused.getBody().get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(paymentRows(e, inv)).isEqualTo(1);
    }

    @Test
    void concurrentRetriesOfOneKeyRecordExactlyOnce() throws Exception {
        Env e = env();
        String inv = invoiceOf(e, "SVC");
        String key = UUID.randomUUID().toString();
        List<ResponseEntity<Map>> rs = parallel(3, i -> () -> pay(e.token(), inv, "cash", 100, key));
        assertThat(rs).allSatisfy(r -> assertThat(r.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT));
        assertThat(rs.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED && r.getHeaders().getFirst("Idempotent-Replayed") == null).count()).isEqualTo(1);
        assertThat(paymentRows(e, inv)).isEqualTo(1);
        assertThat(paidColumn(e, inv)).isEqualByComparingTo("100");
    }

    // response shape, audit, event
    @Test
    void responseCarriesPaidBalanceStatusAndPayments() {
        Env e = env();
        String inv = invoiceOf(e, "SVC");
        ResponseEntity<Map> r = pay(e.token(), inv, "cash", 120);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(num(r.getBody().get("paid"))).isEqualTo(120.0);
        assertThat(num(r.getBody().get("balanceDue"))).isEqualTo(380.0);
        assertThat(r.getBody().get("status")).isEqualTo("partial");
        assertThat((List<?>) r.getBody().get("payments")).hasSize(1);
        assertThat(getInvoice(e.token(), inv).getBody()).containsKeys("paid", "balanceDue", "status", "payments");
    }

    @Test
    void everyPaymentWritesAnAuditRowAndAnOutboxEventWithoutPhi() {
        Env e = env();
        String inv = invoiceOf(e, "SVC");
        pay(e.token(), inv, "cash", 100);
        pay(e.token(), inv, "card", 400);
        assertThat(count(e, "SELECT count(*) FROM audit_log WHERE action = 'billing.payment_recorded' AND entity_id = ?::uuid", inv)).isEqualTo(2);
        List<String> payloads = inTenantTx(e.tenant().id(), () -> jdbc.queryForList(
                "SELECT payload::text FROM outbox_events WHERE event_type = 'billing.payment.recorded.v1' ORDER BY id", String.class));
        assertThat(payloads).hasSize(2);
        assertThat(payloads.get(1)).contains("\"balanceMinor\": 0").contains("\"status\": \"paid\"").contains("\"amountMinor\": 40000");
        assertThat(payloads.get(0)).doesNotContain("Qa ").doesNotContain("+91");
    }

    // AC5: nightly consistency
    @Test
    void consistencyJobFindsZeroWhenInSyncAndAlertsOnDrift() {
        Env e = env();
        String inv = invoiceOf(e, "SVC");
        pay(e.token(), inv, "cash", 200);
        assertThat(consistencyJob.check(e.tenant().id())).isZero();

        try (Connection c = superuserConnection(); PreparedStatement ps = c.prepareStatement("UPDATE invoices SET paid = 0 WHERE id = ?")) {
            ps.setObject(1, UUID.fromString(inv));
            ps.executeUpdate();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        Logger lg = (Logger) LoggerFactory.getLogger(PaymentConsistencyJob.class);
        ListAppender<ILoggingEvent> app = new ListAppender<>();
        app.start();
        lg.addAppender(app);
        try {
            assertThat(consistencyJob.check(e.tenant().id())).isEqualTo(1);
        } finally {
            lg.detachAppender(app);
        }
        assertThat(app.list).anyMatch(ev -> ev.getLevel() == Level.ERROR && ev.getFormattedMessage().contains("ALERT"));
    }

    @Test
    void consistencyJobOnlyCountsItsOwnTenant() {
        Env a = env();
        Env b = env();
        String inv = invoiceOf(a, "SVC");
        pay(a.token(), inv, "cash", 200);
        try (Connection c = superuserConnection(); PreparedStatement ps = c.prepareStatement("UPDATE invoices SET paid = 0 WHERE id = ?")) {
            ps.setObject(1, UUID.fromString(inv));
            ps.executeUpdate();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        assertThat(consistencyJob.check(b.tenant().id())).isZero();
        assertThat(consistencyJob.check(a.tenant().id())).isEqualTo(1);
    }

    // db invariant
    @Test
    void databaseRefusesPaidAboveTotal() {
        Env e = env();
        String inv = invoiceOf(e, "SVC");
        assertThatThrownBy(() -> {
            try (Connection c = superuserConnection(); PreparedStatement ps = c.prepareStatement("UPDATE invoices SET paid = total + 1 WHERE id = ?")) {
                ps.setObject(1, UUID.fromString(inv));
                ps.executeUpdate();
            }
        }).hasMessageContaining("invoices_paid_bounds");
    }

    // FEF-008 depends on this: the invoice read-back must echo each payment's Idempotency-Key so a client can tell whether its payment landed
    @Test
    @Tag("known-defect-FEF-008")
    void invoiceReadBackEchoesIdempotencyKeyOnEachPayment() {
        Env e = env();
        String inv = invoiceOf(e, "SVC");
        String key = UUID.randomUUID().toString();
        pay(e.token(), inv, "upi", 100, key);
        List<?> payments = (List<?>) getInvoice(e.token(), inv).getBody().get("payments");
        assertThat(payments).hasSize(1);
        assertThat(((Map<?, ?>) payments.get(0)).get("idempotencyKey")).isEqualTo(key);
    }
}

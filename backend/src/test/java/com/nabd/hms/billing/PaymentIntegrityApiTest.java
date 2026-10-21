package com.nabd.hms.billing;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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

/** FIN-001: atomic payments, status follows the balance, overpayment refused, nightly consistency check. */
class PaymentIntegrityApiTest extends ApiTestBase {

    @Autowired
    PaymentConsistencyJob consistencyJob;

    private static final java.util.concurrent.atomic.AtomicInteger DOB = new java.util.concurrent.atomic.AtomicInteger();

    private record Ctx(SeededTenant tenant, SeededStaff staff, String token) {
    }

    private Ctx ctx(String n) {
        SeededTenant tenant = seedTenant();
        SeededStaff staff = seedStaff(tenant, seedFullAccessRole(tenant.id()), "pay" + n + "@a.com", "+91970001" + n, false);
        return new Ctx(tenant, staff, loginAndGetAccessToken(staff));
    }

    private String patient(String token, String name, String phone) {
        return (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of(
                "name", name, "phone", phone, "dob", "19" + (50 + DOB.incrementAndGet()) + "-03-04", "gender", "male")), Map.class).getBody().get("id");
    }

    private String checkIn(String token, String patientId, UUID doctorId) {
        return (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(token, Map.of(
                "patientId", patientId, "doctorId", doctorId.toString())), Map.class).getBody().get("id");
    }

    private void moveTo(String token, String entry, String... statuses) {
        for (String s : statuses) {
            exchange("/v1/queue/" + entry + "/status", HttpMethod.PATCH, authedJsonBody(token, Map.of("status", s)), Map.class);
        }
    }

    /** Puts the default department on the flow that bills at billing_pending and again at checkout_pending. */
    @SuppressWarnings("unchecked")
    private void interimBillingFlow(String token) {
        List<Map<String, Object>> depts = exchange("/v1/departments", HttpMethod.GET, authed(token), List.class).getBody();
        String dept = (String) depts.stream().filter(d -> Boolean.TRUE.equals(d.get("isDefault"))).findFirst().orElseThrow().get("id");
        exchange("/v1/departments/" + dept + "/workflow", HttpMethod.POST, authedJsonBody(token, Map.of(
                "templateCode", "clinic_walkin_with_billing", "toggles", Map.of("vitals_enabled", false))), Map.class);
    }

    private static Map<String, Object> line(int price) {
        return Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "X", "chargeName", "Service", "category", "Service", "quantity", 1,
                "unitPrice", price, "taxRatePercent", 0);
    }

    /** A visit billed at checkout_pending with one line of the given price; returns the invoice id. */
    private String invoice(Ctx c, int price, String name, String phone) {
        String entry = checkIn(c.token(), patient(c.token(), name, phone), c.staff().id());
        moveTo(c.token(), entry, "waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending");
        return (String) exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "lineItems", List.of(line(price)))), Map.class).getBody().get("id");
    }

    private ResponseEntity<Map> pay(Ctx c, String invoiceId, int amount, String key) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(c.token());
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        // PLT-026: payments require a UUID Idempotency-Key; callers that do not care get a fresh one per call
        h.set("Idempotency-Key", key != null ? key : java.util.UUID.randomUUID().toString());
        return exchange("/v1/billing/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                new HttpEntity<>("{\"method\":\"cash\",\"amount\":" + amount + "}", h), Map.class);
    }

    private int paymentRows(UUID tenantId, String invoiceId) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM invoice_payments WHERE invoice_id = ?", Integer.class, UUID.fromString(invoiceId)));
    }

    private Map<String, Object> dbInvoice(UUID tenantId, String invoiceId) {
        return inTenantTx(tenantId, () -> jdbc.queryForMap(
                "SELECT paid, status, (SELECT COALESCE(SUM(amount),0) FROM invoice_payments WHERE invoice_id = invoices.id) AS summed " +
                        "FROM invoices WHERE id = ?", UUID.fromString(invoiceId)));
    }

    /** Fires all payments at the same instant (latch) and returns the response statuses. */
    private List<HttpStatus> concurrentPayments(Ctx c, String invoiceId, int... amounts) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(amounts.length);
        CountDownLatch ready = new CountDownLatch(amounts.length);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<HttpStatus>> futures = new ArrayList<>();
        for (int amount : amounts) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return HttpStatus.valueOf(pay(c, invoiceId, amount, null).getStatusCode().value());
            }));
        }
        ready.await();
        go.countDown();
        List<HttpStatus> out = new ArrayList<>();
        for (Future<HttpStatus> f : futures) out.add(f.get());
        pool.shutdown();
        return out;
    }

    @Test
    void twoCashiersPayingAtTheSameInstantBothLand() throws Exception {
        Ctx c = ctx("01");
        String inv = invoice(c, 500, "P1", "+919999960001");
        List<HttpStatus> statuses = concurrentPayments(c, inv, 300, 200);
        assertThat(statuses).containsOnly(HttpStatus.CREATED);
        Map<String, Object> row = dbInvoice(c.tenant().id(), inv);
        assertThat(((Number) row.get("paid")).doubleValue()).isEqualTo(500.0);
        assertThat(row.get("status")).isEqualTo("paid");
        assertThat(((Number) row.get("summed")).doubleValue()).isEqualTo(500.0);
    }

    @Test
    void tenSimultaneousPaymentsAddUpExactlyAndTheSurplusIsRefused() throws Exception {
        Ctx c = ctx("02");
        String inv = invoice(c, 500, "P2", "+919999960002");
        int[] amounts = new int[12];
        java.util.Arrays.fill(amounts, 50);
        List<HttpStatus> statuses = concurrentPayments(c, inv, amounts);
        assertThat(statuses.stream().filter(s -> s == HttpStatus.CREATED).count()).isEqualTo(10);
        assertThat(statuses.stream().filter(s -> s == HttpStatus.UNPROCESSABLE_CONTENT).count()).isEqualTo(2);
        Map<String, Object> row = dbInvoice(c.tenant().id(), inv);
        assertThat(((Number) row.get("paid")).doubleValue()).isEqualTo(500.0);
        assertThat(row.get("status")).isEqualTo("paid");
        assertThat(paymentRows(c.tenant().id(), inv)).isEqualTo(10);
    }

    /** QA-10 / J11: paid in full at the interim billing_pending stop, then a procedure is added at checkout_pending. */
    @Test
    @SuppressWarnings("unchecked")
    void invoicePaidAtInterimCheckpointStaysCollectableAfterAProcedureIsAppended() {
        Ctx c = ctx("03");
        interimBillingFlow(c.token());
        String entry = checkIn(c.token(), patient(c.token(), "P3", "+919999960003"), c.staff().id());
        moveTo(c.token(), entry, "waiting", "billing_pending");

        Map<String, Object> first = exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "lineItems", List.of(line(200)))), Map.class).getBody();
        String inv = (String) first.get("id");
        Map<String, Object> paidFirst = pay(c, inv, 200, null).getBody();
        assertThat(paidFirst.get("status")).isEqualTo("paid");

        moveTo(c.token(), entry, "checkout_pending");
        Map<String, Object> second = exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "lineItems", List.of(line(1200)))), Map.class).getBody();
        assertThat(second.get("status")).isEqualTo("partial");
        assertThat(((Number) second.get("balanceDue")).doubleValue()).isEqualTo(1200.0);

        ResponseEntity<Map> last = pay(c, inv, 1200, null);
        assertThat(last.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(last.getBody().get("status")).isEqualTo("paid");
        assertThat(((Number) last.getBody().get("balanceDue")).doubleValue()).isEqualTo(0.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void overpaymentIsRefusedWith422AndTheBalanceAndWritesNothing() {
        Ctx c = ctx("04");
        String inv = invoice(c, 500, "P4", "+919999960004");
        assertThat(pay(c, inv, 300, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> over = pay(c, inv, 250, null);
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(over.getBody().get("type")).asString().contains("overpayment");
        assertThat(((Number) over.getBody().get("balance")).doubleValue()).isEqualTo(200.0);
        assertThat(paymentRows(c.tenant().id(), inv)).isEqualTo(1);

        assertThat(pay(c, inv, 200, null).getStatusCode()).isEqualTo(HttpStatus.CREATED); // boundary: exactly the balance
        ResponseEntity<Map> afterPaid = pay(c, inv, 1, null);
        assertThat(afterPaid.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(((Number) afterPaid.getBody().get("balance")).doubleValue()).isEqualTo(0.0);
    }

    @Test
    void paymentsOnVoidCancelledOrRefundedInvoicesAreRefused() {
        Ctx c = ctx("05");
        String inv = invoice(c, 500, "P5", "+919999960005");
        for (String closed : new String[]{"void", "cancelled", "refunded"}) {
            inTenantTx(c.tenant().id(), () -> jdbc.update("UPDATE invoices SET status = ? WHERE id = ?", closed, UUID.fromString(inv)));
            ResponseEntity<Map> r = pay(c, inv, 10, null);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(r.getBody().get("type")).asString().contains("invoice-not-payable");
        }
        assertThat(paymentRows(c.tenant().id(), inv)).isZero();
    }

    @Test
    void repeatedIdempotencyKeyRecordsTheMoneyOnce() {
        Ctx c = ctx("06");
        String inv = invoice(c, 500, "P6", "+919999960006");
        String other = invoice(c, 500, "P6c", "+919999961006");
        String key = java.util.UUID.randomUUID().toString();
        assertThat(pay(c, inv, 100, key).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> replay = pay(c, inv, 100, key);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Number) replay.getBody().get("paid")).doubleValue()).isEqualTo(100.0);
        assertThat(paymentRows(c.tenant().id(), inv)).isEqualTo(1);
        // PLT-026 filter: same key on a different request is 422 IDEMPOTENCY_KEY_REUSED (was FIN-001 service 409)
        assertThat(pay(c, other, 100, key).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(paymentRows(c.tenant().id(), other)).isZero();
    }

    @Test
    void totalCannotDropBelowWhatIsAlreadyPaid() {
        Ctx c = ctx("07");
        interimBillingFlow(c.token());
        String entry = checkIn(c.token(), patient(c.token(), "P7", "+919999960007"), c.staff().id());
        moveTo(c.token(), entry, "waiting", "billing_pending");
        String inv = (String) exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "lineItems", List.of(line(1000)))), Map.class).getBody().get("id");
        assertThat(pay(c, inv, 1000, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        moveTo(c.token(), entry, "checkout_pending");

        ResponseEntity<Map> r = exchange("/v1/billing/checkout/" + entry, HttpMethod.POST, authedJsonBody(c.token(), Map.of(
                "lineItems", List.of(), "discount", 100)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("type")).asString().contains("total-below-paid");
        assertThat(dbInvoice(c.tenant().id(), inv).get("status")).isEqualTo("paid");
    }

    @Test
    void auditOutboxAndCrossTenantAreCovered() {
        Ctx a = ctx("08");
        Ctx b = ctx("09");
        String inv = invoice(a, 500, "P8", "+919999960008");
        assertThat(pay(a, inv, 100, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Integer audit = inTenantTx(a.tenant().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'billing.payment_recorded' AND entity_id = ?", Integer.class, UUID.fromString(inv)));
        assertThat(audit).isEqualTo(1);
        Integer events = inTenantTx(a.tenant().id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE event_type = 'billing.payment.recorded.v1' AND payload->>'invoiceId' = ?", Integer.class, inv));
        assertThat(events).isEqualTo(1);

        assertThat(pay(b, inv, 100, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(paymentRows(a.tenant().id(), inv)).isEqualTo(1);
    }

    @Test
    void nightlyConsistencyCheckFindsZeroMismatchesAndRaisesOnDrift() {
        Ctx c = ctx("10");
        String inv = invoice(c, 500, "P10", "+919999960010");
        assertThat(pay(c, inv, 200, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(consistencyJob.check(c.tenant().id())).isZero();

        inTenantTx(c.tenant().id(), () -> jdbc.update("UPDATE invoices SET paid = 300 WHERE id = ?", UUID.fromString(inv)));
        assertThat(consistencyJob.check(c.tenant().id())).isEqualTo(1);
    }
}

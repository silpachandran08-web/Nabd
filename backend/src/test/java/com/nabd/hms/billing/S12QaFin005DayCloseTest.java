package com.nabd.hms.billing;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ConcurrencyHarness;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S12 FIN-005): re-close of a reopened day, close-vs-payment race, tolerance policy per currency, DAY_CLOSED on every money path. */
@SuppressWarnings({"rawtypes", "unchecked"})
class S12QaFin005DayCloseTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 900_000));

    private static String phone() {
        return "+9193" + String.format("%08d", SEQ.incrementAndGet() % 100_000_000);
    }

    private record Owner(SeededTenant tenant, UUID role, UUID staffId, String token) {
    }

    private Owner owner(String region) {
        SeededTenant t = seedTenant();
        if (!"IN".equals(region)) {
            new JdbcTemplate(superuserDataSource()).execute(
                    "SET session_replication_role = replica; UPDATE tenants SET region_code = '" + region + "' WHERE id = '" + t.id() + "'");
        }
        UUID role = seedFullAccessRole(t.id());
        SeededStaff s = seedStaff(t, role, "q1f5" + UUID.randomUUID() + "@a.com", phone(), false);
        return new Owner(t, role, s.id(), loginAndGetAccessToken(s));
    }

    private Owner owner() {
        return owner("IN");
    }

    private String patient(String token) {
        return (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of("name", "Qa Fin " + SEQ.incrementAndGet(),
                "phone", phone(), "dob", "1984-03-02", "gender", "male", "confirmedNotDuplicate", true)), Map.class).getBody().get("id");
    }

    private String visitAtCheckout(Owner o) {
        String q = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(o.token(), Map.of(
                "patientId", patient(o.token()), "doctorId", o.staffId().toString())), Map.class).getBody().get("id");
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + q + "/status", HttpMethod.PATCH, authedJsonBody(o.token(), Map.of("status", s)), Map.class);
        }
        return q;
    }

    private static Map<String, Object> bill() {
        return Map.of("lineItems", List.of(Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "CONS", "chargeName", "Consultation",
                "category", "Consultation", "quantity", 1, "unitPrice", 1000, "taxRatePercent", 0)));
    }

    private ResponseEntity<Map> checkout(String token, String q) {
        return exchange("/v1/billing/checkout/" + q, HttpMethod.POST, authedJsonBody(token, bill()), Map.class);
    }

    private String invoice(Owner o) {
        ResponseEntity<Map> inv = checkout(o.token(), visitAtCheckout(o));
        assertThat(inv.getStatusCode().is2xxSuccessful()).as(String.valueOf(inv.getBody())).isTrue();
        return (String) inv.getBody().get("id");
    }

    private ResponseEntity<Map> pay(String token, String invoiceId, Object amount) {
        return exchange("/v1/billing/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                authedJsonBody(token, Map.of("method", "cash", "amount", amount)), Map.class);
    }

    private ResponseEntity<Map> close(String token, Object counted, String note, String date) {
        Map<String, Object> body = new HashMap<>(Map.of("countedCash", counted));
        if (note != null) body.put("note", note);
        if (date != null) body.put("date", date);
        return exchange("/v1/billing/day-close", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private ResponseEntity<Map> reopen(String token, String date, String reason) {
        Map<String, Object> body = new HashMap<>();
        if (reason != null) body.put("reason", reason);
        if (date != null) body.put("date", date);
        return exchange("/v1/billing/day-close/reopen", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private Map<String, Object> summary(String token, String date) {
        return exchange("/v1/billing/day-close" + (date == null ? "" : "?date=" + date), HttpMethod.GET, authed(token), Map.class).getBody();
    }

    private List<Map<String, Object>> history(String token, String from, String to) {
        return (List<Map<String, Object>>) exchange("/v1/billing/day-close/history?from=" + from + "&to=" + to, HttpMethod.GET, authed(token), Map.class)
                .getBody().get("items");
    }

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    private static Map<String, Object> closeOf(ResponseEntity<Map> r) {
        return (Map<String, Object>) r.getBody().get("close");
    }

    private static void assertDayClosed(ResponseEntity<Map> r, String what) {
        assertThat(r.getStatusCode()).as(what + " " + r.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) r.getBody().get("type")).as(what).endsWith("day-closed");
    }

    private BigDecimal cashInDb(UUID t) {
        return inTenantTx(t, () -> jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM invoice_payments WHERE tenant_id = ? AND method = 'cash'",
                BigDecimal.class, t));
    }

    /** Moves the tenant's newest cash payment to yesterday noon (clinic time), as a correction made on that day would have been. */
    private void backdateLastPayment(UUID t, String yesterday) {
        new JdbcTemplate(superuserDataSource()).execute("SET session_replication_role = replica; UPDATE invoice_payments SET recorded_at = ('"
                + yesterday + " 12:00'::timestamp AT TIME ZONE (SELECT COALESCE(timezone, 'Asia/Kolkata') FROM tenants WHERE id = '" + t + "')) "
                + "WHERE id = (SELECT id FROM invoice_payments WHERE tenant_id = '" + t + "' ORDER BY recorded_at DESC LIMIT 1)");
    }

    // AC1 past day: a backdated correction is picked up by the re-close; one day_closes row, close_count 2, both closes in history.
    @Test
    void reclosingAPastDayRecomputesExpectedCashFromThatDaysPayments() {
        Owner o = owner();
        String today = (String) summary(o.token(), null).get("date");
        String yesterday = LocalDate.parse(today).minusDays(1).toString();
        String inv = invoice(o);
        assertThat(pay(o.token(), inv, 120).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        backdateLastPayment(o.tenant().id(), yesterday);
        assertThat(num(((List<Map<String, Object>>) summary(o.token(), yesterday).get("methods")).get(0).get("amount")))
                .as("fixture: the payment counts on yesterday").isEqualTo(120.0);

        ResponseEntity<Map> c1 = close(o.token(), 120, null, yesterday);
        assertThat(c1.getStatusCode()).as(String.valueOf(c1.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(num(closeOf(c1).get("expectedCash"))).isEqualTo(120.0);

        // reopen needs a reason; reopening an open day is DAY_NOT_CLOSED
        assertThat(reopen(o.token(), yesterday, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reopen(o.token(), yesterday, "  ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reopen(o.token(), yesterday, "Correction: missed cash receipt").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> again = reopen(o.token(), yesterday, "twice");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat((String) again.getBody().get("type")).endsWith("day-not-closed");

        assertThat(pay(o.token(), inv, 80).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        backdateLastPayment(o.tenant().id(), yesterday);
        ResponseEntity<Map> c2 = close(o.token(), 195, "5 short, coin float", yesterday);
        assertThat(c2.getStatusCode()).as(String.valueOf(c2.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(closeOf(c2)).containsEntry("status", "closed").containsEntry("closeCount", 2);
        assertThat(num(closeOf(c2).get("expectedCash"))).as("recomputed").isEqualTo(200.0);
        assertThat(num(closeOf(c2).get("variance"))).isEqualTo(-5.0);

        List<Map<String, Object>> h = history(o.token(), yesterday, yesterday);
        assertThat(h).extracting(i -> i.get("action")).containsExactly("close", "reopen", "close");
        assertThat(num(h.get(0).get("expectedCash"))).isEqualTo(200.0);
        assertThat(num(h.get(0).get("countedCash"))).isEqualTo(195.0);
        assertThat(num(h.get(2).get("expectedCash"))).isEqualTo(120.0);
        assertThat(h.get(1).get("note")).isEqualTo("Correction: missed cash receipt");
        UUID t = o.tenant().id();
        assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT count(*) FROM day_closes WHERE tenant_id = ?", Integer.class, t))).isEqualTo(1);
        assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND entity_type = 'day_close'",
                Integer.class, t))).isEqualTo(3);
        // today stays open and billable
        assertThat(summary(o.token(), null).get("close")).isNull();
        assertThat(pay(o.token(), inv, 1).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // AC1 negatives: only billing:approve may close or reopen; billing:view may read history; cross-tenant history is empty.
    @Test
    void closeAndReopenNeedBillingApprove() {
        Owner o = owner();
        UUID cashierRole = seedRole(o.tenant().id(), "Cashier", false, new ModuleGrant("billing", true, true, true, false, false, false, false));
        String cashier = loginAndGetAccessToken(seedStaff(o.tenant(), cashierRole, "q1f5c" + UUID.randomUUID() + "@a.com", phone(), false));
        assertThat(close(cashier, 0, null, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(close(o.token(), 0, null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reopen(cashier, null, "try").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String today = (String) summary(o.token(), null).get("date");
        assertThat(history(cashier, today, today)).hasSize(1);
        Owner other = owner();
        assertThat(history(other.token(), today, today)).isEmpty();
        assertThat(reopen(other.token(), today, "not mine").getStatusCode()).as("other tenant cannot reopen A's day").isEqualTo(HttpStatus.CONFLICT);
        assertThat(closeOf(exchange("/v1/billing/day-close", HttpMethod.GET, authed(o.token()), Map.class))).containsEntry("status", "closed");
    }

    // AC2 under real concurrency: several payments and a close released together, 8 rounds. Expected cash == every cash payment
    // that exists; each payment is 201 (and counted) or 409 day-closed (and not stored).
    @Test
    void closeRacingSeveralPaymentsNeverMissesOrCountsAfter() {
        Owner o = owner();
        String inv = invoice(o);
        UUID t = o.tenant().id();
        int counted = 0;
        int refused = 0;
        for (int round = 0; round < 8; round++) {
            BigDecimal before = cashInDb(t);
            List<Callable<ResponseEntity<Map>>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) tasks.add(() -> pay(o.token(), inv, 1));
            tasks.add(() -> close(o.token(), 0, "race", null));
            List<ResponseEntity<Map>> res = ConcurrencyHarness.race(tasks);
            ResponseEntity<Map> closed = res.get(4);
            assertThat(closed.getStatusCode()).as("close " + closed.getBody()).isEqualTo(HttpStatus.OK);
            int ok = 0;
            for (ResponseEntity<Map> p : res.subList(0, 4)) {
                if (p.getStatusCode() == HttpStatus.CREATED) ok++;
                else { assertDayClosed(p, "round " + round); refused++; }
            }
            counted += ok;
            BigDecimal after = cashInDb(t);
            assertThat(after.subtract(before).intValue()).as("round " + round + ": stored payments == 201s").isEqualTo(ok);
            assertThat(num(closeOf(closed).get("expectedCash"))).as("round " + round + ": close counted every stored payment").isEqualTo(after.doubleValue());
            // after the close nothing can land
            assertDayClosed(pay(o.token(), inv, 1), "after close round " + round);
            assertThat(cashInDb(t)).isEqualByComparingTo(after);
            assertThat(reopen(o.token(), null, "race round " + round).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(counted + refused).isEqualTo(32);
    }

    // AC2 variant: two closes racing each other produce one close and one DAY_ALREADY_CLOSED, never two close events.
    @Test
    void twoClosesRacingProduceOneClose() {
        Owner o = owner();
        List<ResponseEntity<Map>> res = ConcurrencyHarness.raceSame(3, () -> close(o.token(), 0, null, null));
        assertThat(res).filteredOn(r -> r.getStatusCode() == HttpStatus.OK).hasSize(1);
        assertThat(res).filteredOn(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                .allSatisfy(r -> assertThat((String) r.getBody().get("type")).endsWith("day-already-closed")).hasSize(2);
        UUID t = o.tenant().id();
        assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT count(*) FROM day_close_events WHERE tenant_id = ? AND action = 'close'",
                Integer.class, t))).isEqualTo(1);
    }

    // AC3: AED (and QAR, SAR) tenants default to 20; boundary exactly 20.00 needs no note, 20.01 does; the policy overrides it.
    @Test
    void aedToleranceComesFromClinicPolicyDefault20() {
        Owner ae = owner("AE");
        Map<String, Object> s = summary(ae.token(), null);
        assertThat(s.get("currency")).isEqualTo("AED");
        assertThat(num(s.get("cashTolerance"))).isEqualTo(20.0);
        assertThat(close(ae.token(), 20.00, null, null).getStatusCode()).as("variance == tolerance").isEqualTo(HttpStatus.OK);
        reopen(ae.token(), null, "boundary");
        ResponseEntity<Map> over = close(ae.token(), 20.01, null, null);
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) over.getBody().get("type")).endsWith("variance-note-required");
        assertThat(exchange("/v1/setup/policies/cash_variance_tolerance", HttpMethod.PATCH, authedJsonBody(ae.token(), Map.of("value", "5000")),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(num(summary(ae.token(), null).get("cashTolerance"))).isEqualTo(50.0);
        assertThat(close(ae.token(), 50.00, null, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        Owner qa = owner("QA");
        assertThat(summary(qa.token(), null)).containsEntry("currency", "QAR");
        assertThat(num(summary(qa.token(), null).get("cashTolerance"))).isEqualTo(20.0);
        Owner sa = owner("SA");
        assertThat(num(summary(sa.token(), null).get("cashTolerance"))).isEqualTo(20.0);
        Owner in = owner();
        assertThat(num(summary(in.token(), null).get("cashTolerance"))).as("INR default unchanged, AE policy is tenant-scoped").isEqualTo(200.0);
        // invalid policy values
        for (String bad : List.of("-1", "abc", "20.5")) {
            assertThat(exchange("/v1/setup/policies/cash_variance_tolerance", HttpMethod.PATCH, authedJsonBody(in.token(), Map.of("value", bad)),
                    Map.class).getStatusCode().is4xxClientError()).as(bad).isTrue();
        }
    }

    // AC4: once today is closed, checkout, payment, OTC sale, package sale, package refund approval and void are 409 DAY_CLOSED, nothing stored.
    @Test
    void closedDayRefusesEveryMoneyPathIncludingVoidAndOtc() {
        Owner o = owner();
        UUID t = o.tenant().id();
        String checker = loginAndGetAccessToken(seedStaff(o.tenant(), o.role(), "q1f5k" + UUID.randomUUID() + "@a.com", phone(), false));
        String paid = invoice(o);
        assertThat(pay(o.token(), paid, 100).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String unpaid = invoice(o);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(o.token(), Map.of("mode", "hybrid", "reason", "QA setup")), Map.class);
        String otcCode = (String) exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(o.token(), Map.of("name", "Qa Otc Item " + SEQ.incrementAndGet(),
                "isRx", false, "hsnCode", "3004", "price", 10.00, "taxRatePercent", 0, "stockQty", 50)), Map.class).getBody().get("code");
        Map<String, Object> otcBody = Map.of("lineItems", List.of(Map.of("chargeCode", otcCode, "quantity", 1)));
        ResponseEntity<Map> otcOpen = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(o.token(), otcBody), Map.class);
        assertThat(otcOpen.getStatusCode().is2xxSuccessful()).as("fixture: OTC sale works while the day is open " + otcOpen.getBody()).isTrue();

        Map<String, Object> pkg = new HashMap<>(Map.of("name", "Qa Pkg " + SEQ.incrementAndGet(), "packageType", "combination", "speciality", "Physio",
                "description", "d", "price", 600.00, "taxInclusive", false, "validityDays", 60, "validityStarts", "purchase_date"));
        pkg.put("graceDays", 0);
        pkg.put("items", List.of(Map.of("itemType", "service_session", "name", "Session", "quantity", 2, "unitListPrice", 300.00, "taxRatePercent", 0.0)));
        String pkgId = (String) exchange("/v1/packages", HttpMethod.POST, authedJsonBody(o.token(), pkg), Map.class).getBody().get("id");
        exchange("/v1/packages/" + pkgId + "/activate", HttpMethod.POST, authed(o.token()), Map.class);
        ResponseEntity<Map> sold = exchange("/v1/packages/sell", HttpMethod.POST, authedJsonBody(o.token(), Map.of(
                "patientId", patient(o.token()), "packageId", pkgId, "paymentMethod", "card")), Map.class);
        assertThat(sold.getStatusCode().is2xxSuccessful()).as(String.valueOf(sold.getBody())).isTrue();
        ResponseEntity<Map> refundReq = exchange("/v1/packages/instances/" + sold.getBody().get("id") + "/refund", HttpMethod.POST,
                authedJsonBody(o.token(), Map.of("reason", "Relocating")), Map.class);
        assertThat(refundReq.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(close(o.token(), 100, null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        String late = visitAtCheckout(o); // reaches checkout after the close
        int invoicesBefore = inTenantTx(t, () -> jdbc.queryForObject("SELECT count(*) FROM invoices WHERE tenant_id = ?", Integer.class, t));
        BigDecimal cashBefore = cashInDb(t);

        assertDayClosed(checkout(o.token(), late), "checkout");
        assertDayClosed(pay(o.token(), paid, 50), "payment");
        assertDayClosed(exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(o.token(), otcBody), Map.class), "otc sale");
        assertDayClosed(exchange("/v1/packages/sell", HttpMethod.POST, authedJsonBody(o.token(), Map.of(
                "patientId", patient(o.token()), "packageId", pkgId, "paymentMethod", "cash")), Map.class), "package sale");
        assertDayClosed(exchange("/v1/packages/refunds/" + refundReq.getBody().get("id") + "/approve", HttpMethod.POST, authed(checker), Map.class), "refund");
        assertDayClosed(exchange("/v1/billing/invoices/" + unpaid + "/void", HttpMethod.POST, authedJsonBody(o.token(), Map.of("reason", "error")), Map.class), "void");

        assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT count(*) FROM invoices WHERE tenant_id = ?", Integer.class, t))).isEqualTo(invoicesBefore);
        assertThat(cashInDb(t)).isEqualByComparingTo(cashBefore);
        assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT status FROM invoices WHERE id = ?::uuid", String.class, unpaid))).isNotEqualTo("void");

        // tenant isolation: another clinic's money paths are unaffected by A's close
        Owner b = owner();
        assertThat(pay(b.token(), invoice(b), 10).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // Boundary: closing a past day does not block today's billing (writes land on today).
    @Test
    void closedPastDayDoesNotBlockToday() {
        Owner o = owner();
        String yesterday = LocalDate.parse((String) summary(o.token(), null).get("date")).minusDays(1).toString();
        assertThat(close(o.token(), 0, null, yesterday).getStatusCode()).isEqualTo(HttpStatus.OK);
        String inv = invoice(o);
        assertThat(pay(o.token(), inv, 10).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // API spec: POST /billing/day-close is idempotent with an Idempotency-Key: a retry with the same key replays the first 200.
    @Test
    void closeRetriedWithTheSameIdempotencyKeyReplaysTheFirstResult() {
        Owner o = owner();
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> first = exchange("/v1/billing/day-close", HttpMethod.POST,
                authedJsonBodyWithHeader(o.token(), "Idempotency-Key", key, Map.of("countedCash", 0)), Map.class);
        ResponseEntity<Map> retry = exchange("/v1/billing/day-close", HttpMethod.POST,
                authedJsonBodyWithHeader(o.token(), "Idempotency-Key", key, Map.of("countedCash", 0)), Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getStatusCode()).as("retry body " + retry.getBody()).isEqualTo(HttpStatus.OK);
    }
}

package com.nabd.hms.billing;

import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ConcurrencyHarness;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FIN-005: re-close a reopened (or past) day with history, close-vs-payment race, tolerance policy, DAY_CLOSED on every money path. */
@SuppressWarnings({"rawtypes", "unchecked"})
class DayCloseRecloseApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private static String phone() {
        return "+9196" + (10_000_000 + SEQ.incrementAndGet());
    }

    private record Owner(SeededTenant tenant, UUID role, UUID staffId, String token) {
    }

    private Owner owner() {
        SeededTenant t = seedTenant();
        UUID role = seedFullAccessRole(t.id());
        SeededStaff s = seedStaff(t, role, "f5" + UUID.randomUUID() + "@a.com", phone(), false);
        return new Owner(t, role, s.id(), loginAndGetAccessToken(s));
    }

    private String patient(String token) {
        return (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(token, Map.of("name", "Fin Five " + SEQ.incrementAndGet(),
                "phone", phone(), "dob", "1985-03-02", "gender", "female", "confirmedNotDuplicate", true)), Map.class).getBody().get("id");
    }

    private String visitAtCheckout(Owner o) {
        String q = (String) exchange("/v1/queue/check-in", HttpMethod.POST, authedJsonBody(o.token(), Map.of(
                "patientId", patient(o.token()), "doctorId", o.staffId().toString())), Map.class).getBody().get("id");
        for (String s : List.of("waiting", "vitals_pending", "vitals_done", "in_consult", "checkout_pending")) {
            exchange("/v1/queue/" + q + "/status", HttpMethod.PATCH, authedJsonBody(o.token(), Map.of("status", s)), Map.class);
        }
        return q;
    }

    private ResponseEntity<Map> checkout(String token, String q) {
        return exchange("/v1/billing/checkout/" + q, HttpMethod.POST, authedJsonBody(token, Map.of("lineItems", List.of(
                Map.of("override", Map.of("reasonCode", "other"), "chargeCode", "CONS", "chargeName", "Consultation", "category", "Consultation",
                        "quantity", 1, "unitPrice", 1000, "taxRatePercent", 0)))), Map.class);
    }

    /** One bill of 1000 at 0% tax. */
    private String bill(Owner o) {
        ResponseEntity<Map> inv = checkout(o.token(), visitAtCheckout(o));
        assertThat(inv.getStatusCode().is2xxSuccessful()).as(String.valueOf(inv.getBody())).isTrue();
        return (String) inv.getBody().get("id");
    }

    private ResponseEntity<Map> pay(String token, String invoiceId, int amount) {
        return exchange("/v1/billing/invoices/" + invoiceId + "/payments", HttpMethod.POST,
                authedJsonBody(token, Map.of("method", "cash", "amount", amount)), Map.class);
    }

    private ResponseEntity<Map> close(String token, Object counted, String note, String date) {
        Map<String, Object> body = new HashMap<>(Map.of("countedCash", counted));
        if (note != null) body.put("note", note);
        if (date != null) body.put("date", date);
        return exchange("/v1/billing/day-close", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private ResponseEntity<Map> reopen(String token, String date) {
        Map<String, Object> body = new HashMap<>(Map.of("reason", "Missing bill found afterwards"));
        if (date != null) body.put("date", date);
        return exchange("/v1/billing/day-close/reopen", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private ResponseEntity<Map> history(String token, String query) {
        return exchange("/v1/billing/day-close/history" + query, HttpMethod.GET, authed(token), Map.class);
    }

    private String today(String token) {
        return (String) exchange("/v1/billing/day-close", HttpMethod.GET, authed(token), Map.class).getBody().get("date");
    }

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    private static Map<String, Object> closeOf(ResponseEntity<Map> r) {
        return (Map<String, Object>) r.getBody().get("close");
    }

    // AC1 / J13: close -> reopen -> re-close recomputes expected cash; both closes are in history, audit and events.
    @Test
    void recloseAfterReopenRecomputesExpectedCashAndKeepsBothClosesInHistory() {
        Owner o = owner();
        String inv = bill(o);
        assertThat(pay(o.token(), inv, 500).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> first = close(o.token(), 500, null, null);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(closeOf(first)).containsEntry("status", "closed").containsEntry("closeCount", 1);
        assertThat(num(closeOf(first).get("expectedCash"))).isEqualTo(500.0);

        assertThat(reopen(o.token(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pay(o.token(), inv, 300).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> again = close(o.token(), 800, null, null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(closeOf(again)).containsEntry("status", "closed").containsEntry("closeCount", 2)
                .containsEntry("reopenReason", null);
        assertThat(num(closeOf(again).get("expectedCash"))).isEqualTo(800.0);
        assertThat(num(closeOf(again).get("variance"))).isEqualTo(0.0);

        String day = today(o.token());
        List<Map<String, Object>> items = (List<Map<String, Object>>) history(o.token(), "?from=" + day + "&to=" + day).getBody().get("items");
        assertThat(items).extracting(i -> i.get("action")).containsExactly("close", "reopen", "close");
        assertThat(num(items.get(0).get("expectedCash"))).isEqualTo(800.0);
        assertThat(num(items.get(2).get("expectedCash"))).isEqualTo(500.0);
        assertThat(items.get(1)).containsEntry("note", "Missing bill found afterwards").containsEntry("expectedCash", null);
        assertThat(items).allSatisfy(i -> assertThat(i).containsEntry("date", day).containsEntry("actorName", "Test Staff"));

        UUID t = o.tenant().id();
        assertThat(inTenantTx(t, () -> jdbc.queryForList("SELECT action FROM audit_log WHERE tenant_id = ? AND entity_type = 'day_close' ORDER BY id",
                String.class, t))).containsExactly("day_close.close", "day_close.reopen", "day_close.close");
        assertThat(inTenantTx(t, () -> jdbc.queryForList("SELECT event_type FROM outbox_events WHERE tenant_id = ? AND event_type LIKE 'billing.day.%' ORDER BY created_at, event_type",
                String.class, t))).containsExactlyInAnyOrder("billing.day.closed.v1", "billing.day.reopened.v1", "billing.day.closed.v1");
        Map<String, Object> payload = inTenantTx(t, () -> jdbc.queryForMap("SELECT payload::text AS p FROM outbox_events WHERE tenant_id = ? AND event_type = 'billing.day.closed.v1' " +
                "ORDER BY created_at DESC LIMIT 1", t));
        assertThat((String) payload.get("p")).contains("\"expectedCashMinor\": 80000").contains("\"closeCount\": 2").contains("\"currency\": \"INR\"");
    }

    // AC1 for a past day: close, reopen and re-close yesterday by date; future dates are refused; history pages by cursor.
    @Test
    void pastDayClosesReopensAndClosesAgainByDate() {
        Owner o = owner();
        String day = today(o.token());
        String yesterday = LocalDate.parse(day).minusDays(1).toString();

        assertThat(close(o.token(), 0, null, LocalDate.parse(day).plusDays(1).toString()).getBody().get("type")).asString().endsWith("date-in-future");
        assertThat(reopen(o.token(), yesterday).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<Map> c1 = close(o.token(), 0, null, yesterday);
        assertThat(c1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(c1.getBody()).containsEntry("date", yesterday).containsEntry("today", false);
        assertThat(close(o.token(), 0, null, yesterday).getBody().get("type")).asString().endsWith("day-already-closed");
        ResponseEntity<Map> r = reopen(o.token(), yesterday);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(closeOf(r)).containsEntry("status", "reopened");
        // today was never touched
        assertThat(exchange("/v1/billing/day-close", HttpMethod.GET, authed(o.token()), Map.class).getBody().get("close")).isNull();
        ResponseEntity<Map> c2 = close(o.token(), 0, null, yesterday);
        assertThat(closeOf(c2)).containsEntry("status", "closed").containsEntry("closeCount", 2);

        ResponseEntity<Map> p1 = history(o.token(), "?from=" + yesterday + "&to=" + day + "&limit=2");
        assertThat((List<?>) p1.getBody().get("items")).hasSize(2);
        String cursor = (String) p1.getBody().get("nextCursor");
        assertThat(cursor).isNotNull();
        ResponseEntity<Map> p2 = history(o.token(), "?from=" + yesterday + "&to=" + day + "&limit=2&cursor=" + cursor);
        List<Map<String, Object>> rest = (List<Map<String, Object>>) p2.getBody().get("items");
        assertThat(rest).singleElement().satisfies(i -> assertThat(i).containsEntry("action", "close"));
        assertThat(p2.getBody().get("nextCursor")).isNull();
        assertThat(history(o.token(), "?cursor=nonsense").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(history(o.token(), "?from=" + day + "&to=" + yesterday).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // the range filters by the closed day: today alone has nothing
        assertThat((List<?>) history(o.token(), "?from=" + day + "&to=" + day).getBody().get("items")).isEmpty();
    }

    // AC2: payment and close released together by a latch, several rounds: a payment is either counted by the close or 409 DAY_CLOSED.
    @Test
    void closeRacingPaymentNeverLeavesAPaymentCountedAfterTheClose() {
        Owner o = owner();
        String inv = bill(o);
        UUID t = o.tenant().id();
        int counted = 0;
        int refused = 0;
        for (int round = 0; round < 6; round++) {
            List<Callable<ResponseEntity<Map>>> tasks = List.of(() -> pay(o.token(), inv, 10),
                    () -> close(o.token(), 0, "race round", null));
            List<ResponseEntity<Map>> res = ConcurrencyHarness.race(tasks);
            ResponseEntity<Map> payment = res.get(0);
            ResponseEntity<Map> closed = res.get(1);
            assertThat(closed.getStatusCode()).as("close " + closed.getBody()).isEqualTo(HttpStatus.OK);
            BigDecimal cashInDb = inTenantTx(t, () -> jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount), 0) FROM invoice_payments WHERE tenant_id = ? AND method = 'cash'", BigDecimal.class, t));
            // never counted-after-close: what the close expected is every cash payment that exists
            assertThat(num(closeOf(closed).get("expectedCash"))).as("round " + round).isEqualTo(cashInDb.doubleValue());
            if (payment.getStatusCode() == HttpStatus.CREATED) {
                counted++;
            } else {
                assertThat(payment.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(payment.getBody().get("type")).asString().endsWith("day-closed");
                refused++;
            }
            assertThat(reopen(o.token(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(counted + refused).isEqualTo(6);
    }

    // AC2, deterministic half: payment and close both wait on the tenant-day lock while another transaction holds it.
    @Test
    void paymentAndCloseWaitForTheTenantDayLock() throws Exception {
        Owner o = owner();
        String inv = bill(o);
        String day = today(o.token());
        try (Connection su = superuserConnection()) {
            su.setAutoCommit(false);
            try (PreparedStatement ps = su.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?), ?)")) {
                ps.setString(1, "day-close:" + o.tenant().id());
                ps.setInt(2, (int) LocalDate.parse(day).toEpochDay());
                ps.execute();
            }
            CompletableFuture<ResponseEntity<Map>> payment = CompletableFuture.supplyAsync(() -> pay(o.token(), inv, 25));
            assertThatThrownBy(() -> payment.get(800, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            CompletableFuture<ResponseEntity<Map>> closing = CompletableFuture.supplyAsync(() -> close(o.token(), 25, null, null));
            assertThatThrownBy(() -> closing.get(800, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            su.commit(); // release
            ResponseEntity<Map> p = payment.get(30, TimeUnit.SECONDS);
            ResponseEntity<Map> c = closing.get(30, TimeUnit.SECONDS);
            assertThat(c.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(num(closeOf(c).get("expectedCash"))).isEqualTo(p.getStatusCode() == HttpStatus.CREATED ? 25.0 : 0.0);
        }
    }

    // AC3: tolerance comes from the cash_variance_tolerance clinic policy (minor units), default 200 for INR.
    @Test
    void toleranceComesFromClinicPolicy() {
        Owner o = owner();
        assertThat(num(exchange("/v1/billing/day-close", HttpMethod.GET, authed(o.token()), Map.class).getBody().get("cashTolerance"))).isEqualTo(200.0);
        assertThat(exchange("/v1/setup/policies/cash_variance_tolerance", HttpMethod.PATCH, authedJsonBody(o.token(), Map.of("value", "12.5")),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(exchange("/v1/setup/policies/cash_variance_tolerance", HttpMethod.PATCH, authedJsonBody(o.token(), Map.of("value", "50000")),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(num(exchange("/v1/billing/day-close", HttpMethod.GET, authed(o.token()), Map.class).getBody().get("cashTolerance"))).isEqualTo(500.0);
        // off by 400: within the 500 policy, so no note needed (the old ₹200 constant would have demanded one)
        assertThat(close(o.token(), 400, null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        // boundary: tighten to 399.99 -> the same variance now needs a note
        exchange("/v1/setup/policies/cash_variance_tolerance", HttpMethod.PATCH, authedJsonBody(o.token(), Map.of("value", "39999")), Map.class);
        assertThat(reopen(o.token(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(close(o.token(), 400, null, null).getBody().get("type")).asString().endsWith("variance-note-required");
        // the defaults the other tenants keep are untouched
        assertThat(num(exchange("/v1/billing/day-close", HttpMethod.GET, authed(owner().token()), Map.class).getBody().get("cashTolerance"))).isEqualTo(200.0);
    }

    // AC4: once today is closed, checkout, payment, package sale and package refund approval are all 409 DAY_CLOSED.
    @Test
    void closedDayRefusesEveryMoneyPath() {
        Owner o = owner();
        String checker = loginAndGetAccessToken(seedStaff(o.tenant(), o.role(), "f5c" + UUID.randomUUID() + "@a.com", phone(), false));
        String inv = bill(o);
        assertThat(pay(o.token(), inv, 100).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> pkgBody = new HashMap<>(Map.of("name", "F5 Pkg " + SEQ.incrementAndGet(), "packageType", "combination", "speciality", "Physio",
                "description", "d", "price", 900.00, "taxInclusive", false, "validityDays", 60, "validityStarts", "purchase_date"));
        pkgBody.put("graceDays", 0);
        pkgBody.put("items", List.of(Map.of("itemType", "service_session", "name", "Session", "quantity", 3, "unitListPrice", 300.00, "taxRatePercent", 0.0)));
        String pkgId = (String) exchange("/v1/packages", HttpMethod.POST, authedJsonBody(o.token(), pkgBody), Map.class).getBody().get("id");
        assertThat(exchange("/v1/packages/" + pkgId + "/activate", HttpMethod.POST, authed(o.token()), Map.class).getStatusCode().is2xxSuccessful()).isTrue();
        ResponseEntity<Map> sold = exchange("/v1/packages/sell", HttpMethod.POST, authedJsonBody(o.token(), Map.of(
                "patientId", patient(o.token()), "packageId", pkgId, "paymentMethod", "upi")), Map.class);
        assertThat(sold.getStatusCode().is2xxSuccessful()).as(String.valueOf(sold.getBody())).isTrue();
        ResponseEntity<Map> refund = exchange("/v1/packages/instances/" + sold.getBody().get("id") + "/refund", HttpMethod.POST,
                authedJsonBody(o.token(), Map.of("reason", "Moving away")), Map.class);
        assertThat(refund.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(close(o.token(), 100, null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        String lateVisit = visitAtCheckout(o); // a visit reaching checkout after the close

        ResponseEntity<Map> co = checkout(o.token(), lateVisit);
        ResponseEntity<Map> payment = pay(o.token(), inv, 50);
        ResponseEntity<Map> sale = exchange("/v1/packages/sell", HttpMethod.POST, authedJsonBody(o.token(), Map.of(
                "patientId", patient(o.token()), "packageId", pkgId, "paymentMethod", "cash")), Map.class);
        ResponseEntity<Map> approve = exchange("/v1/packages/refunds/" + refund.getBody().get("id") + "/approve", HttpMethod.POST, authed(checker), Map.class);
        for (ResponseEntity<Map> r : List.of(co, payment, sale, approve)) {
            assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CONFLICT);
            assertThat(r.getBody().get("type")).asString().endsWith("day-closed");
        }
        UUID t = o.tenant().id();
        assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT count(*) FROM package_refunds WHERE tenant_id = ? AND status = 'pending'", Integer.class, t))).isEqualTo(1);

        // after a reopen the same refund approval goes through
        assertThat(reopen(o.token(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/packages/refunds/" + refund.getBody().get("id") + "/approve", HttpMethod.POST, authed(checker), Map.class)
                .getStatusCode().is2xxSuccessful()).isTrue();
    }

    // Tenant isolation of day_close_events (forced RLS, ADR-07 policy) and of the history endpoint.
    @Test
    void historyAndEventsAreTenantIsolated() {
        Owner a = owner();
        Owner b = owner();
        assertThat(close(a.token(), 0, null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) history(a.token(), "").getBody().get("items")).hasSize(1);
        assertThat((List<?>) history(b.token(), "").getBody().get("items")).isEmpty();
        assertThat(inTenantTx(b.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM day_close_events WHERE tenant_id = ?",
                Integer.class, a.tenant().id()))).isZero();
        assertThat(inTenantTx(a.tenant().id(), () -> jdbc.queryForObject("SELECT count(*) FROM day_close_events WHERE tenant_id = ?",
                Integer.class, a.tenant().id()))).isEqualTo(1);
        UUID closeId = inTenantTx(a.tenant().id(), () -> jdbc.queryForObject("SELECT id FROM day_closes WHERE tenant_id = ?", UUID.class, a.tenant().id()));
        assertThatThrownBy(() -> inTenantTx(b.tenant().id(), () -> jdbc.update(
                "INSERT INTO day_close_events (tenant_id, day_close_id, action, note, actor_id) VALUES (?, ?, 'reopen', 'x', ?)",
                a.tenant().id(), closeId, a.staffId()))).rootCause().hasMessageContaining("violates row-level security policy");
    }

    @Test
    void historyNeedsBillingView() {
        SeededTenant t = seedTenant();
        UUID none = seedRole(t.id(), "Front desk", false, new com.nabd.hms.common.ModuleGrant("patients", true, true, false, false, false, false, false));
        String token = loginAndGetAccessToken(seedStaff(t, none, "f5n" + UUID.randomUUID() + "@a.com", phone(), false));
        assertThat(history(token, "").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}

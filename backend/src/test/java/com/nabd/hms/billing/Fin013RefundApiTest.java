package com.nabd.hms.billing;

import com.nabd.hms.common.ModuleGrant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FIN-013: refund requests, maker-checker, credit notes with per-line tax reversal, refund payment rows, day close. */
class Fin013RefundApiTest extends S10QaBillingSupport {

    @Autowired com.nabd.hms.common.ClinicClock clinicClock;

    private record Fx(Env env, String maker, String manager) {
    }

    private Fx fx() {
        Env e = env();
        catalogue(e.tenant(), "G18", "Service", 1000, null, 18);
        catalogue(e.tenant(), "G5", "Service", 500, null, 5);
        catalogue(e.tenant(), "G0", "Service", 200, null, 0);
        String maker = tokenFor(e, "Biller", true, true, true, false, false);
        UUID role = seedRole(e.tenant().id(), "Manager", false, new ModuleGrant("billing", true, false, false, false, true, false, false));
        String manager = loginAndGetAccessToken(seedStaff(e.tenant(), role, "mgr" + UUID.randomUUID() + "@a.com", phone(), false));
        return new Fx(e, maker, manager);
    }

    /** A paid invoice (one cash payment of the full total) of these catalogue codes, quantity 1 each. */
    private Map<?, ?> paid(Fx f, String... codes) {
        List<Map<String, Object>> lines = new java.util.ArrayList<>();
        for (String c : codes) lines.add(line(c, 1));
        ResponseEntity<Map> r = checkout(f.env().token(), atCheckout(f.env()), lines);
        assertThat(r.getStatusCode().is2xxSuccessful()).as(String.valueOf(r.getBody())).isTrue();
        String id = (String) r.getBody().get("id");
        assertThat(pay(f.env().token(), id, "cash", r.getBody().get("total")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return getInvoice(f.env().token(), id).getBody();
    }

    private static String id(Map<?, ?> invoice) {
        return (String) invoice.get("id");
    }

    private static String lineId(Map<?, ?> invoice, int i) {
        return (String) ((Map<?, ?>) ((List<?>) invoice.get("lineItems")).get(i)).get("id");
    }

    private static String paymentId(Map<?, ?> invoice) {
        return (String) ((Map<?, ?>) ((List<?>) invoice.get("payments")).get(0)).get("id");
    }

    private ResponseEntity<Map> request(String token, Map<?, ?> invoice, Map<String, Object> body) {
        return exchange("/v1/billing/invoices/" + id(invoice) + "/refund-requests", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private Map<String, Object> wholeLine(Map<?, ?> invoice, int line, Object amount) {
        Map<String, Object> b = new HashMap<>();
        b.put("lines", List.of(Map.of("lineId", lineId(invoice, line), "quantity", 1)));
        b.put("reasonCode", "billing_error");
        b.put("refundTo", List.of(Map.of("paymentId", paymentId(invoice), "amount", amount)));
        return b;
    }

    private ResponseEntity<Map> approve(String token, Object requestId, Object expected) {
        return exchange("/v1/billing/refund-requests/" + requestId + "/approve", HttpMethod.POST, authedJsonBody(token, Map.of("expectedAmount", expected)), Map.class);
    }

    private static String code(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("code"));
    }

    // AC1: INR 1,180 paid invoice, full refund -> credit note reverses 1,000 + 180, invoice refunded, number from the credit_note series
    @Test
    void fullRefundIssuesTheCreditNoteReversesTaxAndMarksTheInvoiceRefunded() {
        Fx f = fx();
        exchange("/v1/organization/invoice-series/credit_note", HttpMethod.PUT, authedJsonBody(f.env().token(), Map.of("prefixPattern", "CN/{FY}/")), Map.class);
        Map<?, ?> inv = paid(f, "G18");
        assertThat(num(inv.get("total"))).isEqualTo(1180.0);

        ResponseEntity<Map> req = request(f.maker(), inv, wholeLine(inv, 0, 1180));
        assertThat(req.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(req.getBody().get("status")).isEqualTo("pending");
        assertThat(num(req.getBody().get("amount"))).isEqualTo(1180.0);

        ResponseEntity<Map> ok = approve(f.manager(), req.getBody().get("id"), 1180);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) ok.getBody().get("creditNoteNumber")).matches("CN/\\d\\d-\\d\\d/000001");

        Map<String, Object> cn = inTenantTx(f.env().tenant().id(), () -> jdbc.queryForMap(
                "SELECT taxable_minor, tax_minor, total_minor, number FROM credit_notes WHERE invoice_id = ?::uuid", id(inv)));
        assertThat(cn).containsEntry("taxable_minor", 100000L).containsEntry("tax_minor", 18000L).containsEntry("total_minor", 118000L);
        Map<?, ?> after = getInvoice(f.env().token(), id(inv)).getBody();
        assertThat(after.get("status")).isEqualTo("refunded");
        assertThat(num(after.get("paid"))).isEqualTo(0.0);
        List<Map<String, Object>> rows = inTenantTx(f.env().tenant().id(), () -> jdbc.queryForList(
                "SELECT kind, method, amount, amount_minor FROM invoice_payments WHERE invoice_id = ?::uuid ORDER BY kind DESC", id(inv)));
        assertThat(rows).extracting(m -> m.get("kind")).containsExactly("refund", "payment");
        assertThat(rows.get(0)).containsEntry("method", "cash").containsEntry("amount_minor", -118000L);
        // the audit rows and the event
        assertThat(count(f.env(), "SELECT count(*) FROM audit_log WHERE action IN ('billing.refund_requested','billing.refund_approved') AND entity_id = ?::uuid", req.getBody().get("id"))).isEqualTo(2);
        Map<String, Object> ev = inTenantTx(f.env().tenant().id(), () -> jdbc.queryForMap(
                "SELECT envelope->'data' AS d FROM outbox_cloudevents WHERE envelope->>'type' = 'billing.refund.approved.v1' AND envelope->'data'->>'invoiceId' = ?", id(inv)));
        assertThat(ev.get("d").toString()).contains("118000").contains("cash").doesNotContain("note");
        // a fully refunded invoice takes no more refunds or payments
        assertThat(request(f.maker(), inv, wholeLine(inv, 0, 1)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(pay(f.env().token(), id(inv), "cash", 1).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // AC2
    @Test
    void requesterCannotApproveHisOwnRequest() {
        Fx f = fx();
        Map<?, ?> inv = paid(f, "G18");
        ResponseEntity<Map> req = request(f.env().token(), inv, wholeLine(inv, 0, 1180));
        ResponseEntity<Map> r = approve(f.env().token(), req.getBody().get("id"), 1180);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(r)).isEqualTo("SAME_USER");
        assertThat(count(f.env(), "SELECT count(*) FROM credit_notes")).isZero();
    }

    private Fx fxWithThreshold(long minor) {
        Fx f = fx();
        inTenantTx(f.env().tenant().id(), () -> jdbc.update("INSERT INTO clinic_policies (tenant_id, policy_key, value) VALUES (?, 'refund_owner_threshold', ?)",
                f.env().tenant().id(), String.valueOf(minor)));
        return f;
    }

    // AC3: above INR 2,000 (default) only the owner role approves
    @Test
    void aboveTheDefaultThresholdOnlyTheOwnerRoleCanApprove() {
        Fx f = fx();
        Map<?, ?> big = paid(f, "G18", "G18"); // 2,360 > 2,000
        ResponseEntity<Map> req = request(f.maker(), big, new HashMap<>(Map.of("amount", 2360, "reasonCode", "billing_error",
                "refundTo", List.of(Map.of("paymentId", paymentId(big), "amount", 2360)))));
        assertThat(req.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> denied = approve(f.manager(), req.getBody().get("id"), 2360);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(denied)).isEqualTo("THRESHOLD_REQUIRES_OWNER");
        assertThat(count(f.env(), "SELECT count(*) FROM credit_notes")).isZero();
        assertThat(approve(f.env().token(), req.getBody().get("id"), 2360).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theThresholdIsStrictlyAbove_andTheClinicPolicyOverridesTheDefault() {
        Fx atEdge = fxWithThreshold(118000); // INR 1,180.00
        Map<?, ?> inv = paid(atEdge, "G18");
        ResponseEntity<Map> req = request(atEdge.maker(), inv, wholeLine(inv, 0, 1180));
        assertThat(approve(atEdge.manager(), req.getBody().get("id"), 1180).getStatusCode()).isEqualTo(HttpStatus.OK); // equal is not above

        Fx below = fxWithThreshold(117999);
        Map<?, ?> inv2 = paid(below, "G18");
        ResponseEntity<Map> req2 = request(below.maker(), inv2, wholeLine(inv2, 0, 1180));
        assertThat(code(approve(below.manager(), req2.getBody().get("id"), 1180))).isEqualTo("THRESHOLD_REQUIRES_OWNER");
    }

    // AC4: expected cash and the Refunds block
    @Test
    void approvedCashRefundReducesTodaysExpectedCashAndShowsUnderRefunds() {
        Fx f = fx();
        Map<?, ?> inv = paid(f, "G18");
        Map<?, ?> before = exchange("/v1/billing/day-close", HttpMethod.GET, authed(f.env().token()), Map.class).getBody();
        double cashBefore = num(((Map<?, ?>) ((List<?>) before.get("methods")).get(0)).get("amount"));
        ResponseEntity<Map> req = request(f.maker(), inv, wholeLine(inv, 0, 1180));
        approve(f.manager(), req.getBody().get("id"), 1180);
        Map<?, ?> after = exchange("/v1/billing/day-close", HttpMethod.GET, authed(f.env().token()), Map.class).getBody();
        Map<?, ?> cash = (Map<?, ?>) ((List<?>) after.get("methods")).get(0);
        assertThat(cash.get("method")).isEqualTo("cash");
        assertThat(num(cash.get("amount"))).isEqualTo(cashBefore - 1180);
        Map<?, ?> refundCash = (Map<?, ?>) ((List<?>) after.get("refunds")).get(0);
        assertThat(refundCash.get("method")).isEqualTo("cash");
        assertThat(num(refundCash.get("amount"))).isEqualTo(1180.0);
        assertThat(((Number) refundCash.get("transactions")).intValue()).isEqualTo(1);
        // closing the day uses the netted figure as expected cash
        ResponseEntity<Map> close = exchange("/v1/billing/day-close", HttpMethod.POST, authedJsonBody(f.env().token(), Map.of("countedCash", cashBefore - 1180)), Map.class);
        assertThat(close.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(num(((Map<?, ?>) close.getBody().get("close")).get("expectedCash"))).isEqualTo(cashBefore - 1180);
    }

    // AC5: the invoice's day is closed, today is open: credit note dated today, the closed day is untouched
    @Test
    void refundOnAnOldClosedDaysInvoiceIsDatedTodayAndLeavesThatDayAlone() {
        Fx f = fx();
        Map<?, ?> inv = paid(f, "G18");
        ZoneId zone = clinicClock.zone(f.env().tenant().id());
        LocalDate today = clinicClock.today(zone);
        LocalDate yesterday = today.minusDays(1);
        var past = java.sql.Timestamp.from(yesterday.atTime(11, 0).atZone(zone).toInstant());
        var su = new org.springframework.jdbc.core.JdbcTemplate(superuserDataSource());
        su.update("UPDATE invoices SET created_at = ? WHERE id = ?::uuid", past, id(inv));
        su.update("UPDATE invoice_payments SET recorded_at = ? WHERE invoice_id = ?::uuid", past, id(inv));
        ResponseEntity<Map> closed = exchange("/v1/billing/day-close", HttpMethod.POST, authedJsonBody(f.env().token(),
                Map.of("date", yesterday.toString(), "countedCash", 1180)), Map.class);
        assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> req = request(f.maker(), inv, wholeLine(inv, 0, 1180));
        ResponseEntity<Map> ok = approve(f.manager(), req.getBody().get("id"), 1180);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);

        java.sql.Timestamp issued = inTenantTx(f.env().tenant().id(), () -> jdbc.queryForObject(
                "SELECT issued_at FROM credit_notes WHERE invoice_id = ?::uuid", java.sql.Timestamp.class, id(inv)));
        assertThat(issued.toInstant().atZone(zone).toLocalDate()).isEqualTo(today);
        Map<?, ?> yday = exchange("/v1/billing/day-close?date=" + yesterday, HttpMethod.GET, authed(f.env().token()), Map.class).getBody();
        assertThat(num(((Map<?, ?>) ((List<?>) yday.get("methods")).get(0)).get("amount"))).isEqualTo(1180.0);
        assertThat(num(((Map<?, ?>) ((List<?>) yday.get("refunds")).get(0)).get("amount"))).isEqualTo(0.0);
        assertThat(((Map<?, ?>) yday.get("close")).get("status")).isEqualTo("closed");
        Map<?, ?> tday = exchange("/v1/billing/day-close", HttpMethod.GET, authed(f.env().token()), Map.class).getBody();
        assertThat(num(((Map<?, ?>) ((List<?>) tday.get("refunds")).get(0)).get("amount"))).isEqualTo(1180.0);
    }

    @Test
    void approvalIsRefusedWhileTodaysDayIsClosed() {
        Fx f = fx();
        Map<?, ?> inv = paid(f, "G18");
        ResponseEntity<Map> req = request(f.maker(), inv, wholeLine(inv, 0, 1180));
        assertThat(exchange("/v1/billing/day-close", HttpMethod.POST, authedJsonBody(f.env().token(), Map.of("countedCash", 1180)), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> r = approve(f.manager(), req.getBody().get("id"), 1180);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(r.getBody().get("type"))).contains("day-closed");
    }

    // AC6: 1 of 3 lines
    @Test
    void partialRefundOfOneLineReversesOnlyThatLinesTaxableValueAndTax() {
        Fx f = fx();
        Map<?, ?> inv = paid(f, "G18", "G5", "G0"); // 1,180 + 525 + 200 = 1,905
        ResponseEntity<Map> req = request(f.maker(), inv, wholeLine(inv, 1, 525));
        assertThat(req.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(approve(f.manager(), req.getBody().get("id"), 525).getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> cl = inTenantTx(f.env().tenant().id(), () -> jdbc.queryForList(
                "SELECT l.taxable_minor, l.tax_minor, l.quantity, l.taxes::text AS taxes FROM credit_note_lines l JOIN credit_notes c ON c.id = l.credit_note_id WHERE c.invoice_id = ?::uuid", id(inv)));
        assertThat(cl).hasSize(1);
        assertThat(cl.get(0)).containsEntry("taxable_minor", 50000L).containsEntry("tax_minor", 2500L).containsEntry("quantity", 1);
        assertThat(cl.get(0).get("taxes").toString()).contains("CGST").contains("SGST");
        Map<?, ?> after = getInvoice(f.env().token(), id(inv)).getBody();
        assertThat(after.get("status")).isEqualTo("paid"); // the rest of the bill is still fully paid
        assertThat(num(after.get("paid"))).isEqualTo(1380.0);
        // the same line cannot be refunded twice
        ResponseEntity<Map> again = request(f.maker(), inv, wholeLine(inv, 1, 525));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(again)).isEqualTo("EXCEEDS_REFUNDABLE");
    }

    @Test
    void goodwillAmountSplitsTaxableAndTaxInTheInvoicedProportion() {
        Fx f = fx();
        Map<?, ?> inv = paid(f, "G18");
        Map<String, Object> b = new HashMap<>(Map.of("amount", 100, "reasonCode", "goodwill", "note", "late",
                "refundTo", List.of(Map.of("paymentId", paymentId(inv), "amount", 100))));
        ResponseEntity<Map> req = request(f.maker(), inv, b);
        assertThat(req.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(approve(f.manager(), req.getBody().get("id"), 100).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> cn = inTenantTx(f.env().tenant().id(), () -> jdbc.queryForMap(
                "SELECT taxable_minor, tax_minor, total_minor FROM credit_notes WHERE invoice_id = ?::uuid", id(inv)));
        assertThat(cn).containsEntry("taxable_minor", 8475L).containsEntry("tax_minor", 1525L).containsEntry("total_minor", 10000L);
        Map<?, ?> after = getInvoice(f.env().token(), id(inv)).getBody();
        assertThat(after.get("status")).isEqualTo("paid");
        // more than is left: 1,080 left, ask for 1,080.01
        Map<String, Object> over = new HashMap<>(Map.of("amount", 1080.01, "reasonCode", "goodwill",
                "refundTo", List.of(Map.of("paymentId", paymentId(inv), "amount", 1080.01))));
        assertThat(code(request(f.maker(), inv, over))).isEqualTo("EXCEEDS_REFUNDABLE");
        // exactly what is left closes the invoice
        Map<String, Object> rest = new HashMap<>(Map.of("amount", 1080, "reasonCode", "goodwill",
                "refundTo", List.of(Map.of("paymentId", paymentId(inv), "amount", 1080))));
        ResponseEntity<Map> r2 = request(f.maker(), inv, rest);
        assertThat(approve(f.manager(), r2.getBody().get("id"), 1080).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getInvoice(f.env().token(), id(inv)).getBody().get("status")).isEqualTo("refunded");
        assertThat(count(f.env(), "SELECT count(*) FROM (SELECT c.invoice_id FROM credit_notes c WHERE c.invoice_id = ?::uuid GROUP BY 1 HAVING sum(taxable_minor) = 100000 AND sum(tax_minor) = 18000) x", id(inv))).isEqualTo(1);
    }

    @Test
    void requestValidationAndDecisionRules() {
        Fx f = fx();
        Map<?, ?> inv = paid(f, "G18");
        // both lines and amount
        Map<String, Object> both = wholeLine(inv, 0, 1180);
        both.put("amount", 10);
        assertThat(request(f.maker(), inv, both).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // refundTo that does not add up
        assertThat(code(request(f.maker(), inv, wholeLine(inv, 0, 1000)))).isEqualTo("REFUND_TO_MISMATCH");
        // a payment of another invoice
        Map<String, Object> foreign = wholeLine(inv, 0, 1180);
        foreign.put("refundTo", List.of(Map.of("paymentId", UUID.randomUUID().toString(), "amount", 1180)));
        assertThat(request(f.maker(), inv, foreign).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // no billing:edit
        assertThat(request(f.manager(), inv, wholeLine(inv, 0, 1180)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // one open request per invoice
        ResponseEntity<Map> first = request(f.maker(), inv, wholeLine(inv, 0, 1180));
        ResponseEntity<Map> dup = request(f.maker(), inv, wholeLine(inv, 0, 1180));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(dup)).isEqualTo("OPEN_REQUEST_EXISTS");
        // a stale expected amount
        ResponseEntity<Map> stale = approve(f.manager(), first.getBody().get("id"), 1000);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(stale)).isEqualTo("AMOUNT_CHANGED");
        // reject needs billing:approve and ends the request; approving afterwards is 409
        String rejectUrl = "/v1/billing/refund-requests/" + first.getBody().get("id") + "/reject";
        assertThat(exchange(rejectUrl, HttpMethod.POST, authedJsonBody(f.maker(), Map.of("reason", "no")), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> rej = exchange(rejectUrl, HttpMethod.POST, authedJsonBody(f.manager(), Map.of("reason", "not a billing error")), Map.class);
        assertThat(rej.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rej.getBody().get("status")).isEqualTo("rejected");
        assertThat(code(approve(f.manager(), first.getBody().get("id"), 1180))).isEqualTo("ALREADY_DECIDED");
        assertThat(code(exchange(rejectUrl, HttpMethod.POST, authedJsonBody(f.manager(), Map.of("reason", "again")), Map.class))).isEqualTo("ALREADY_DECIDED");
        // after the rejection a new request is allowed; the list shows both, filtered by status, paged
        assertThat(request(f.maker(), inv, wholeLine(inv, 0, 1180)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> all = exchange("/v1/billing/refund-requests?limit=1", HttpMethod.GET, authed(f.manager()), Map.class).getBody();
        assertThat((List<?>) all.get("items")).hasSize(1);
        assertThat(all.get("nextCursor")).isNotNull();
        Map<?, ?> pending = exchange("/v1/billing/refund-requests?status=pending", HttpMethod.GET, authed(f.manager()), Map.class).getBody();
        assertThat((List<?>) pending.get("items")).hasSize(1);
        assertThat(exchange("/v1/billing/refund-requests?status=bogus", HttpMethod.GET, authed(f.manager()), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // unknown request is 404
        assertThat(approve(f.manager(), UUID.randomUUID(), 1).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anotherClinicCannotSeeOrDecideThisClinicsRefunds() {
        Fx a = fx();
        Fx b = fx();
        Map<?, ?> inv = paid(a, "G18");
        ResponseEntity<Map> req = request(a.maker(), inv, wholeLine(inv, 0, 1180));
        assertThat(approve(b.env().token(), req.getBody().get("id"), 1180).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(request(b.maker(), inv, wholeLine(inv, 0, 1180)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<?, ?> list = exchange("/v1/billing/refund-requests", HttpMethod.GET, authed(b.manager()), Map.class).getBody();
        assertThat((List<?>) list.get("items")).isEmpty();
        // RLS: tenant B sees none of A's rows even by direct SQL
        assertThat(count(b.env(), "SELECT count(*) FROM refund_requests WHERE tenant_id = ?::uuid", a.env().tenant().id())).isZero();
        assertThat(count(b.env(), "SELECT count(*) FROM credit_notes WHERE tenant_id = ?::uuid", a.env().tenant().id())).isZero();
    }

    @Test
    void resaleablePharmacyReturnGoesBackOnTheShelfOnlyWhenMarkedResaleable() {
        Env e = env();
        String maker = tokenFor(e, "Biller", true, true, true, false, false);
        exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(e.token(), Map.of("mode", "hybrid", "reason", "Test setup")), Map.class);
        String code = (String) exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(e.token(), Map.of(
                "name", "Paracetamol 500mg", "isRx", false, "hsnCode", "3004", "price", 20.00, "taxRatePercent", 12.00, "stockQty", 10)), Map.class).getBody().get("code");
        ResponseEntity<Map> sale = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(e.token(), Map.of("lineItems",
                List.of(Map.of("chargeCode", code, "quantity", 4)))), Map.class);
        assertThat(sale.getStatusCode()).isEqualTo(HttpStatus.OK);
        String inv = (String) sale.getBody().get("id");
        pay(e.token(), inv, "cash", sale.getBody().get("total"));
        Map<?, ?> full = getInvoice(e.token(), inv).getBody();
        // 1 unit resaleable, 1 unit not: stock 6 -> 7
        for (boolean resale : new boolean[]{true, false}) {
            Map<String, Object> b = new HashMap<>();
            b.put("lines", List.of(Map.of("lineId", lineId(full, 0), "quantity", 1, "resaleable", resale)));
            b.put("reasonCode", "customer_return");
            b.put("refundTo", List.of(Map.of("paymentId", paymentId(full), "amount", 22.40)));
            ResponseEntity<Map> req = request(maker, full, b);
            assertThat(req.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(approve(e.token(), req.getBody().get("id"), 22.40).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        Integer stock = inTenantTx(e.tenant().id(), () -> jdbc.queryForObject("SELECT stock_qty FROM charge_catalogue WHERE tenant_id = ? AND code = ?", Integer.class, e.tenant().id(), code));
        assertThat(stock).isEqualTo(7);
        assertThat(count(e, "SELECT count(*) FROM pharmacy_stock_movements WHERE kind = 'restock' AND invoice_id = ?::uuid", inv)).isEqualTo(1);
    }
}

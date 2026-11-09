package com.nabd.hms.billing;

import com.nabd.hms.common.ApiException;
import com.nabd.hms.common.ClinicClock;
import com.nabd.hms.pharmacy.api.StockCommands;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PHL-011: counter return against the bill -> maker-checker refund -> credit note with tax, stock back to its batch (or quarantine). */
@SuppressWarnings({"unchecked", "rawtypes"})
class Phl011PharmacyReturnApiTest extends S10QaBillingSupport {

    @Autowired StockCommands stock;
    @Autowired ClinicClock clock;

    /** owner = e.token() (billing:approve, Owner role); maker = a pharmacist with billing view/create/edit only. */
    private record Fx(Env e, String maker) {
        UUID t() {
            return e.tenant().id();
        }
    }

    private Fx fx() {
        Env e = env();
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(e.token(), Map.of("mode", "in_house", "reason", "PHL-011 test")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Fx(e, tokenFor(e, "Pharmacist", true, true, true, false, false));
    }

    /** A stocked item (price 20.00, tax 12%) and its batch with {@code qty} units; returns {itemId, code, batchId}. */
    private String[] item(Fx f, String name, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("name", name, "isRx", false, "hsnCode", "3004", "price", 20.00, "taxRatePercent", 12.00));
        body.putAll(extra);
        ResponseEntity<Map> r = exchange("/v1/pharmacy/items", HttpMethod.POST, authedJsonBody(f.e().token(), body), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString((String) r.getBody().get("id"));
        UUID batch = inTenantTx(f.t(), () -> stock.receiveBatch(id, new StockCommands.BatchReceipt("B-" + name.hashCode(), clock.today(f.t()).plusDays(300),
                new BigDecimal("25.00"), new BigDecimal("10.0000"), 10, "A1", null, null), f.e().owner().id(), "GRN").batchId());
        return new String[]{id.toString(), (String) r.getBody().get("code"), batch.toString()};
    }

    /** A paid counter sale of these {code, qty} pairs; returns the invoice as GET /billing/invoices/{id} shows it. */
    private Map<?, ?> sale(Fx f, Object... codeQty) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < codeQty.length; i += 2) lines.add(Map.of("chargeCode", codeQty[i], "quantity", codeQty[i + 1]));
        ResponseEntity<Map> s = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(f.e().token(), Map.of("lineItems", lines)), Map.class);
        assertThat(s.getStatusCode()).as(String.valueOf(s.getBody())).isEqualTo(HttpStatus.OK);
        String inv = (String) s.getBody().get("id");
        assertThat(pay(f.e().token(), inv, "cash", s.getBody().get("total")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return getInvoice(f.e().token(), inv).getBody();
    }

    private static String lineId(Map<?, ?> inv, int i) {
        return (String) ((Map<?, ?>) ((List<?>) inv.get("lineItems")).get(i)).get("id");
    }

    private static Map<String, Object> ret(Map<?, ?> inv, int line, int qty, String condition) {
        return Map.of("invoiceId", inv.get("id"), "lines", List.of(Map.of("invoiceLineId", lineId(inv, line), "qty", qty, "condition", condition)));
    }

    private ResponseEntity<Map> post(String token, Object body) {
        return exchange("/v1/pharmacy/returns", HttpMethod.POST, withKey(token, UUID.randomUUID().toString(), body), Map.class);
    }

    private ResponseEntity<Map> approve(String token, Object refundId, Object expected) {
        return exchange("/v1/billing/refund-requests/" + refundId + "/approve", HttpMethod.POST, authedJsonBody(token, Map.of("expectedAmount", expected)), Map.class);
    }

    private ResponseEntity<Map> bill(String token, Object invoiceId) {
        return exchange("/v1/pharmacy/returns/bill?invoiceId=" + invoiceId, HttpMethod.GET, authed(token), Map.class);
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private int batchQty(String batch) {
        return su().queryForObject("SELECT qty_on_hand FROM stock_batches WHERE id = ?::uuid", Integer.class, batch);
    }

    private static Map<String, Object> lineOf(Map<?, ?> bill, int i) {
        return (Map<String, Object>) ((List<?>) bill.get("lines")).get(i);
    }

    // AC1: 2 strips of X from batch B sold, 1 returned -> credit note for 1 strip incl. tax, batch B +1 (after the approval)
    @Test
    void returningOneOfTwoStripsIssuesACreditNoteWithTaxAndPutsItBackIntoItsBatch() {
        Fx f = fx();
        String[] x = item(f, "Cetirizine 10mg strip", Map.of());
        Map<?, ?> inv = sale(f, x[1], 2);
        assertThat(su().queryForObject("SELECT batch_id::text FROM invoice_line_items WHERE id = ?::uuid", String.class, lineId(inv, 0))).isEqualTo(x[2]);
        assertThat(batchQty(x[2])).isEqualTo(8);

        Map<String, Object> body = ret(inv, 0, 1, "sealed");
        String key = UUID.randomUUID().toString();
        ResponseEntity<Map> r = exchange("/v1/pharmacy/returns", HttpMethod.POST, withKey(f.maker(), key, body), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("status")).isEqualTo("pending");
        assertThat(num(r.getBody().get("amount"))).isEqualTo(22.40);
        List<Map<?, ?>> to = (List<Map<?, ?>>) r.getBody().get("refundTo");
        assertThat(to).hasSize(1);
        assertThat(to.get(0).get("paymentId")).isEqualTo(((Map<?, ?>) ((List<?>) inv.get("payments")).get(0)).get("id"));
        // idempotent: the same key replays the same answer, no second request
        ResponseEntity<Map> again = exchange("/v1/pharmacy/returns", HttpMethod.POST, withKey(f.maker(), key, body), Map.class);
        assertThat(again.getBody().get("refundId")).isEqualTo(r.getBody().get("refundId"));
        assertThat(count(f.e(), "SELECT count(*) FROM refund_requests WHERE invoice_id = ?::uuid", inv.get("id"))).isEqualTo(1);
        // nothing moves before the second person approves (FIN-013 maker-checker; the maker cannot approve it)
        assertThat(batchQty(x[2])).isEqualTo(8);
        assertThat(count(f.e(), "SELECT count(*) FROM credit_notes WHERE invoice_id = ?::uuid", inv.get("id"))).isZero();
        assertThat(approve(f.maker(), r.getBody().get("refundId"), 22.40).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> ok = approve(f.e().token(), r.getBody().get("refundId"), 22.40);
        assertThat(ok.getStatusCode()).as(String.valueOf(ok.getBody())).isEqualTo(HttpStatus.OK);
        Map<String, Object> cn = inTenantTx(f.t(), () -> jdbc.queryForMap(
                "SELECT c.taxable_minor, c.tax_minor, c.total_minor, l.quantity FROM credit_notes c JOIN credit_note_lines l ON l.credit_note_id = c.id WHERE c.invoice_id = ?::uuid",
                inv.get("id")));
        assertThat(cn).containsEntry("taxable_minor", 2000L).containsEntry("tax_minor", 240L).containsEntry("total_minor", 2240L).containsEntry("quantity", 1);
        assertThat(batchQty(x[2])).isEqualTo(9);
        Map<String, Object> mv = su().queryForMap("SELECT kind, delta, batch_id::text AS b FROM pharmacy_stock_movements WHERE invoice_id = ?::uuid AND delta > 0",
                UUID.fromString((String) inv.get("id")));
        assertThat(mv).containsEntry("kind", "return_in").containsEntry("delta", 1).containsEntry("b", x[2]);
        assertThat(su().queryForObject("SELECT status FROM stock_batches WHERE id = ?::uuid", String.class, x[2])).isEqualTo("active");
        // refunded to the original method (cash), exact minor units
        assertThat(count(f.e(), "SELECT count(*) FROM invoice_payments WHERE invoice_id = ?::uuid AND kind = 'refund' AND method = 'cash' AND amount_minor = -2240",
                inv.get("id"))).isEqualTo(1);
        String data = inTenantTx(f.t(), () -> jdbc.queryForObject("SELECT envelope->'data' FROM outbox_cloudevents WHERE envelope->>'type' = 'pharmacy.return.accepted.v1' " +
                "AND envelope->'data'->>'invoiceId' = ?", String.class, inv.get("id")));
        assertThat(data).contains(x[2]).contains("\"quarantined\": false").contains(String.valueOf(ok.getBody().get("creditNoteId")));
        Map<?, ?> b = bill(f.maker(), inv.get("id")).getBody();
        assertThat(lineOf(b, 0)).containsEntry("soldQty", 2).containsEntry("returnedQty", 1).containsEntry("returnableQty", 1).containsEntry("allowed", true);
    }

    // AC2: IN_H1 / controlled, cold-chain, opened packs, not-returnable and non-stock lines are refused with the reason
    @Test
    void controlledColdChainOpenedAndNotReturnableLinesAreRefusedWithTheReason() {
        Fx f = fx();
        String[] h1 = item(f, "H1 item", Map.of());
        String[] cold = item(f, "Insulin pen", Map.of("coldChain", true));
        String[] keep = item(f, "Clinic only", Map.of("returnable", false));
        String[] plain = item(f, "ORS sachet", Map.of());
        Map<?, ?> inv = sale(f, h1[1], 1, cold[1], 1, keep[1], 1, plain[1], 1);
        // the H1 line as sold (class snapshot): Rx sales need a linked prescription, so the snapshot is set directly
        su().update("UPDATE invoice_line_items SET schedule_class = 'IN_H1' WHERE id = ?::uuid", UUID.fromString(lineId(inv, 0)));

        Map<String, String> expected = Map.of(lineId(inv, 0), "Schedule H1", lineId(inv, 1), "Cold-chain", lineId(inv, 2), "not returnable");
        for (var e : expected.entrySet()) {
            ResponseEntity<Map> r = post(f.maker(), Map.of("invoiceId", inv.get("id"), "lines", List.of(Map.of("invoiceLineId", e.getKey(), "qty", 1, "condition", "sealed"))));
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(type(r)).endsWith("return-not-allowed");
            assertThat((String) r.getBody().get("detail")).contains(e.getValue());
            assertThat(r.getBody().get("invoiceLineId")).isEqualTo(e.getKey());
        }
        ResponseEntity<Map> opened = post(f.maker(), ret(inv, 3, 1, "opened"));
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) opened.getBody().get("detail")).contains("Opened pack");
        // one refused line refuses the whole return: nothing was requested
        ResponseEntity<Map> mixed = post(f.maker(), Map.of("invoiceId", inv.get("id"), "lines", List.of(
                Map.of("invoiceLineId", lineId(inv, 3), "qty", 1, "condition", "sealed"), Map.of("invoiceLineId", lineId(inv, 1), "qty", 1, "condition", "sealed"))));
        assertThat(mixed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(count(f.e(), "SELECT count(*) FROM refund_requests WHERE invoice_id = ?::uuid", inv.get("id"))).isZero();
        // the screen shows the same reasons, and the sealed plain line as returnable
        Map<?, ?> b = bill(f.maker(), inv.get("id")).getBody();
        assertThat(lineOf(b, 0).get("allowed")).isEqualTo(false);
        assertThat((String) lineOf(b, 0).get("reason")).contains("Schedule H1");
        assertThat((String) lineOf(b, 1).get("reason")).contains("Cold-chain");
        assertThat(lineOf(b, 3)).containsEntry("allowed", true).containsEntry("returnableQty", 1);
        assertThat(b.get("refundMethods")).isEqualTo(List.of("cash"));
        // the billing desk's refund cannot put a cold-chain unit back on the shelf either (decision 80: no way around the rule)
        ResponseEntity<Map> desk = exchange("/v1/billing/invoices/" + inv.get("id") + "/refund-requests", HttpMethod.POST, authedJsonBody(f.maker(), Map.of(
                "lines", List.of(Map.of("lineId", lineId(inv, 1), "quantity", 1, "resaleable", true)), "reasonCode", "customer_return",
                "refundTo", List.of(Map.of("paymentId", ((Map<?, ?>) ((List<?>) inv.get("payments")).get(0)).get("id"), "amount", 22.40)))), Map.class);
        assertThat(desk.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(type(desk)).endsWith("return-not-allowed");
    }

    @Test
    void aChargeThatIsNotAStockedItemCannotBeReturnedHere() {
        Fx f = fx();
        String[] plain = item(f, "Plain", Map.of());
        Map<?, ?> inv = sale(f, plain[1], 1, "SVC", 1);
        ResponseEntity<Map> r = post(f.maker(), ret(inv, 1, 1, "sealed"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) r.getBody().get("detail")).contains("Not a stocked pharmacy item");
        assertThat(lineOf(bill(f.maker(), inv.get("id")).getBody(), 1).get("allowed")).isEqualTo(false);
    }

    // AC3: the batch expired after the sale -> the returned units go in quarantined, never sellable
    @Test
    void unitsOfABatchThatExpiredSinceTheSaleGoToQuarantine() {
        Fx f = fx();
        String[] x = item(f, "Amoxicillin syrup", Map.of());
        Map<?, ?> inv = sale(f, x[1], 2);
        su().update("UPDATE stock_batches SET expiry_date = ? WHERE id = ?::uuid", java.sql.Date.valueOf(clock.today(f.t()).minusDays(1)), x[2]);
        ResponseEntity<Map> r = post(f.maker(), ret(inv, 0, 1, "sealed"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(approve(f.e().token(), r.getBody().get("refundId"), 22.40).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> batch = su().queryForMap("SELECT qty_on_hand, status FROM stock_batches WHERE id = ?::uuid", x[2]);
        assertThat(batch).containsEntry("qty_on_hand", 9).containsEntry("status", "quarantined");
        assertThat(su().queryForObject("SELECT reason FROM pharmacy_stock_movements WHERE invoice_id = ?::uuid AND kind = 'return_in'", String.class,
                UUID.fromString((String) inv.get("id")))).contains("quarantined");
        // not sellable: the FEFO pick finds nothing to sell even though 9 units are on the shelf
        UUID item = UUID.fromString(x[0]);
        assertThatThrownBy(() -> inTenantTx(f.t(), () -> stock.pick(item, 1, null))).isInstanceOf(ApiException.class);
        // a fresh batch is still picked, the quarantined one never
        UUID fresh = inTenantTx(f.t(), () -> stock.receiveBatch(item, new StockCommands.BatchReceipt("FRESH", clock.today(f.t()).plusDays(200),
                new BigDecimal("25.00"), new BigDecimal("10.0000"), 3, "A1", null, null), f.e().owner().id(), "GRN").batchId());
        assertThat(inTenantTx(f.t(), () -> stock.pick(item, 3, null))).extracting(StockCommands.Pick::batchId).containsExactly(fresh);
        assertThatThrownBy(() -> inTenantTx(f.t(), () -> stock.pick(item, 4, null))).isInstanceOf(ApiException.class);
        String data = inTenantTx(f.t(), () -> jdbc.queryForObject("SELECT envelope->'data' FROM outbox_cloudevents WHERE envelope->>'type' = 'pharmacy.return.accepted.v1' " +
                "AND envelope->'data'->>'invoiceId' = ?", String.class, inv.get("id")));
        assertThat(data).contains("\"quarantined\": true");
    }

    // AC4: the day close shows the credit note and the refund; the leakage report lists the refund
    @Test
    void dayCloseTotalsShowTheCreditNoteAndTheRefund() {
        Fx f = fx();
        String[] x = item(f, "Vitamin C", Map.of());
        Map<?, ?> before = exchange("/v1/billing/day-close", HttpMethod.GET, authed(f.e().token()), Map.class).getBody();
        assertThat(((Map<?, ?>) before.get("creditNotes")).get("count")).isEqualTo(0);
        Map<?, ?> inv = sale(f, x[1], 2);
        ResponseEntity<Map> r = post(f.maker(), ret(inv, 0, 2, "sealed"));
        assertThat(approve(f.e().token(), r.getBody().get("refundId"), 45).getStatusCode()).isEqualTo(HttpStatus.OK); // 44.80 + 0.20 round-off back
        Map<?, ?> day = exchange("/v1/billing/day-close", HttpMethod.GET, authed(f.e().token()), Map.class).getBody();
        Map<?, ?> cn = (Map<?, ?>) day.get("creditNotes");
        assertThat(cn.get("count")).isEqualTo(1);
        assertThat(num(cn.get("taxable"))).isEqualTo(40.0);
        assertThat(num(cn.get("tax"))).isEqualTo(4.80);
        assertThat(num(cn.get("total"))).isEqualTo(45.0);
        Map<?, ?> cash = ((List<Map<?, ?>>) day.get("refunds")).stream().filter(m -> "cash".equals(m.get("method"))).findFirst().orElseThrow();
        assertThat(num(cash.get("amount"))).isEqualTo(45.0);
        assertThat(cash.get("transactions")).isEqualTo(1);
        Map<?, ?> leak = exchange("/v1/billing/leakage?type=refund", HttpMethod.GET, authed(f.e().token()), Map.class).getBody();
        assertThat((List<?>) leak.get("items")).hasSize(1);
        assertThat(((Number) ((Map<?, ?>) leak.get("totals")).get("refundMinor")).longValue()).isEqualTo(4500L);
    }

    // AC5: more than sold (minus what came back already) is 422 return-exceeds-sold; two returns at once cannot both pass
    @Test
    void returningMoreThanWasSoldIsRefusedAndConcurrentReturnsCannotBothPass() throws Exception {
        Fx f = fx();
        String[] x = item(f, "Paracetamol strip", Map.of());
        Map<?, ?> inv = sale(f, x[1], 2);
        ResponseEntity<Map> tooMany = post(f.maker(), ret(inv, 0, 3, "sealed"));
        assertThat(tooMany.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(type(tooMany)).endsWith("return-exceeds-sold");
        assertThat(tooMany.getBody().get("returnableQty")).isEqualTo(2);
        // the same line twice in one return adds up
        ResponseEntity<Map> twice = post(f.maker(), Map.of("invoiceId", inv.get("id"), "lines", List.of(
                Map.of("invoiceLineId", lineId(inv, 0), "qty", 2, "condition", "sealed"), Map.of("invoiceLineId", lineId(inv, 0), "qty", 1, "condition", "sealed"))));
        assertThat(type(twice)).endsWith("return-exceeds-sold");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<ResponseEntity<Map>> one = () -> { go.await(); return post(f.maker(), ret(inv, 0, 2, "sealed")); };
        Future<ResponseEntity<Map>> a = pool.submit(one);
        Future<ResponseEntity<Map>> b = pool.submit(one);
        go.countDown();
        List<HttpStatus> got = List.of(HttpStatus.valueOf(a.get().getStatusCode().value()), HttpStatus.valueOf(b.get().getStatusCode().value()));
        pool.shutdown();
        assertThat(got).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        ResponseEntity<Map> loser = a.get().getStatusCode() == HttpStatus.CONFLICT ? a.get() : b.get();
        assertThat(type(loser)).endsWith("approval-pending");
        Object refundId = (a.get().getStatusCode() == HttpStatus.CREATED ? a.get() : b.get()).getBody().get("refundId");
        assertThat(bill(f.maker(), inv.get("id")).getBody().get("pendingRefundId")).isEqualTo(refundId);

        // reject it, return 1 and approve, then 2 more is more than is left
        assertThat(exchange("/v1/billing/refund-requests/" + refundId + "/reject", HttpMethod.POST, authedJsonBody(f.e().token(), Map.of("reason", "test")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> first = post(f.maker(), ret(inv, 0, 1, "sealed"));
        assertThat(approve(f.e().token(), first.getBody().get("refundId"), 22.40).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> rest = post(f.maker(), ret(inv, 0, 2, "sealed"));
        assertThat(type(rest)).endsWith("return-exceeds-sold");
        assertThat(rest.getBody().get("returnableQty")).isEqualTo(1);
        assertThat(batchQty(x[2])).isEqualTo(9);
    }

    @Test
    void anExternalPharmacyHasNoCounterReturns() {
        Fx f = fx();
        String[] x = item(f, "Gauze", Map.of());
        Map<?, ?> inv = sale(f, x[1], 1);
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(f.e().token(), Map.of("mode", "external", "reason", "PHL-011 test")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> r = post(f.maker(), ret(inv, 0, 1, "sealed"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(r)).endsWith("pharmacy-mode-external");
        assertThat(bill(f.maker(), inv.get("id")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void refundGoesToTheOriginalMethodOnly() {
        Fx f = fx();
        String[] x = item(f, "Cough syrup", Map.of());
        Map<?, ?> inv = sale(f, x[1], 1);
        Map<String, Object> card = new HashMap<>(ret(inv, 0, 1, "sealed"));
        card.put("refundMethod", "card");
        ResponseEntity<Map> r = post(f.maker(), card);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(type(r)).endsWith("refund-method-not-original");
        card.put("refundMethod", "cash");
        assertThat(post(f.maker(), card).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void withoutBillingEditTheReturnIsForbiddenAndAnotherClinicSeesNothing() {
        Fx f = fx();
        String[] x = item(f, "Antacid", Map.of());
        Map<?, ?> inv = sale(f, x[1], 1);
        String viewer = tokenFor(f.e(), "Viewer", true, false, false, false, false);
        assertThat(post(viewer, ret(inv, 0, 1, "sealed")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bill(viewer, inv.get("id")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Fx other = fx();
        assertThat(post(other.maker(), ret(inv, 0, 1, "sealed")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(bill(other.maker(), inv.get("id")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/pharmacy/returns/bill?number=" + inv.get("invoiceNumber"), HttpMethod.GET, authed(other.maker()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // by printed number in the own clinic
        Map<?, ?> byNumber = exchange("/v1/pharmacy/returns/bill?number=" + inv.get("invoiceNumber"), HttpMethod.GET, authed(f.maker()), Map.class).getBody();
        assertThat(byNumber.get("invoiceId")).isEqualTo(inv.get("id"));
        // a bad body is 400
        assertThat(post(f.maker(), Map.of("invoiceId", inv.get("id"), "lines", List.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(f.maker(), ret(inv, 0, 1, "damaged")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

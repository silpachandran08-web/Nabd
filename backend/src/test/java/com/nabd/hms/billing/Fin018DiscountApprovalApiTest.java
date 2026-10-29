package com.nabd.hms.billing;

import com.nabd.hms.common.ModuleGrant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** FIN-018: owner threshold for discounts and below-list overrides, approval in app or by owner PIN, issue gate, leakage report. */
class Fin018DiscountApprovalApiTest extends S10QaBillingSupport {

    /** env().token() is the owner (built-in Owner role); desk is a receptionist with billing.discount and billing:approve. */
    private record Fx(Env env, String desk) {
    }

    private Fx fx() {
        Env e = env();
        UUID role = seedRole(e.tenant().id(), "Desk", false, new ModuleGrant("billing", true, true, true, false, true, true, false),
                fullGrant("queue"), fullGrant("patients"));
        String desk = loginAndGetAccessToken(seedStaff(e.tenant(), role, "desk" + UUID.randomUUID() + "@a.com", phone(), false));
        return new Fx(e, desk);
    }

    private void policy(Fx f, String key, String value) {
        ResponseEntity<Map> r = exchange("/v1/setup/policies/" + key, HttpMethod.PATCH, authedJsonBody(f.env().token(), Map.of("value", value)), Map.class);
        assertThat(r.getStatusCode()).as(String.valueOf(r.getBody())).isEqualTo(HttpStatus.OK);
    }

    private static Map<String, Object> pct(int value, String reason) {
        Map<String, Object> m = new HashMap<>(Map.of("kind", "percent", "value", value));
        if (reason != null) m.put("reasonCode", reason);
        return m;
    }

    private static Map<String, Object> override(String code, int price, String reason) {
        Map<String, Object> l = line(code, 1);
        l.put("override", Map.of("unitPrice", price, "reasonCode", reason));
        return l;
    }

    private ResponseEntity<Map> bill(String token, String entry, List<Map<String, Object>> lines, List<Map<String, Object>> discounts, String approvalId) {
        List<Object> extra = new ArrayList<>(List.of("discounts", discounts));
        if (approvalId != null) { extra.add("discountApprovalId"); extra.add(approvalId); }
        return checkout(token, entry, lines, extra.toArray());
    }

    private ResponseEntity<Map> ask(String token, String draft, List<Map<String, Object>> lines, List<Map<String, Object>> discounts) {
        return exchange("/v1/billing/discount-approvals", HttpMethod.POST, authedJsonBody(token, Map.of(
                "invoiceDraftId", draft, "lineItems", lines, "discounts", discounts)), Map.class);
    }

    private ResponseEntity<Map> decide(String token, Object id, Map<String, Object> body) {
        return exchange("/v1/billing/discount-approvals/" + id + "/decide", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private static String code(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("code"));
    }

    // AC1 + AC4: threshold matrix (percent strictly above, amount strictly above, below-list override, none set)
    @Test
    void thresholdMatrix() {
        Fx f = fx();
        List<Map<String, Object>> svc = List.of(line("SVC", 1)); // 500.00
        // no threshold: today's behaviour, a 15% discount applies directly
        assertThat(bill(f.desk(), atCheckout(f.env()), svc, List.of(pct(15, "goodwill")), null).getStatusCode()).isEqualTo(HttpStatus.OK);

        policy(f, "discount_threshold_percent", "10");
        ResponseEntity<Map> above = bill(f.desk(), atCheckout(f.env()), svc, List.of(pct(15, "goodwill")), null);
        assertThat(above.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(above)).isEqualTo("APPROVAL_REQUIRED");
        assertThat(((Number) above.getBody().get("amountMinor")).longValue()).isEqualTo(7500L);
        assertThat(bill(f.desk(), atCheckout(f.env()), svc, List.of(pct(10, "goodwill")), null).getStatusCode()).as("equal is not above").isEqualTo(HttpStatus.OK);
        // a below-list override counts: 500 -> 400 is 20%
        assertThat(code(checkout(f.desk(), atCheckout(f.env()), List.of(override("SVC", 400, "price_match"))))).isEqualTo("APPROVAL_REQUIRED");

        // AC4: below the threshold it applies directly, but only with a coded reason
        ResponseEntity<Map> noReason = bill(f.desk(), atCheckout(f.env()), svc, List.of(pct(5, null)), null);
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(noReason)).isEqualTo("REASON_REQUIRED");
        assertThat(code(bill(f.desk(), atCheckout(f.env()), svc, List.of(pct(5, "made_up")), null))).isEqualTo("REASON_INVALID");
        ResponseEntity<Map> direct = bill(f.desk(), atCheckout(f.env()), svc, List.of(pct(5, "staff")), null);
        assertThat(direct.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count(f.env(), "SELECT count(*) FROM invoice_discounts WHERE invoice_id = ?::uuid AND approval_id IS NULL AND reason_code = 'staff'",
                direct.getBody().get("id"))).isEqualTo(1);

        // amount threshold (minor units), percent off
        policy(f, "discount_threshold_percent", "off");
        policy(f, "discount_threshold_amount", "5000"); // 50.00
        assertThat(code(bill(f.desk(), atCheckout(f.env()), svc, List.of(Map.of("kind", "amount", "value", 60, "reasonCode", "waiver")), null)))
                .isEqualTo("APPROVAL_REQUIRED");
        assertThat(bill(f.desk(), atCheckout(f.env()), svc, List.of(Map.of("kind", "amount", "value", 50, "reasonCode", "waiver")), null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        // bad policy values are refused
        assertThat(exchange("/v1/setup/policies/discount_threshold_percent", HttpMethod.PATCH, authedJsonBody(f.env().token(), Map.of("value", "101")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // the counter sale is gated too
        inTenantTx(f.env().tenant().id(), () -> jdbc.update("INSERT INTO pharmacy_settings (tenant_id, mode) VALUES (?, 'hybrid')", f.env().tenant().id()));
        assertThat(code(exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(f.desk(), Map.of("lineItems", svc,
                "discounts", List.of(Map.of("kind", "amount", "value", 60, "reasonCode", "waiver")))), Map.class))).isEqualTo("APPROVAL_REQUIRED");
    }

    // AC1 + AC2: awaiting approval cannot be issued; the owner approves in app (once); the bill is issued with the approval
    @Test
    void inAppApprovalUnlocksTheBillOnceAndIsRaceSafe() throws Exception {
        Fx f = fx();
        policy(f, "discount_threshold_percent", "10");
        String entry = atCheckout(f.env());
        List<Map<String, Object>> svc = List.of(line("SVC", 1));
        List<Map<String, Object>> disc = List.of(pct(15, "goodwill"));

        assertThat(code(ask(f.desk(), entry, svc, List.of(pct(5, "goodwill"))))).isEqualTo("APPROVAL_NOT_NEEDED");
        assertThat(code(ask(f.desk(), entry, svc, List.of(pct(15, null))))).isEqualTo("REASON_REQUIRED");
        ResponseEntity<Map> req = ask(f.desk(), entry, svc, disc);
        assertThat(req.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Object id = req.getBody().get("approvalId");
        assertThat(req.getBody()).containsEntry("status", "pending").containsEntry("billStatus", "awaiting_approval").containsEntry("reasonCode", "goodwill");
        assertThat(((Number) req.getBody().get("amountMinor")).longValue()).isEqualTo(7500L);
        assertThat(num(req.getBody().get("percent"))).isEqualTo(15.0);
        String ev = inTenantTx(f.env().tenant().id(), () -> jdbc.queryForObject(
                "SELECT envelope->'data' FROM outbox_cloudevents WHERE envelope->>'type' = 'billing.discount.approval_requested.v1' AND envelope->'data'->>'approvalId' = ?",
                String.class, String.valueOf(id)));
        assertThat(ev).contains("7500").contains("15");

        // cannot be issued while waiting
        assertThat(code(bill(f.desk(), entry, svc, disc, String.valueOf(id)))).isEqualTo("AWAITING_APPROVAL");
        // the desk list for the draft shows it
        Map<?, ?> listed = exchange("/v1/billing/discount-approvals?invoiceDraftId=" + entry, HttpMethod.GET, authed(f.desk()), Map.class).getBody();
        assertThat((List<?>) listed.get("items")).hasSize(1);
        // the requester is not an owner: no approval, even with billing:approve
        ResponseEntity<Map> notOwner = decide(f.desk(), id, Map.of("decision", "approve", "method", "in_app"));
        assertThat(notOwner.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(notOwner)).isEqualTo("OWNER_REQUIRED");

        // two decisions at once: exactly one wins, the other is 409 ALREADY_DECIDED
        CompletableFuture<ResponseEntity<Map>> a = CompletableFuture.supplyAsync(() -> decide(f.env().token(), id, Map.of("decision", "approve", "method", "in_app")));
        CompletableFuture<ResponseEntity<Map>> b = CompletableFuture.supplyAsync(() -> decide(f.env().token(), id, Map.of("decision", "approve", "method", "in_app")));
        List<Integer> codes = List.of(a.get().getStatusCode().value(), b.get().getStatusCode().value());
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        ResponseEntity<Map> loser = codes.get(0) == 409 ? a.get() : b.get();
        assertThat(code(loser)).isEqualTo("ALREADY_DECIDED");
        ResponseEntity<Map> winner = codes.get(0) == 200 ? a.get() : b.get();
        assertThat(winner.getBody()).containsEntry("method", "in_app").containsEntry("approverId", f.env().owner().id().toString());
        assertThat(count(f.env(), "SELECT count(*) FROM outbox_cloudevents WHERE envelope->>'type' = 'billing.discount.approval_decided.v1' "
                + "AND envelope->'data'->>'decision' = 'approved'")).isEqualTo(1);

        ResponseEntity<Map> issued = bill(f.desk(), entry, svc, disc, String.valueOf(id));
        assertThat(issued.getStatusCode()).as(String.valueOf(issued.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(count(f.env(), "SELECT count(*) FROM invoice_discounts WHERE invoice_id = ?::uuid AND approval_id = ?::uuid",
                issued.getBody().get("id"), id)).isEqualTo(1);
        Map<?, ?> after = (Map<?, ?>) ((List<?>) exchange("/v1/billing/discount-approvals?invoiceDraftId=" + entry, HttpMethod.GET, authed(f.desk()), Map.class)
                .getBody().get("items")).get(0);
        assertThat(after.get("billStatus")).isEqualTo("issued");
        // an approval is used once, and only on its own bill
        assertThat(code(bill(f.desk(), atCheckout(f.env()), svc, disc, String.valueOf(id)))).isEqualTo("APPROVAL_NOT_USABLE");
    }

    @Test
    void rejectedCannotBeIssuedAndALargerDiscountNeedsANewApproval() {
        Fx f = fx();
        policy(f, "discount_threshold_percent", "10");
        String entry = atCheckout(f.env());
        List<Map<String, Object>> svc = List.of(line("SVC", 1));
        Object rejected = ask(f.desk(), entry, svc, List.of(pct(15, "goodwill"))).getBody().get("approvalId");
        assertThat(decide(f.env().token(), rejected, Map.of("decision", "reject", "method", "in_app")).getBody()).containsEntry("status", "rejected");
        assertThat(code(bill(f.desk(), entry, svc, List.of(pct(15, "goodwill")), String.valueOf(rejected)))).isEqualTo("APPROVAL_NOT_USABLE");

        Object ok = ask(f.desk(), entry, svc, List.of(pct(15, "goodwill"))).getBody().get("approvalId");
        decide(f.env().token(), ok, Map.of("decision", "approve", "method", "in_app"));
        assertThat(code(bill(f.desk(), entry, svc, List.of(pct(20, "goodwill")), String.valueOf(ok)))).isEqualTo("APPROVAL_NOT_USABLE");
        // pay later on a closed request is refused; the inbox filter works
        assertThat(exchange("/v1/billing/discount-approvals/" + rejected + "/pay-later", HttpMethod.POST, authed(f.desk()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        Map<?, ?> inbox = exchange("/v1/billing/discount-approvals?status=rejected", HttpMethod.GET, authed(f.env().token()), Map.class).getBody();
        assertThat((List<?>) inbox.get("items")).hasSize(1);
    }

    // AC3: the owner's PIN at the desk -> method 'pin', the owner (not the desk user) is the approver; the assertion is session bound
    @Test
    void ownerPinAtTheDeskApprovesWithMethodPin() {
        Fx f = fx();
        policy(f, "discount_threshold_percent", "10");
        String entry = atCheckout(f.env());
        List<Map<String, Object>> svc = List.of(line("SVC", 1));
        Object id = ask(f.desk(), entry, svc, List.of(pct(15, "goodwill"))).getBody().get("approvalId");
        assertThat(exchange("/v1/billing/discount-approvals/" + id + "/pay-later", HttpMethod.POST, authed(f.desk()), Map.class).getBody())
                .containsEntry("payLater", true).containsEntry("billStatus", "awaiting_approval");

        ResponseEntity<Map> missing = decide(f.desk(), id, Map.of("decision", "approve", "method", "pin"));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(missing)).isEqualTo("STEP_UP_REQUIRED");
        ResponseEntity<Map> wrong = exchange("/v1/auth/pin-assertion", HttpMethod.POST, authedJsonBody(f.desk(), Map.of("pin", "9876")), Map.class);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(wrong)).isEqualTo("PIN_INVALID");

        ResponseEntity<Map> pin = exchange("/v1/auth/pin-assertion", HttpMethod.POST, authedJsonBody(f.desk(), Map.of("pin", STAFF_PIN)), Map.class);
        assertThat(pin.getStatusCode()).isEqualTo(HttpStatus.OK);
        String assertion = (String) pin.getBody().get("stepUpToken");
        // bound to the desk session: the owner's own session cannot replay it
        assertThat(decide(f.env().token(), id, Map.of("decision", "approve", "method", "pin", "pinAssertion", assertion)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<Map> ok = decide(f.desk(), id, Map.of("decision", "approve", "method", "pin", "pinAssertion", assertion));
        assertThat(ok.getStatusCode()).as(String.valueOf(ok.getBody())).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("status", "approved").containsEntry("method", "pin")
                .containsEntry("approverId", f.env().owner().id().toString()).containsEntry("billStatus", "approved");
        assertThat(count(f.env(), "SELECT count(*) FROM audit_log WHERE action = 'billing.discount_approval_decided' AND actor_id = ?", f.env().owner().id()))
                .isEqualTo(1);
        assertThat(bill(f.desk(), entry, svc, List.of(pct(15, "goodwill")), String.valueOf(id)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anotherClinicCannotSeeOrDecide() {
        Fx a = fx();
        Fx b = fx();
        policy(a, "discount_threshold_percent", "10");
        Object id = ask(a.desk(), atCheckout(a.env()), List.of(line("SVC", 1)), List.of(pct(15, "goodwill"))).getBody().get("approvalId");
        assertThat(decide(b.env().token(), id, Map.of("decision", "approve", "method", "in_app")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat((List<?>) exchange("/v1/billing/discount-approvals", HttpMethod.GET, authed(b.env().token()), Map.class).getBody().get("items")).isEmpty();
    }

    // AC5: discounts (direct and owner-approved), below-list overrides, write-offs and refunds, each with reason and approver, plus totals
    @Test
    void leakageListsEveryItemWithReasonAndApproverAndTotals() {
        Fx f = fx();
        policy(f, "discount_threshold_percent", "10");
        String owner = f.env().token();
        // direct discount 5% of 500 = 25.00, unpaid -> written off 100.00 later
        String direct = (String) bill(f.desk(), atCheckout(f.env()), List.of(line("SVC", 1)), List.of(pct(5, "staff")), null).getBody().get("id");
        // owner-approved 15% = 75.00
        String entry = atCheckout(f.env());
        Object id = ask(f.desk(), entry, List.of(line("SVC", 1)), List.of(pct(15, "goodwill"))).getBody().get("approvalId");
        decide(owner, id, Map.of("decision", "approve", "method", "in_app"));
        assertThat(bill(f.desk(), entry, List.of(line("SVC", 1)), List.of(pct(15, "goodwill")), String.valueOf(id)).getStatusCode()).isEqualTo(HttpStatus.OK);
        // below-list override 1200 -> 1150 = 50.00 (4%, below the threshold), paid then refunded 100.00
        ResponseEntity<Map> ov = checkout(f.desk(), atCheckout(f.env()), List.of(override("XRAY", 1150, "price_match")));
        String ovInvoice = (String) ov.getBody().get("id");
        assertThat(pay(owner, ovInvoice, "cash", ov.getBody().get("total")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String paymentId = (String) ((Map<?, ?>) ((List<?>) getInvoice(owner, ovInvoice).getBody().get("payments")).get(0)).get("id");
        Object refund = exchange("/v1/billing/invoices/" + ovInvoice + "/refund-requests", HttpMethod.POST, authedJsonBody(f.desk(), Map.of(
                "amount", 100, "reasonCode", "billing_error", "refundTo", List.of(Map.of("paymentId", paymentId, "amount", 100)))), Map.class).getBody().get("id");
        assertThat(exchange("/v1/billing/refund-requests/" + refund + "/approve", HttpMethod.POST, authedJsonBody(owner, Map.of("expectedAmount", 100)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/billing/invoices/" + direct + "/write-off", HttpMethod.POST, authedJsonBody(owner, Map.of("amount", 100, "reasonCode", "hardship")),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> r = exchange("/v1/billing/leakage", HttpMethod.GET, authed(owner), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.getBody().get("items");
        assertThat(items).extracting(i -> i.get("type")).containsExactlyInAnyOrder("discount", "discount", "override", "refund", "write_off");
        assertThat(items).allSatisfy(i -> assertThat(i.get("reasonCode")).isNotNull()).allSatisfy(i -> assertThat(i.get("approverName")).isNotNull());
        Map<String, Object> approved = items.stream().filter(i -> "goodwill".equals(i.get("reasonCode"))).findFirst().orElseThrow();
        assertThat(approved).containsEntry("approvalMethod", "in_app").containsEntry("approverId", f.env().owner().id().toString());
        assertThat(((Number) approved.get("amountMinor")).longValue()).isEqualTo(7500L);
        assertThat(items.stream().filter(i -> "staff".equals(i.get("reasonCode"))).findFirst().orElseThrow()).containsEntry("approvalMethod", "direct");
        Map<?, ?> totals = (Map<?, ?>) r.getBody().get("totals");
        assertThat(((Number) totals.get("discountMinor")).longValue()).isEqualTo(10000L);
        assertThat(((Number) totals.get("overrideMinor")).longValue()).isEqualTo(5000L);
        assertThat(((Number) totals.get("writeOffMinor")).longValue()).isEqualTo(10000L);
        assertThat(((Number) totals.get("refundMinor")).longValue()).isEqualTo(10000L);
        assertThat(((Number) totals.get("totalMinor")).longValue()).isEqualTo(35000L);
        assertThat(((Number) totals.get("ownerApprovedMinor")).longValue()).isEqualTo(7500L);
        assertThat(((Number) totals.get("withoutOwnerApprovalMinor")).longValue()).isEqualTo(27500L);
        assertThat((List<?>) exchange("/v1/billing/leakage?type=refund", HttpMethod.GET, authed(owner), Map.class).getBody().get("items")).hasSize(1);
        assertThat(exchange("/v1/billing/leakage?type=bogus", HttpMethod.GET, authed(owner), Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // reports:view without the Owner role is not enough
        UUID mgr = seedRole(f.env().tenant().id(), "Manager", false, fullGrant("reports"), fullGrant("billing"));
        String manager = loginAndGetAccessToken(seedStaff(f.env().tenant(), mgr, "mgr" + UUID.randomUUID() + "@a.com", phone(), false));
        ResponseEntity<Map> denied = exchange("/v1/billing/leakage", HttpMethod.GET, authed(manager), Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(denied)).isEqualTo("OWNER_REQUIRED");
    }
}

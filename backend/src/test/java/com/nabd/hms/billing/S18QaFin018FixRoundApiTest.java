package com.nabd.hms.billing;

import com.nabd.hms.common.ModuleGrant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** S18 QA retest after 66a9665 (FIN-018 fix round): one-use owner PIN assertions, binding to an approval, desk-only lockout, approval bound to its bill. */
@SuppressWarnings({"unchecked", "rawtypes"})
class S18QaFin018FixRoundApiTest extends S10QaBillingSupport {

    private record Fx(Env env, String desk) {
        String owner() {
            return env.token();
        }
    }

    private Fx fx() {
        Env e = env();
        catalogue(e.tenant(), "SV2", "Service", 500, null, 0); // same list price as SVC, different charge
        UUID role = seedRole(e.tenant().id(), "Desk", false, new ModuleGrant("billing", true, true, true, false, true, true, false),
                fullGrant("queue"), fullGrant("patients"));
        return new Fx(e, loginAndGetAccessToken(seedStaff(e.tenant(), role, "fdesk" + UUID.randomUUID() + "@a.com", phone(), false)));
    }

    private String roleToken(Fx f, String name, ModuleGrant... grants) {
        UUID role = seedRole(f.env().tenant().id(), name, false, grants);
        return loginAndGetAccessToken(seedStaff(f.env().tenant(), role, "f" + name.replace(" ", "") + UUID.randomUUID() + "@a.com", phone(), false));
    }

    private void percent(Fx f, String v) {
        assertThat(exchange("/v1/setup/policies/discount_threshold_percent", HttpMethod.PATCH, authedJsonBody(f.owner(), Map.of("value", v)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static Map<String, Object> pct(Number v) {
        return new HashMap<>(Map.of("kind", "percent", "value", v, "reasonCode", "goodwill"));
    }

    private Object ask(String token, String draft, List<Map<String, Object>> lines, Map<String, Object> discount) {
        ResponseEntity<Map> r = exchange("/v1/billing/discount-approvals", HttpMethod.POST, authedJsonBody(token, Map.of(
                "invoiceDraftId", draft, "lineItems", lines, "discounts", List.of(discount))), Map.class);
        assertThat(r.getStatusCode()).as("ask %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("approvalId");
    }

    private ResponseEntity<Map> pin(String token, String pinValue, Object approvalId) {
        Map<String, Object> body = new HashMap<>(Map.of("pin", pinValue));
        if (approvalId != null) body.put("approvalId", String.valueOf(approvalId));
        return exchange("/v1/auth/pin-assertion", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private String assertion(String token, Object approvalId) {
        ResponseEntity<Map> r = pin(token, STAFF_PIN, approvalId);
        assertThat(r.getStatusCode()).as("pin %s", r.getBody()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("stepUpToken");
    }

    private ResponseEntity<Map> decidePin(String token, Object id, String a) {
        return exchange("/v1/billing/discount-approvals/" + id + "/decide", HttpMethod.POST, authedJsonBody(token,
                Map.of("decision", "approve", "method", "pin", "pinAssertion", a)), Map.class);
    }

    private void approveInApp(Fx f, Object id) {
        assertThat(exchange("/v1/billing/discount-approvals/" + id + "/decide", HttpMethod.POST, authedJsonBody(f.owner(),
                Map.of("decision", "approve", "method", "in_app")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map> bill(String token, String entry, List<Map<String, Object>> lines, Map<String, Object> d, Object approvalId) {
        List<Object> extra = new ArrayList<>(List.of("discounts", List.of(d)));
        if (approvalId != null) { extra.add("discountApprovalId"); extra.add(String.valueOf(approvalId)); }
        return checkout(token, entry, lines, extra.toArray());
    }

    private static String code(ResponseEntity<Map> r) {
        return r.getBody() == null ? "null" : String.valueOf(r.getBody().get("code"));
    }

    @Test
    void anUnboundAssertionIsUsedOnceEvenWhenTwoDecisionsRace() throws Exception {
        Fx f = fx();
        percent(f, "10");
        Object a = ask(f.desk(), atCheckout(f.env()), List.of(line("SVC", 1)), pct(15));
        Object b = ask(f.desk(), atCheckout(f.env()), List.of(line("SVC", 1)), pct(40));
        String token = assertion(f.desk(), null);
        CompletableFuture<ResponseEntity<Map>> x = CompletableFuture.supplyAsync(() -> decidePin(f.desk(), a, token));
        CompletableFuture<ResponseEntity<Map>> y = CompletableFuture.supplyAsync(() -> decidePin(f.desk(), b, token));
        List<ResponseEntity<Map>> rs = List.of(x.get(), y.get());
        assertThat(rs.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count()).as("%s", rs.stream().map(ResponseEntity::getBody).toList()).isEqualTo(1);
        ResponseEntity<Map> loser = rs.stream().filter(r -> r.getStatusCode() != HttpStatus.OK).findFirst().orElseThrow();
        assertThat(loser.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(loser)).isEqualTo("PIN_ASSERTION_USED");
        assertThat(count(f.env(), "SELECT count(*) FROM discount_approvals WHERE status = 'approved' AND method = 'pin'")).isEqualTo(1);
        // and a third replay, serially
        Object c = ask(f.desk(), atCheckout(f.env()), List.of(line("SVC", 1)), pct(20));
        assertThat(code(decidePin(f.desk(), c, token))).isEqualTo("PIN_ASSERTION_USED");
    }

    @Test
    void anAssertionBoundToOneApprovalIsRefusedOnAnother() {
        Fx f = fx();
        percent(f, "10");
        Object a = ask(f.desk(), atCheckout(f.env()), List.of(line("SVC", 1)), pct(15));
        Object b = ask(f.desk(), atCheckout(f.env()), List.of(line("SVC", 1)), pct(40));
        String forA = assertion(f.desk(), a);
        ResponseEntity<Map> wrong = decidePin(f.desk(), b, forA);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(wrong)).isEqualTo("PIN_ASSERTION_MISMATCH");
        assertThat(count(f.env(), "SELECT count(*) FROM discount_approvals WHERE id = ?::uuid AND status = 'pending'", String.valueOf(b))).isEqualTo(1);
        ResponseEntity<Map> ok = decidePin(f.desk(), a, forA);
        assertThat(ok.getStatusCode()).as("the mismatch did not burn it: %s", ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("method", "pin").containsEntry("approverId", f.env().owner().id().toString());
        assertThat(code(decidePin(f.desk(), a, forA))).isIn("PIN_ASSERTION_USED", "ALREADY_DECIDED");
    }

    @Test
    void onlyADeskThatBillsMayTryOwnerPins() {
        Fx f = fx();
        String nurse = roleToken(f, "Nurse R", fullGrant("queue"), fullGrant("patients"), fullGrant("nursing"));
        String viewer = roleToken(f, "Bill viewer", new ModuleGrant("billing", true, false, false, false, false, false, false), fullGrant("queue"));
        for (String t : List.of(nurse, viewer)) {
            ResponseEntity<Map> r = pin(t, STAFF_PIN, null);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(r.getBody() == null || !r.getBody().containsKey("stepUpToken")).isTrue();
        }
        // billing:create without billing:approve may get an assertion but cannot decide with it
        String cashier = roleToken(f, "Cashier R", new ModuleGrant("billing", true, true, false, false, false, true, false), fullGrant("queue"), fullGrant("patients"));
        percent(f, "10");
        Object id = ask(f.desk(), atCheckout(f.env()), List.of(line("SVC", 1)), pct(15));
        String tok = assertion(cashier, id);
        assertThat(decidePin(cashier, id, tok).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void wrongPinsLockTheDeskCallerNotTheOwner() {
        Fx f = fx();
        for (int i = 0; i < 5; i++) {
            assertThat(code(pin(f.desk(), "000" + i, null))).isEqualTo("PIN_INVALID");
        }
        assertThat(pin(f.desk(), STAFF_PIN, null).getStatusCode()).as("the guessing desk is locked").isEqualTo(HttpStatus.LOCKED);
        // the owner can still sign in and has no failed attempts recorded
        assertThat(loginAndGetAccessToken(f.env().owner())).isNotBlank();
        assertThat(new JdbcTemplate(superuserDataSource()).queryForObject("SELECT count(*) FROM login_attempts WHERE staff_id = ? AND NOT succeeded",
                Integer.class, f.env().owner().id())).isZero();
        // another desk user is not affected
        String desk2 = roleToken(f, "Desk two", new ModuleGrant("billing", true, true, true, false, true, true, false), fullGrant("queue"), fullGrant("patients"));
        assertThat(pin(desk2, STAFF_PIN, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anApprovalDoesNotCoverAChangedVisitCart() {
        Fx f = fx();
        percent(f, "10");
        String e = atCheckout(f.env());
        Object id = ask(f.desk(), e, List.of(line("SVC", 2)), pct(15)); // 150.00 of 1000.00
        approveInApp(f, id);
        assertThat(bill(f.desk(), e, List.of(line("SV2", 2)), pct(15), id).getStatusCode()).as("other charge, same gross").isEqualTo(HttpStatus.CONFLICT);
        assertThat(bill(f.desk(), e, List.of(line("SVC", 1)), pct(15), id).getStatusCode()).as("quantity changed").isEqualTo(HttpStatus.CONFLICT);
        assertThat(bill(f.desk(), e, List.of(line("SVC", 2)), pct(16), id).getStatusCode()).as("larger percent").isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<Map> smaller = bill(f.desk(), e, List.of(line("SVC", 2)), pct(12), id);
        assertThat(smaller.getStatusCode()).as("same bill, smaller discount: %s", smaller.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aCounterApprovalDoesNotCoverAChangedCounterCart() {
        Fx f = fx();
        inTenantTx(f.env().tenant().id(), () -> jdbc.update("INSERT INTO pharmacy_settings (tenant_id, mode) VALUES (?, 'hybrid')", f.env().tenant().id()));
        percent(f, "10");
        String draft = UUID.randomUUID().toString();
        Object id = ask(f.desk(), draft, List.of(line("SVC", 1)), pct(15));
        approveInApp(f, id);
        Map<String, Object> other = new HashMap<>(Map.of("lineItems", List.of(line("SV2", 1)), "discounts", List.of(pct(15)), "discountApprovalId", String.valueOf(id)));
        ResponseEntity<Map> r = exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(f.desk(), other), Map.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> same = new HashMap<>(Map.of("lineItems", List.of(line("SVC", 1)), "discounts", List.of(pct(15)), "discountApprovalId", String.valueOf(id)));
        assertThat(exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(f.desk(), same), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}

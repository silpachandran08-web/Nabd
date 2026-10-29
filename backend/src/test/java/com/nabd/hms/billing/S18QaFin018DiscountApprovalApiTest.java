package com.nabd.hms.billing;

import com.nabd.hms.common.ModuleGrant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S18 independent QA for FIN-018 (real API, real Postgres): threshold boundaries, approval reuse, approver rules, PIN step-up,
 * race safety, tenant isolation, permissions, leakage. Case IDs refer to reports/s18-qa-testcases.md.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S18QaFin018DiscountApprovalApiTest extends S10QaBillingSupport {

    private record Fx(Env env, SeededStaff deskStaff, String desk) {
        String owner() {
            return env.token();
        }
    }

    private Fx fx() {
        Env e = env();
        UUID role = seedRole(e.tenant().id(), "Desk", false, new ModuleGrant("billing", true, true, true, false, true, true, false),
                fullGrant("queue"), fullGrant("patients"));
        SeededStaff s = seedStaff(e.tenant(), role, "qdesk" + UUID.randomUUID() + "@a.com", phone(), false);
        return new Fx(e, s, loginAndGetAccessToken(s));
    }

    private String roleToken(Fx f, String name, ModuleGrant... grants) {
        UUID role = seedRole(f.env().tenant().id(), name, false, grants);
        return loginAndGetAccessToken(seedStaff(f.env().tenant(), role, "q" + name.replace(" ", "") + UUID.randomUUID() + "@a.com", phone(), false));
    }

    private JdbcTemplate su() {
        return new JdbcTemplate(superuserDataSource());
    }

    private ResponseEntity<Map> policy(Fx f, String key, String value) {
        return exchange("/v1/setup/policies/" + key, HttpMethod.PATCH, authedJsonBody(f.owner(), Map.of("value", value)), Map.class);
    }

    private void setPolicy(Fx f, String key, String value) {
        assertThat(policy(f, key, value).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private static Map<String, Object> pct(Number v, String reason) {
        Map<String, Object> m = new HashMap<>(Map.of("kind", "percent", "value", v));
        if (reason != null) m.put("reasonCode", reason);
        return m;
    }

    private static Map<String, Object> amt(Number v, String reason) {
        Map<String, Object> m = new HashMap<>(Map.of("kind", "amount", "value", v));
        if (reason != null) m.put("reasonCode", reason);
        return m;
    }

    private ResponseEntity<Map> bill(String token, String entry, List<Map<String, Object>> lines, List<Map<String, Object>> discounts, Object approvalId) {
        List<Object> extra = new ArrayList<>(List.of("discounts", discounts));
        if (approvalId != null) { extra.add("discountApprovalId"); extra.add(String.valueOf(approvalId)); }
        return checkout(token, entry, lines, extra.toArray());
    }

    private ResponseEntity<Map> otc(String token, List<Map<String, Object>> lines, List<Map<String, Object>> discounts, Object approvalId) {
        Map<String, Object> body = new HashMap<>(Map.of("lineItems", lines, "discounts", discounts));
        if (approvalId != null) body.put("discountApprovalId", String.valueOf(approvalId));
        return exchange("/v1/billing/otc-checkout", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private ResponseEntity<Map> ask(String token, String draft, List<Map<String, Object>> lines, List<Map<String, Object>> discounts) {
        return exchange("/v1/billing/discount-approvals", HttpMethod.POST, authedJsonBody(token, Map.of(
                "invoiceDraftId", draft, "lineItems", lines, "discounts", discounts)), Map.class);
    }

    private Object askOk(String token, String draft, List<Map<String, Object>> lines, List<Map<String, Object>> discounts) {
        ResponseEntity<Map> r = ask(token, draft, lines, discounts);
        assertThat(r.getStatusCode()).as("ask %s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("approvalId");
    }

    private ResponseEntity<Map> decide(String token, Object id, String decision, String method, String assertion) {
        Map<String, Object> body = new HashMap<>(Map.of("decision", decision, "method", method));
        if (assertion != null) body.put("pinAssertion", assertion);
        return exchange("/v1/billing/discount-approvals/" + id + "/decide", HttpMethod.POST, authedJsonBody(token, body), Map.class);
    }

    private ResponseEntity<Map> pin(String token, String pin) {
        return exchange("/v1/auth/pin-assertion", HttpMethod.POST, authedJsonBody(token, Map.of("pin", pin)), Map.class);
    }

    private static String code(ResponseEntity<Map> r) {
        return r.getBody() == null ? "null" : String.valueOf(r.getBody().get("code"));
    }

    private int invoicesFor(Fx f, String entry) {
        return count(f.env(), "SELECT count(*) FROM invoices WHERE queue_entry_id = ?::uuid", entry);
    }

    private static final List<Map<String, Object>> SVC = List.of(line("SVC", 1));

    // ---------------- thresholds ----------------

    @Test
    void fApi01to05_thresholdBoundariesAmountPercentBothAndNone() {
        Fx f = fx();
        // F-API-05: no threshold row: 15% applies directly
        assertThat(bill(f.desk(), atCheckout(f.env()), SVC, List.of(pct(15, "goodwill")), null).getStatusCode()).as("no threshold").isEqualTo(HttpStatus.OK);

        setPolicy(f, "discount_threshold_percent", "10");
        // F-API-01
        String e1 = atCheckout(f.env());
        ResponseEntity<Map> above = bill(f.desk(), e1, SVC, List.of(pct(15, "goodwill")), null);
        assertThat(above.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(above)).isEqualTo("APPROVAL_REQUIRED");
        assertThat(((Number) above.getBody().get("amountMinor")).longValue()).isEqualTo(7500L);
        assertThat(num(above.getBody().get("percent"))).isEqualTo(15.0);
        assertThat(invoicesFor(f, e1)).as("nothing issued while above the threshold").isZero();
        // F-API-02: exactly 10% passes, 10.01% does not
        assertThat(bill(f.desk(), atCheckout(f.env()), SVC, List.of(amt(50, "goodwill")), null).getStatusCode()).as("50.00 = 10.00%").isEqualTo(HttpStatus.OK);
        assertThat(code(bill(f.desk(), atCheckout(f.env()), SVC, List.of(amt(50.05, "goodwill")), null))).as("50.05 = 10.01%").isEqualTo("APPROVAL_REQUIRED");

        // F-API-03: amount only
        setPolicy(f, "discount_threshold_percent", "off");
        setPolicy(f, "discount_threshold_amount", "5000");
        assertThat(bill(f.desk(), atCheckout(f.env()), SVC, List.of(amt(50, "waiver")), null).getStatusCode()).as("amount = threshold").isEqualTo(HttpStatus.OK);
        assertThat(code(bill(f.desk(), atCheckout(f.env()), SVC, List.of(amt(50.01, "waiver")), null))).as("one paisa above").isEqualTo("APPROVAL_REQUIRED");

        // F-API-04: both set, either triggers
        setPolicy(f, "discount_threshold_amount", "10000");
        setPolicy(f, "discount_threshold_percent", "10");
        assertThat(code(bill(f.desk(), atCheckout(f.env()), SVC, List.of(amt(60, "waiver")), null))).as("12%% under amount").isEqualTo("APPROVAL_REQUIRED");
        assertThat(code(bill(f.desk(), atCheckout(f.env()), List.of(line("XRAY", 1)), List.of(amt(110, "waiver")), null))).as("9.2%% over amount")
                .isEqualTo("APPROVAL_REQUIRED");
        assertThat(bill(f.desk(), atCheckout(f.env()), List.of(line("XRAY", 1)), List.of(amt(50, "waiver")), null).getStatusCode()).isEqualTo(HttpStatus.OK);

        // F-API-05: "off" restores the old behaviour
        setPolicy(f, "discount_threshold_amount", "off");
        setPolicy(f, "discount_threshold_percent", "off");
        assertThat(bill(f.desk(), atCheckout(f.env()), SVC, List.of(pct(15, "goodwill")), null).getStatusCode()).as("off").isEqualTo(HttpStatus.OK);
    }

    @Test
    void fApi06_thresholdPolicyValidation() {
        Fx f = fx();
        for (String bad : List.of("abc", "-1", "100.001", "101", "10.555")) {
            assertThat(policy(f, "discount_threshold_percent", bad).getStatusCode()).as("percent %s", bad).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        for (String bad : List.of("1.5", "-5", "abc")) {
            assertThat(policy(f, "discount_threshold_amount", bad).getStatusCode()).as("amount %s", bad).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        for (String ok : List.of("100", "0", "12.5", "off")) {
            assertThat(policy(f, "discount_threshold_percent", ok).getStatusCode()).as("percent %s", ok).isEqualTo(HttpStatus.OK);
        }
        assertThat(policy(f, "discount_threshold_amount", "0").getStatusCode()).isEqualTo(HttpStatus.OK);
        // desk (no setup:edit) cannot change the owner's threshold
        assertThat(exchange("/v1/setup/policies/discount_threshold_percent", HttpMethod.PATCH, authedJsonBody(f.desk(), Map.of("value", "90")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------- AC4 reasons ----------------

    @Test
    void fApi07_everyPriceChangeNeedsACodedReason() {
        Fx f = fx();
        setPolicy(f, "discount_threshold_percent", "10");
        assertThat(code(bill(f.desk(), atCheckout(f.env()), SVC, List.of(pct(5, null)), null))).isEqualTo("REASON_REQUIRED");
        assertThat(code(bill(f.desk(), atCheckout(f.env()), SVC, List.of(pct(5, "made_up")), null))).isEqualTo("REASON_INVALID");
        Map<String, Object> ov = line("SVC", 1);
        ov.put("override", Map.of("unitPrice", 480));
        assertThat(code(checkout(f.desk(), atCheckout(f.env()), List.of(ov)))).as("override without reason").isEqualTo("REASON_REQUIRED");
        Map<String, Object> free = new HashMap<>(Map.of("chargeCode", "MISC1", "chargeName", "Misc", "quantity", 1, "unitPrice", 100));
        assertThat(code(checkout(f.desk(), atCheckout(f.env()), List.of(free)))).as("free-text charge without reason").isEqualTo("REASON_REQUIRED");
        assertThat(code(checkout(f.desk(), atCheckout(f.env()), SVC, "discount", 20))).as("legacy scalar discount").isEqualTo("REASON_REQUIRED");
        ResponseEntity<Map> ok = bill(f.desk(), atCheckout(f.env()), SVC, List.of(pct(5, "price_match")), null);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count(f.env(), "SELECT count(*) FROM invoice_discounts WHERE invoice_id = ?::uuid AND reason_code = 'price_match' AND approval_id IS NULL",
                ok.getBody().get("id"))).isEqualTo(1);
    }

    // ---------------- approval flow and reuse ----------------

    @Test
    void fApi08to10_approvalIsUsedOnceOnItsOwnBillAndCoversOnlyWhatWasApproved() {
        Fx f = fx();
        setPolicy(f, "discount_threshold_percent", "10");
        String a = atCheckout(f.env());
        ResponseEntity<Map> req = ask(f.desk(), a, SVC, List.of(pct(15, "goodwill")));
        assertThat(req.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(req.getBody()).containsEntry("status", "pending").containsEntry("billStatus", "awaiting_approval");
        Object id = req.getBody().get("approvalId");
        assertThat(code(bill(f.desk(), a, SVC, List.of(pct(15, "goodwill")), id))).isEqualTo("AWAITING_APPROVAL");
        ResponseEntity<Map> ok = decide(f.owner(), id, "approve", "in_app", null);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("status", "approved").containsEntry("method", "in_app").containsEntry("approverId", f.env().owner().id().toString());

        // F-API-09: not on another visit's bill
        String b = atCheckout(f.env());
        assertThat(code(bill(f.desk(), b, SVC, List.of(pct(15, "goodwill")), id))).isEqualTo("APPROVAL_NOT_USABLE");
        // F-API-10: not for more than approved
        assertThat(code(bill(f.desk(), a, SVC, List.of(pct(20, "goodwill")), id))).isEqualTo("APPROVAL_NOT_USABLE");
        // F-API-08: issued with it, once
        ResponseEntity<Map> issued = bill(f.desk(), a, SVC, List.of(pct(15, "goodwill")), id);
        assertThat(issued.getStatusCode()).as("%s", issued.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(count(f.env(), "SELECT count(*) FROM invoice_discounts WHERE invoice_id = ?::uuid AND approval_id = ?::uuid", issued.getBody().get("id"), id))
                .isEqualTo(1);
        assertThat(code(bill(f.desk(), atCheckout(f.env()), SVC, List.of(pct(15, "goodwill")), id))).isEqualTo("APPROVAL_NOT_USABLE");
    }

    @Test
    void fApi11_anApprovalForOneCartDoesNotCoverADifferentCartOnTheSameVisit() {
        Fx f = fx();
        setPolicy(f, "discount_threshold_percent", "10");
        String a = atCheckout(f.env());
        Object id = askOk(f.desk(), a, SVC, List.of(amt(75, "goodwill"))); // 75 of 500 = 15%
        assertThat(decide(f.owner(), id, "approve", "in_app", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        // the desk swaps the cart: 75.00 off a 400.00 bill = 18.75%, more than the owner saw
        ResponseEntity<Map> swapped = bill(f.desk(), a, List.of(line("CONS", 1)), List.of(amt(75, "goodwill")), id);
        assertThat(swapped.getStatusCode()).as("approved 15%% of SVC used for 18.75%% of CONS: %s", swapped.getBody()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void fApi12_counterSaleApprovalIsTiedToItsCart() {
        Fx f = fx();
        inTenantTx(f.env().tenant().id(), () -> jdbc.update("INSERT INTO pharmacy_settings (tenant_id, mode) VALUES (?, 'hybrid')", f.env().tenant().id()));
        setPolicy(f, "discount_threshold_amount", "5000");
        List<Map<String, Object>> xray = List.of(line("XRAY", 1));
        assertThat(code(otc(f.desk(), xray, List.of(amt(60, "waiver")), null))).isEqualTo("APPROVAL_REQUIRED");

        // (b) a visit's approval cannot be spent on a counter sale
        String entry = atCheckout(f.env());
        Object visitApproval = askOk(f.desk(), entry, SVC, List.of(amt(60, "waiver")));
        decide(f.owner(), visitApproval, "approve", "in_app", null);
        assertThat(code(otc(f.desk(), xray, List.of(amt(60, "waiver")), visitApproval))).isEqualTo("APPROVAL_NOT_USABLE");

        // (a) a counter sale approval for an SVC cart is not usable on a different (XRAY) cart
        Object counter = askOk(f.desk(), UUID.randomUUID().toString(), SVC, List.of(amt(60, "waiver")));
        decide(f.owner(), counter, "approve", "in_app", null);
        ResponseEntity<Map> other = otc(f.desk(), xray, List.of(amt(60, "waiver")), counter);
        assertThat(other.getStatusCode()).as("counter approval for SVC spent on XRAY: %s", other.getBody()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void fApi12_counterSaleWithItsOwnApprovalIsIssued() {
        Fx f = fx();
        inTenantTx(f.env().tenant().id(), () -> jdbc.update("INSERT INTO pharmacy_settings (tenant_id, mode) VALUES (?, 'hybrid')", f.env().tenant().id()));
        setPolicy(f, "discount_threshold_amount", "5000");
        Object counter = askOk(f.desk(), UUID.randomUUID().toString(), SVC, List.of(amt(60, "waiver")));
        decide(f.owner(), counter, "approve", "in_app", null);
        ResponseEntity<Map> r = otc(f.desk(), SVC, List.of(amt(60, "waiver")), counter);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    // ---------------- approver rules, race ----------------

    @Test
    void fApi13_onlyAnOwnerDecides() {
        Fx f = fx();
        setPolicy(f, "discount_threshold_percent", "10");
        Object id = askOk(f.desk(), atCheckout(f.env()), SVC, List.of(pct(15, "goodwill")));
        ResponseEntity<Map> desk = decide(f.desk(), id, "approve", "in_app", null);
        assertThat(desk.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(desk)).isEqualTo("OWNER_REQUIRED");
        String noApprove = roleToken(f, "Desk NA", new ModuleGrant("billing", true, true, true, false, false, true, false), fullGrant("queue"), fullGrant("patients"));
        assertThat(decide(noApprove, id, "approve", "in_app", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(decide(noApprove, id, "reject", "in_app", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // the owner raising a request may approve it (engineer's documented decision)
        Object own = askOk(f.owner(), atCheckout(f.env()), SVC, List.of(pct(15, "goodwill")));
        assertThat(decide(f.owner(), own, "approve", "in_app", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        // a bad decision / method is 400, not 500
        Object id2 = askOk(f.desk(), atCheckout(f.env()), SVC, List.of(pct(15, "goodwill")));
        assertThat(decide(f.owner(), id2, "maybe", "in_app", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(decide(f.owner(), id2, "approve", "sms", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(decide(f.owner(), UUID.randomUUID(), "approve", "in_app", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void fApi14_fiveConcurrentDecisionsOneWins() throws Exception {
        Fx f = fx();
        setPolicy(f, "discount_threshold_percent", "10");
        Object id = askOk(f.desk(), atCheckout(f.env()), SVC, List.of(pct(15, "goodwill")));
        List<CompletableFuture<ResponseEntity<Map>>> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String d = i % 2 == 0 ? "approve" : "reject";
            all.add(CompletableFuture.supplyAsync(() -> decide(f.owner(), id, d, "in_app", null)));
        }
        List<ResponseEntity<Map>> rs = new ArrayList<>();
        for (CompletableFuture<ResponseEntity<Map>> c : all) rs.add(c.get());
        assertThat(rs.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count()).isEqualTo(1);
        assertThat(rs.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).map(S18QaFin018DiscountApprovalApiTest::code)).hasSize(4)
                .allMatch("ALREADY_DECIDED"::equals);
        assertThat(code(decide(f.owner(), id, "reject", "in_app", null))).isEqualTo("ALREADY_DECIDED");
        assertThat(count(f.env(), "SELECT count(*) FROM outbox_cloudevents WHERE envelope->>'type' = 'billing.discount.approval_decided.v1' "
                + "AND envelope->'data'->>'approvalId' = ?", String.valueOf(id))).isEqualTo(1);
    }

    // ---------------- AC3 PIN ----------------

    @Test
    void fApi15_ownerPinAtTheDeskAndNotAnyoneElsesPin() {
        Fx f = fx();
        setPolicy(f, "discount_threshold_percent", "10");
        su().update("UPDATE staff SET pin_hash = ? WHERE id = ?", pinEncoder.encode("5678"), f.deskStaff().id()); // the desk's own PIN differs from the owner's
        String desk = loginWithPin(f, "5678");
        Object id = askOk(desk, atCheckout(f.env()), SVC, List.of(pct(15, "goodwill")));
        ResponseEntity<Map> missing = decide(desk, id, "approve", "pin", null);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(missing)).isEqualTo("STEP_UP_REQUIRED");
        assertThat(code(decide(desk, id, "approve", "pin", "not-a-token"))).isEqualTo("STEP_UP_REQUIRED");
        assertThat(code(pin(desk, "9999"))).isEqualTo("PIN_INVALID");
        ResponseEntity<Map> deskOwnPin = pin(desk, "5678");
        assertThat(deskOwnPin.getStatusCode()).as("a non-owner's PIN is not an owner PIN").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(deskOwnPin)).isEqualTo("PIN_INVALID");
        ResponseEntity<Map> p = pin(desk, STAFF_PIN);
        assertThat(p.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> ok = decide(desk, id, "approve", "pin", (String) p.getBody().get("stepUpToken"));
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).containsEntry("method", "pin").containsEntry("approverId", f.env().owner().id().toString());
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'billing.discount_approval_decided' AND actor_id = ?",
                Integer.class, f.env().tenant().id(), f.env().owner().id())).isEqualTo(1);
        // the PIN is never stored or logged in billing data
        assertThat(su().queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND (after::text LIKE '%5678%' OR after::text LIKE '%\"pin\":%')",
                Integer.class, f.env().tenant().id())).isZero();
    }

    private String loginWithPin(Fx f, String pinValue) {
        ResponseEntity<Map> r = http.postForEntity(url("/v1/auth/login"), jsonBody(Map.of("tenantSlug", f.deskStaff().slug(), "email", f.deskStaff().email(),
                "pin", pinValue)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) r.getBody().get("accessToken");
    }

    @Test
    void fApi16_onePinEntryApprovesOneRequest() {
        Fx f = fx();
        setPolicy(f, "discount_threshold_percent", "10");
        Object first = askOk(f.desk(), atCheckout(f.env()), SVC, List.of(pct(15, "goodwill")));
        Object second = askOk(f.desk(), atCheckout(f.env()), SVC, List.of(pct(40, "goodwill")));
        String assertion = (String) pin(f.desk(), STAFF_PIN).getBody().get("stepUpToken");
        assertThat(decide(f.desk(), first, "approve", "pin", assertion).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> replay = decide(f.desk(), second, "approve", "pin", assertion);
        assertThat(replay.getStatusCode()).as("the owner typed the PIN for one 15%% request; the desk replays it on a 40%% one: %s", replay.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void fApi17_wrongOwnerPinsLockOut() {
        Fx f = fx();
        for (int i = 0; i < 5; i++) {
            assertThat(code(pin(f.desk(), "000" + i))).isEqualTo("PIN_INVALID");
        }
        ResponseEntity<Map> locked = pin(f.desk(), STAFF_PIN);
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(locked.getBody()).doesNotContainKey("stepUpToken");
    }

    @Test
    void fApi18_aStaffMemberWhoCannotApproveCannotTryOwnerPins() {
        Fx f = fx();
        String nurse = roleToken(f, "Nurse Q", fullGrant("queue"), fullGrant("patients"), fullGrant("nursing"));
        ResponseEntity<Map> r = pin(nurse, STAFF_PIN);
        assertThat(r.getStatusCode()).as("pin-assertion by a role without billing:approve: %s", r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody() == null || !r.getBody().containsKey("stepUpToken")).isTrue();
    }

    // ---------------- isolation and permissions ----------------

    @Test
    void fApi19_anotherClinicSeesAndUsesNothing() {
        Fx a = fx();
        Fx b = fx();
        setPolicy(a, "discount_threshold_percent", "10");
        setPolicy(b, "discount_threshold_percent", "10");
        Object pending = askOk(a.desk(), atCheckout(a.env()), SVC, List.of(pct(15, "goodwill")));
        Object approved = askOk(a.desk(), atCheckout(a.env()), SVC, List.of(pct(20, "goodwill")));
        decide(a.owner(), approved, "approve", "in_app", null);
        bill(a.desk(), atCheckout(a.env()), SVC, List.of(pct(5, "staff")), null);

        assertThat((List) exchange("/v1/billing/discount-approvals", HttpMethod.GET, authed(b.owner()), Map.class).getBody().get("items")).isEmpty();
        assertThat(decide(b.owner(), pending, "approve", "in_app", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange("/v1/billing/discount-approvals/" + pending + "/pay-later", HttpMethod.POST, authed(b.desk()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> use = bill(b.desk(), atCheckout(b.env()), SVC, List.of(pct(20, "goodwill")), approved);
        assertThat(use.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(use)).isEqualTo("APPROVAL_NOT_USABLE");
        assertThat((List) exchange("/v1/billing/leakage", HttpMethod.GET, authed(b.owner()), Map.class).getBody().get("items")).isEmpty();
        assertThat(su().queryForObject("SELECT status FROM discount_approvals WHERE id = ?::uuid", String.class, String.valueOf(pending))).isEqualTo("pending");
    }

    @Test
    void fApi20_permissionGates() {
        Fx f = fx();
        setPolicy(f, "discount_threshold_percent", "10");
        String entry = atCheckout(f.env());
        String noDiscount = roleToken(f, "Desk ND", new ModuleGrant("billing", true, true, true, false, true, false, false), fullGrant("queue"), fullGrant("patients"));
        assertThat(ask(noDiscount, entry, SVC, List.of(pct(15, "goodwill"))).getStatusCode()).as("no billing.discount").isEqualTo(HttpStatus.FORBIDDEN);
        String viewOnly = roleToken(f, "Viewer", new ModuleGrant("billing", true, false, false, false, false, false, false), fullGrant("queue"));
        assertThat(ask(viewOnly, entry, SVC, List.of(pct(15, "goodwill"))).getStatusCode()).as("no billing:create").isEqualTo(HttpStatus.FORBIDDEN);
        String nobill = roleToken(f, "Nobill", fullGrant("queue"), fullGrant("patients"));
        assertThat(exchange("/v1/billing/discount-approvals?status=pending", HttpMethod.GET, authed(nobill), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Object id = askOk(f.desk(), entry, SVC, List.of(pct(15, "goodwill")));
        assertThat(exchange("/v1/billing/discount-approvals/" + id + "/pay-later", HttpMethod.POST, authed(viewOnly), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        String manager = roleToken(f, "Manager", fullGrant("reports"), fullGrant("billing"));
        ResponseEntity<Map> mgr = exchange("/v1/billing/leakage", HttpMethod.GET, authed(manager), Map.class);
        assertThat(mgr.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(mgr)).isEqualTo("OWNER_REQUIRED");
        assertThat(exchange("/v1/billing/leakage", HttpMethod.GET, authed(f.desk()), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------- AC5 leakage ----------------

    @Test
    void fApi21_leakageListsEveryKindWithReasonAndApproverAndHonoursThePeriod() {
        Fx f = fx();
        setPolicy(f, "discount_threshold_percent", "10");
        String owner = f.owner();
        // direct discount 25.00, later written off 100.00
        String direct = (String) bill(f.desk(), atCheckout(f.env()), SVC, List.of(pct(5, "staff")), null).getBody().get("id");
        // owner-approved below-list override: XRAY 1200 -> 1000 (16.7%)
        Map<String, Object> ov = line("XRAY", 1);
        ov.put("override", Map.of("unitPrice", 1000, "reasonCode", "pricing_error"));
        String oe = atCheckout(f.env());
        Object ovId = askOk(f.desk(), oe, List.of(ov), List.of());
        assertThat(decide(owner, ovId, "approve", "in_app", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> ovBill = checkout(f.desk(), oe, List.of(ov), "discountApprovalId", String.valueOf(ovId));
        assertThat(ovBill.getStatusCode()).as("%s", ovBill.getBody()).isEqualTo(HttpStatus.OK);
        String ovInvoice = (String) ovBill.getBody().get("id");
        // pay the override bill, then an approved refund of 100.00
        assertThat(pay(owner, ovInvoice, "cash", ovBill.getBody().get("total")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String paymentId = (String) ((Map) ((List) getInvoice(owner, ovInvoice).getBody().get("payments")).get(0)).get("id");
        Object refund = exchange("/v1/billing/invoices/" + ovInvoice + "/refund-requests", HttpMethod.POST, authedJsonBody(f.desk(), Map.of(
                "amount", 100, "reasonCode", "billing_error", "refundTo", List.of(Map.of("paymentId", paymentId, "amount", 100)))), Map.class).getBody().get("id");
        assertThat(exchange("/v1/billing/refund-requests/" + refund + "/approve", HttpMethod.POST, authedJsonBody(owner, Map.of("expectedAmount", 100)), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/billing/invoices/" + direct + "/write-off", HttpMethod.POST, authedJsonBody(owner, Map.of("amount", 100, "reasonCode", "hardship")),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        // a discount on an invoice that is voided afterwards is not leakage
        String voided = (String) bill(f.desk(), atCheckout(f.env()), SVC, List.of(pct(3, "goodwill")), null).getBody().get("id");
        assertThat(exchange("/v1/billing/invoices/" + voided + "/void", HttpMethod.POST, authedJsonBody(f.desk(), Map.of("reason", "wrong patient")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        Map body = exchange("/v1/billing/leakage", HttpMethod.GET, authed(owner), Map.class).getBody();
        List<Map> items = (List<Map>) body.get("items");
        assertThat(items).extracting(i -> i.get("type")).containsExactlyInAnyOrder("discount", "override", "write_off", "refund");
        assertThat(items).allSatisfy(i -> {
            assertThat(i.get("reasonCode")).isNotNull();
            assertThat(i.get("approverName")).isNotNull();
            assertThat(i.get("appliedByName")).isNotNull();
        });
        Map override = items.stream().filter(i -> "override".equals(i.get("type"))).findFirst().orElseThrow();
        assertThat(override).containsEntry("reasonCode", "pricing_error").containsEntry("approvalMethod", "in_app")
                .containsEntry("approverId", f.env().owner().id().toString());
        assertThat(((Number) override.get("amountMinor")).longValue()).isEqualTo(20000L);
        assertThat(items.stream().filter(i -> "refund".equals(i.get("type"))).findFirst().orElseThrow()).containsEntry("approvalMethod", "maker_checker")
                .containsEntry("reasonCode", "billing_error");
        assertThat(items.stream().filter(i -> voided.equals(i.get("invoiceId")))).as("void excluded").isEmpty();
        Map totals = (Map) body.get("totals");
        assertThat(((Number) totals.get("totalMinor")).longValue()).isEqualTo(2500L + 20000L + 10000L + 10000L);
        assertThat(((Number) totals.get("ownerApprovedMinor")).longValue()).isEqualTo(20000L);

        String yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        assertThat((List) exchange("/v1/billing/leakage?from=" + yesterday + "&to=" + yesterday, HttpMethod.GET, authed(owner), Map.class).getBody().get("items"))
                .isEmpty();
        assertThat(exchange("/v1/billing/leakage?from=" + today + "&to=" + yesterday, HttpMethod.GET, authed(owner), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((List<Map>) exchange("/v1/billing/leakage?type=override", HttpMethod.GET, authed(owner), Map.class).getBody().get("items"))
                .extracting(i -> i.get("type")).containsOnly("override");
    }

    // ---------------- pay later, reasons list, events, expiry ----------------

    @Test
    void fApi22_payLaterThenApproveThenIssue() {
        Fx f = fx();
        setPolicy(f, "discount_threshold_percent", "10");
        String entry = atCheckout(f.env());
        Object id = askOk(f.desk(), entry, SVC, List.of(pct(15, "goodwill")));
        ResponseEntity<Map> later = exchange("/v1/billing/discount-approvals/" + id + "/pay-later", HttpMethod.POST, authed(f.desk()), Map.class);
        assertThat(later.getBody()).containsEntry("payLater", true).containsEntry("billStatus", "awaiting_approval");
        decide(f.owner(), id, "approve", "in_app", null);
        assertThat(bill(f.desk(), entry, SVC, List.of(pct(15, "goodwill")), id).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/billing/discount-approvals/" + id + "/pay-later", HttpMethod.POST, authed(f.desk()), Map.class).getStatusCode())
                .as("used approval").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void fApi23_discountReasonListReadableByABillingOnlyDesk() {
        Fx f = fx();
        String billingOnly = roleToken(f, "Cashier", new ModuleGrant("billing", true, true, false, false, false, false, false));
        ResponseEntity<Map> r = exchange("/v1/reason-codes?kind=discount&locale=en", HttpMethod.GET, authed(billingOnly), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map>) r.getBody().get("items")).extracting(i -> i.get("code"))
                .contains("goodwill", "price_match", "pricing_error", "staff", "waiver", "other");
        String queueOnly = roleToken(f, "QueueOnly", fullGrant("queue"));
        assertThat(exchange("/v1/reason-codes?kind=discount", HttpMethod.GET, authed(queueOnly), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String neither = roleToken(f, "Neither", fullGrant("patients"));
        assertThat(exchange("/v1/reason-codes?kind=discount", HttpMethod.GET, authed(neither), Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void fApi24_eventsCarryNoPatientData() {
        Fx f = fx();
        setPolicy(f, "discount_threshold_percent", "10");
        Object id = askOk(f.desk(), atCheckout(f.env()), SVC, List.of(pct(15, "goodwill")));
        decide(f.owner(), id, "reject", "in_app", null);
        List<String> data = inTenantTx(f.env().tenant().id(), () -> jdbc.queryForList(
                "SELECT envelope->>'type' || ' ' || (envelope->'data')::text FROM outbox_cloudevents WHERE envelope->'data'->>'approvalId' = ? ORDER BY 1",
                String.class, String.valueOf(id)));
        assertThat(data).hasSize(2);
        assertThat(data.get(0)).startsWith("billing.discount.approval_decided.v1").contains("rejected");
        assertThat(data.get(1)).startsWith("billing.discount.approval_requested.v1").contains("7500");
        assertThat(String.join(" ", data)).doesNotContain("Qa ").doesNotContain("+91").doesNotContainIgnoringCase("phone").doesNotContainIgnoringCase("patient");
    }

    @Test
    void fApi25and26_expiredAndSupersededRequests() {
        Fx f = fx();
        setPolicy(f, "discount_threshold_percent", "10");
        String entry = atCheckout(f.env());
        Object r1 = askOk(f.desk(), entry, SVC, List.of(pct(15, "goodwill")));
        Object r2 = askOk(f.desk(), entry, SVC, List.of(pct(18, "goodwill")));
        assertThat(su().queryForObject("SELECT status FROM discount_approvals WHERE id = ?::uuid", String.class, String.valueOf(r1))).isEqualTo("expired");
        assertThat(code(decide(f.owner(), r1, "approve", "in_app", null))).isEqualTo("ALREADY_DECIDED");
        su().update("UPDATE discount_approvals SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", String.valueOf(r2));
        ResponseEntity<Map> late = decide(f.owner(), r2, "approve", "in_app", null);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getBody()).containsEntry("status", "expired");
        assertThat(code(bill(f.desk(), entry, SVC, List.of(pct(18, "goodwill")), r2))).isEqualTo("APPROVAL_NOT_USABLE");
    }
}

package com.nabd.hms.packages;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ConcurrencyHarness;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent QA (S11 FIN-004): one open refund per package, amount true at approval, races, reject, book/redeem block, extend. */
@SuppressWarnings({"rawtypes", "unchecked"})
class S11QaFin004RefundOnceTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private record Ctx(SeededTenant tenant, UUID role, String maker, List<String> checkers, String instanceId, List<String> itemIds) {
    }

    private String phone() {
        return "+9195" + (10_000_000 + SEQ.incrementAndGet());
    }

    private String login(SeededTenant t, UUID role) {
        return loginAndGetAccessToken(seedStaff(t, role, "q3f" + UUID.randomUUID() + "@a.com", phone(), false));
    }

    /** 3 x Session (list 500, 0% tax) sold at 1200. */
    private Ctx ctx(int checkers) {
        SeededTenant t = seedTenant();
        UUID role = seedFullAccessRole(t.id());
        String maker = login(t, role);
        List<String> cs = new ArrayList<>();
        for (int i = 0; i < checkers; i++) {
            cs.add(login(t, role));
        }
        return new Ctx(t, role, maker, cs, null, null);
    }

    private Ctx sell(Ctx c) {
        ResponseEntity<Map> reg = exchange("/v1/patients", HttpMethod.POST, authedJsonBody(c.maker(), Map.of(
                "name", "Qarefund Lea " + UUID.randomUUID().toString().substring(0, 6), "phone", phone(), "dob", "1985-01-01", "gender", "female",
                "confirmedNotDuplicate", true)), Map.class);
        assertThat(reg.getStatusCode().is2xxSuccessful()).as("register " + reg.getBody()).isTrue();
        String patientId = (String) reg.getBody().get("id");
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "name", "QA3 Pkg " + UUID.randomUUID().toString().substring(0, 6), "packageType", "combination", "speciality", "Physio",
                "description", "d", "price", 1200.00, "taxInclusive", false, "validityDays", 60, "validityStarts", "purchase_date"));
        body.put("graceDays", 0);
        body.put("items", List.of(Map.of("itemType", "service_session", "name", "Session", "quantity", 3, "unitListPrice", 500.00, "taxRatePercent", 0.0)));
        ResponseEntity<Map> pkg = exchange("/v1/packages", HttpMethod.POST, authedJsonBody(c.maker(), body), Map.class);
        assertThat(pkg.getStatusCode().is2xxSuccessful()).as(String.valueOf(pkg.getBody())).isTrue();
        String pkgId = (String) pkg.getBody().get("id");
        ResponseEntity<Map> act = exchange("/v1/packages/" + pkgId + "/activate", HttpMethod.POST, authed(c.maker()), Map.class);
        assertThat(act.getStatusCode().is2xxSuccessful()).as("activate " + act.getBody()).isTrue();
        ResponseEntity<Map> sold = exchange("/v1/packages/sell", HttpMethod.POST, authedJsonBody(c.maker(), Map.of(
                "patientId", patientId, "packageId", pkgId, "paymentMethod", "upi")), Map.class);
        assertThat(sold.getStatusCode().is2xxSuccessful()).as("sell " + sold.getBody()).isTrue();
        Map inst = sold.getBody();
        List<String> items = ((List<Map<String, Object>>) inst.get("items")).stream().map(i -> (String) i.get("id")).toList();
        return new Ctx(c.tenant(), c.role(), c.maker(), c.checkers(), (String) inst.get("id"), items);
    }

    private ResponseEntity<Map> request(String tok, String instanceId) {
        return exchange("/v1/packages/instances/" + instanceId + "/refund", HttpMethod.POST, authedJsonBody(tok, Map.of("reason", "Moving away")), Map.class);
    }

    private ResponseEntity<Map> approve(String tok, String refundId, Object expected) {
        return expected == null ? exchange("/v1/packages/refunds/" + refundId + "/approve", HttpMethod.POST, authed(tok), Map.class)
                : exchange("/v1/packages/refunds/" + refundId + "/approve", HttpMethod.POST, authedJsonBody(tok, Map.of("expectedAmount", expected)), Map.class);
    }

    private ResponseEntity<Map> reject(String tok, String refundId) {
        return exchange("/v1/packages/refunds/" + refundId + "/reject", HttpMethod.POST, authedJsonBody(tok, Map.of("reason", "Stays")), Map.class);
    }

    private ResponseEntity<Map> item(String tok, String itemId, String action) {
        return exchange("/v1/packages/instances/items/" + itemId + "/" + action, HttpMethod.POST, authed(tok), Map.class);
    }

    private int count(UUID t, String sql, Object... args) {
        return inTenantTx(t, () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    private static String type(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("type"));
    }

    private static long cnSeq(Object cn) {
        return Long.parseLong(String.valueOf(cn).substring(String.valueOf(cn).lastIndexOf('-') + 1)); // CN-26-27-000001
    }

    // AC1
    @Test
    void ac1_secondRequestWhilePending409_byAnyUser_andOnlyOnePendingRowExists() {
        Ctx c = sell(ctx(1));
        assertThat(request(c.maker(), c.instanceId()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        for (String tok : List.of(c.maker(), c.checkers().get(0))) {
            ResponseEntity<Map> again = request(tok, c.instanceId());
            assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(type(again)).endsWith("refund-already-open");
        }
        assertThat(count(c.tenant().id(), "SELECT count(*) FROM package_refunds WHERE instance_id = ?::uuid AND status = 'pending'", c.instanceId())).isEqualTo(1);
        // the partial unique index backs it at the database too
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> inTenantTx(c.tenant().id(), () -> jdbc.update(
                "INSERT INTO package_refunds (tenant_id, instance_id, reason, used_list_value, refund_amount, amount_owed, requested_by, status) " +
                        "SELECT tenant_id, instance_id, 'dup', used_list_value, refund_amount, amount_owed, requested_by, 'pending' FROM package_refunds WHERE instance_id = ?::uuid",
                c.instanceId()))).hasMessageContaining("uq_package_refunds_one_pending");
    }

    // AC2: four approvers at once; one credit note, and the next approval gets the very next number (no number burnt by losers)
    @Test
    void ac2_fourConcurrentApprovers_oneWinner_oneCreditNote_noNumberBurnt() {
        Ctx c = sell(ctx(4));
        String refundId = (String) request(c.maker(), c.instanceId()).getBody().get("id");
        List<Callable<ResponseEntity<Map>>> tasks = new ArrayList<>();
        c.checkers().forEach(tok -> tasks.add(() -> approve(tok, refundId, null)));
        List<ResponseEntity<Map>> rs = ConcurrencyHarness.race(tasks);
        List<ResponseEntity<Map>> ok = rs.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).toList();
        assertThat(ok).as(rs.toString()).hasSize(1);
        assertThat(rs.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).map(S11QaFin004RefundOnceTest::type))
                .hasSize(3).allMatch(s -> s.endsWith("already-decided"));
        assertThat(count(c.tenant().id(), "SELECT count(*) FROM package_refunds WHERE instance_id = ?::uuid AND credit_note_number IS NOT NULL", c.instanceId())).isEqualTo(1);
        long first = cnSeq(ok.get(0).getBody().get("creditNoteNumber"));

        Ctx c2 = sell(c);
        String r2 = (String) request(c2.maker(), c2.instanceId()).getBody().get("id");
        long second = cnSeq(approve(c2.checkers().get(0), r2, null).getBody().get("creditNoteNumber"));
        assertThat(second).as("losers drew no credit note number").isEqualTo(first + 1);
    }

    // AC2 variant: approve races reject; exactly one decision sticks and the instance state matches it
    @Test
    void ac2_approveRacesReject_exactlyOneDecision_stateConsistent() {
        for (int round = 0; round < 3; round++) {
            Ctx c = sell(ctx(2));
            String refundId = (String) request(c.maker(), c.instanceId()).getBody().get("id");
            List<ResponseEntity<Map>> rs = ConcurrencyHarness.race(List.of(
                    () -> approve(c.checkers().get(0), refundId, null), () -> reject(c.checkers().get(1), refundId)));
            assertThat(rs.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count()).as(rs.toString()).isEqualTo(1);
            assertThat(rs.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count()).isEqualTo(1);
            String status = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT status FROM package_refunds WHERE id = ?::uuid", String.class, refundId));
            String inst = inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT status FROM package_instances WHERE id = ?::uuid", String.class, c.instanceId()));
            int cns = count(c.tenant().id(), "SELECT count(*) FROM package_refunds WHERE id = ?::uuid AND credit_note_number IS NOT NULL", refundId);
            if ("approved".equals(status)) {
                assertThat(inst).isEqualTo("refunded");
                assertThat(cns).isEqualTo(1);
            } else {
                assertThat(status).isEqualTo("rejected");
                assertThat(inst).isEqualTo("active");
                assertThat(cns).isZero();
            }
        }
    }

    // AC3: amount recomputed at approval; a stale or wrong expectedAmount is refused with both amounts shown
    @Test
    void ac3_redeemedSinceRequest_recomputedAtFullListPrice_staleExpected409_currentExpectedApproves() {
        Ctx c = sell(ctx(1));
        ResponseEntity<Map> req = request(c.maker(), c.instanceId());
        String refundId = (String) req.getBody().get("id");
        BigDecimal requested = new BigDecimal(String.valueOf(req.getBody().get("refundAmount")));

        ResponseEntity<Map> wrong = approve(c.checkers().get(0), refundId, requested.subtract(BigDecimal.ONE));
        assertThat(wrong.getStatusCode()).as("expectedAmount that was never true").isEqualTo(HttpStatus.CONFLICT);
        assertThat(type(wrong)).endsWith("amount-changed");

        inTenantTx(c.tenant().id(), () -> jdbc.update("UPDATE package_instance_items SET quantity_consumed = 2 WHERE id = ?::uuid", c.itemIds().get(0)));
        ResponseEntity<Map> stale = approve(c.checkers().get(0), refundId, requested);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(type(stale)).endsWith("amount-changed");
        BigDecimal current = new BigDecimal(String.valueOf(stale.getBody().get("currentAmount")));
        assertThat(new BigDecimal(String.valueOf(stale.getBody().get("requestedAmount")))).isEqualByComparingTo(requested);
        assertThat(requested.subtract(current)).as("two used sessions at the 500 list price, no fee").isEqualByComparingTo("1000");
        assertThat(count(c.tenant().id(), "SELECT count(*) FROM package_refunds WHERE id = ?::uuid AND status = 'pending'", refundId)).isEqualTo(1);

        ResponseEntity<Map> ok = approve(c.checkers().get(0), refundId, current);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new BigDecimal(String.valueOf(ok.getBody().get("recomputedAmount")))).isEqualByComparingTo(current);
    }

    // AC4
    @Test
    void ac4_bookAndRedeemBlockedWhilePending_otherInstancesUnaffected_unblockedAfterReject() {
        Ctx c = sell(ctx(1));
        Ctx other = sell(c);
        String refundId = (String) request(c.maker(), c.instanceId()).getBody().get("id");
        for (String action : List.of("book", "redeem")) {
            ResponseEntity<Map> r = item(c.maker(), c.itemIds().get(0), action);
            assertThat(r.getStatusCode()).as(action).isEqualTo(HttpStatus.CONFLICT);
            assertThat(type(r)).endsWith("refund-pending");
        }
        assertThat(item(c.maker(), other.itemIds().get(0), "redeem").getStatusCode()).as("a different package of the same clinic").isEqualTo(HttpStatus.OK);
        assertThat(inTenantTx(c.tenant().id(), () -> jdbc.queryForObject("SELECT quantity_consumed FROM package_instance_items WHERE id = ?::uuid",
                Integer.class, c.itemIds().get(0)))).isZero();
        assertThat(reject(c.checkers().get(0), refundId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(item(c.maker(), c.itemIds().get(0), "redeem").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // AC5
    @Test
    void ac5_extend_earlierOrEqual422_later200_refunded422() {
        Ctx c = sell(ctx(1));
        LocalDate end = LocalDate.parse((String) exchange("/v1/packages/instances/" + c.instanceId(), HttpMethod.GET, authed(c.maker()), Map.class)
                .getBody().get("validityEnd"));
        java.util.function.Function<LocalDate, ResponseEntity<Map>> extend = d -> exchange("/v1/packages/instances/" + c.instanceId() + "/extend",
                HttpMethod.POST, authedJsonBody(c.maker(), Map.of("newValidityEnd", d.toString(), "reason", "Hospitalised")), Map.class);
        assertThat(extend.apply(end.minusDays(10)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(extend.apply(end).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(extend.apply(end.plusDays(5)).getStatusCode()).isEqualTo(HttpStatus.OK);
        String refundId = (String) request(c.maker(), c.instanceId()).getBody().get("id");
        assertThat(approve(c.checkers().get(0), refundId, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> refunded = extend.apply(end.plusDays(60));
        assertThat(refunded.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(String.valueOf(refunded.getBody())).doesNotContain("Exception");
    }

    // Reject path, maker-checker, permissions, audit, event, isolation
    @Test
    void rejectAndApprove_auditedOnceEach_makerCannotApprove_noApproveGrant403_otherTenant404_eventCarriesRecomputedAmounts() {
        Ctx c = sell(ctx(1));
        String r1 = (String) request(c.maker(), c.instanceId()).getBody().get("id");
        ResponseEntity<Map> self = approve(c.maker(), r1, null);
        assertThat(self.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(self)).endsWith("same-user");
        UUID deskRole = seedRole(c.tenant().id(), "Desk" + UUID.randomUUID().toString().substring(0, 4), false,
                new ModuleGrant("packages", true, true, true, false, false, false, false), new ModuleGrant("patients", true, false, false, false, false, false, false));
        String desk = login(c.tenant(), deskRole);
        assertThat(approve(desk, r1, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(reject(desk, r1).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        UUID viewRole = seedRole(c.tenant().id(), "View" + UUID.randomUUID().toString().substring(0, 4), false,
                new ModuleGrant("packages", true, false, false, false, false, false, false));
        assertThat(request(login(c.tenant(), viewRole), c.instanceId()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Ctx b = sell(ctx(1));
        assertThat(approve(b.checkers().get(0), r1, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reject(b.checkers().get(0), r1).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(reject(c.checkers().get(0), r1).getStatusCode()).isEqualTo(HttpStatus.OK);
        String r2 = (String) request(c.maker(), c.instanceId()).getBody().get("id");
        ResponseEntity<Map> ok = approve(c.checkers().get(0), r2, null);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reject(c.checkers().get(0), r2).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        UUID t = c.tenant().id();
        assertThat(count(t, "SELECT count(*) FROM audit_log WHERE action = 'packages.refund_requested' AND entity_id IN (?::uuid, ?::uuid)", r1, r2)).isEqualTo(2);
        assertThat(count(t, "SELECT count(*) FROM audit_log WHERE action = 'packages.refund_rejected' AND entity_id = ?::uuid", r1)).isEqualTo(1);
        assertThat(count(t, "SELECT count(*) FROM audit_log WHERE action = 'packages.refund_approved' AND entity_id = ?::uuid", r2)).isEqualTo(1);
        Map<String, Object> ev = inTenantTx(t, () -> jdbc.queryForMap("SELECT payload->>'creditNoteNumber' AS cn, (payload->>'refundMinor')::bigint AS rm, " +
                "payload->>'instanceId' AS inst FROM outbox_events WHERE event_type = 'billing.package_refund.approved.v1' AND payload->>'refundId' = ?", r2));
        assertThat(ev.get("cn")).isEqualTo(ok.getBody().get("creditNoteNumber"));
        assertThat(ev.get("inst")).isEqualTo(c.instanceId());
        assertThat(((Number) ev.get("rm")).longValue()).isEqualTo(new BigDecimal(String.valueOf(ok.getBody().get("recomputedAmount"))).movePointRight(2).longValue());
        assertThat(inTenantTx(t, () -> jdbc.queryForObject("SELECT verify_audit_chain(?)", Long.class, t))).isNull();
    }
}

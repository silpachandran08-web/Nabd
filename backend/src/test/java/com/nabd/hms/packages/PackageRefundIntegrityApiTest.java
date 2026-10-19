package com.nabd.hms.packages;

import com.nabd.hms.common.ModuleGrant;
import com.nabd.hms.support.ApiTestBase;
import com.nabd.hms.support.ConcurrencyHarness;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** FIN-004: one open refund per instance, amount recomputed at approval, reject path, book/redeem blocked while pending, extend validation. */
@SuppressWarnings({"rawtypes", "unchecked"})
class PackageRefundIntegrityApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private record Ctx(SeededTenant tenant, UUID role, String maker, String checker, String instanceId, List<String> itemIds) {
    }

    private String phone() {
        return "+9196" + (10_000_000 + SEQ.incrementAndGet());
    }

    /** 2 x Facial (list 1000, 18%) + 1 x Cream (list 1000, 12%), sold at 2400 + tax. */
    private Ctx ctx() {
        SeededTenant t = seedTenant();
        UUID role = seedFullAccessRole(t.id());
        String maker = loginAndGetAccessToken(seedStaff(t, role, "mk" + UUID.randomUUID() + "@a.com", phone(), false));
        String checker = loginAndGetAccessToken(seedStaff(t, role, "ck" + UUID.randomUUID() + "@a.com", phone(), false));
        String patientId = (String) exchange("/v1/patients", HttpMethod.POST, authedJsonBody(maker, Map.of(
                "name", "Refund Integrity Patient", "phone", phone(), "dob", "1985-01-01", "gender", "female")), Map.class).getBody().get("id");
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "name", "FIN004 Pkg", "packageType", "combination", "speciality", "Dermatology", "description", "d",
                "price", 2400.00, "taxInclusive", false, "validityDays", 90, "validityStarts", "purchase_date"));
        body.put("graceDays", 7);
        body.put("items", List.of(
                Map.of("itemType", "service_session", "name", "Facial", "quantity", 2, "unitListPrice", 1000.00, "taxRatePercent", 18.0),
                Map.of("itemType", "take_home_product", "name", "Cream", "quantity", 1, "unitListPrice", 1000.00, "taxRatePercent", 12.0)));
        String pkg = (String) exchange("/v1/packages", HttpMethod.POST, authedJsonBody(maker, body), Map.class).getBody().get("id");
        exchange("/v1/packages/" + pkg + "/activate", HttpMethod.POST, authed(maker), Map.class);
        Map inst = exchange("/v1/packages/sell", HttpMethod.POST, authedJsonBody(maker, Map.of(
                "patientId", patientId, "packageId", pkg, "paymentMethod", "upi")), Map.class).getBody();
        List<String> items = ((List<Map<String, Object>>) inst.get("items")).stream().map(i -> (String) i.get("id")).toList();
        return new Ctx(t, role, maker, checker, (String) inst.get("id"), items);
    }

    private ResponseEntity<Map> request(String token, String instanceId) {
        return exchange("/v1/packages/instances/" + instanceId + "/refund", HttpMethod.POST,
                authedJsonBody(token, Map.of("reason", "Patient relocated")), Map.class);
    }

    private ResponseEntity<Map> approve(String token, String refundId, Object expectedAmount) {
        return expectedAmount == null
                ? exchange("/v1/packages/refunds/" + refundId + "/approve", HttpMethod.POST, authed(token), Map.class)
                : exchange("/v1/packages/refunds/" + refundId + "/approve", HttpMethod.POST,
                        authedJsonBody(token, Map.of("expectedAmount", expectedAmount)), Map.class);
    }

    private ResponseEntity<Map> reject(String token, String refundId) {
        return exchange("/v1/packages/refunds/" + refundId + "/reject", HttpMethod.POST,
                authedJsonBody(token, Map.of("reason", "Patient stays")), Map.class);
    }

    private ResponseEntity<Map> itemAction(String token, String itemId, String action) {
        return exchange("/v1/packages/instances/items/" + itemId + "/" + action, HttpMethod.POST, authed(token), Map.class);
    }

    private ResponseEntity<Map> extend(String token, String instanceId, LocalDate end) {
        return exchange("/v1/packages/instances/" + instanceId + "/extend", HttpMethod.POST, authedJsonBody(token, Map.of(
                "newValidityEnd", end.toString(), "reason", "Patient hospitalised")), Map.class);
    }

    private int count(UUID tenantId, String sql, Object... args) {
        return inTenantTx(tenantId, () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    private static String type(ResponseEntity<Map> r) {
        return String.valueOf(r.getBody().get("type"));
    }

    // AC1: second refund request while one is pending -> 409 REFUND_ALREADY_OPEN; a decided one frees the slot.
    @Test
    void secondRequestWhilePendingIsRefusedAndAllowedAgainAfterReject() {
        Ctx c = ctx();
        ResponseEntity<Map> first = request(c.maker(), c.instanceId());
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> second = request(c.checker(), c.instanceId());
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(type(second)).endsWith("refund-already-open");

        assertThat(reject(c.checker(), (String) first.getBody().get("id")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(request(c.maker(), c.instanceId()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void concurrentRefundRequestsOpenExactlyOne() {
        Ctx c = ctx();
        List<ResponseEntity<Map>> rs = ConcurrencyHarness.race(List.of(
                () -> request(c.maker(), c.instanceId()), () -> request(c.checker(), c.instanceId()),
                () -> request(c.maker(), c.instanceId())));
        assertThat(rs.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count()).as(rs.toString()).isEqualTo(1);
        assertThat(rs.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count()).isEqualTo(2);
        assertThat(count(c.tenant().id(), "SELECT count(*) FROM package_refunds WHERE instance_id = ?::uuid", c.instanceId())).isEqualTo(1);
    }

    @Test
    void refundedInstanceIsNotRefundableAgain() {
        Ctx c = ctx();
        String refundId = (String) request(c.maker(), c.instanceId()).getBody().get("id");
        assertThat(approve(c.checker(), refundId, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> again = request(c.maker(), c.instanceId());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(type(again)).endsWith("instance-not-refundable");
        assertThat(count(c.tenant().id(), "SELECT count(*) FROM package_refunds WHERE instance_id = ?::uuid AND credit_note_number IS NOT NULL",
                c.instanceId())).isEqualTo(1);
    }

    // AC2: concurrent approvers -> one 200, one credit note, losers 409.
    @Test
    void concurrentApproversConsumeOneCreditNoteAndLosersGet409() {
        Ctx c = ctx();
        String third = loginAndGetAccessToken(seedStaff(c.tenant(), c.role(), "ck3" + UUID.randomUUID() + "@a.com", phone(), false));
        String refundId = (String) request(c.maker(), c.instanceId()).getBody().get("id");

        List<ResponseEntity<Map>> rs = ConcurrencyHarness.race(List.of(
                () -> approve(c.checker(), refundId, null), () -> approve(third, refundId, null)));

        assertThat(rs.stream().filter(r -> r.getStatusCode() == HttpStatus.OK).count()).as(rs.toString()).isEqualTo(1);
        ResponseEntity<Map> loser = rs.stream().filter(r -> r.getStatusCode() != HttpStatus.OK).findFirst().orElseThrow();
        assertThat(loser.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(type(loser)).endsWith("already-decided");
        assertThat(count(c.tenant().id(), "SELECT count(*) FROM package_instance_events WHERE instance_id = ?::uuid AND event_type = 'refunded'",
                c.instanceId())).isEqualTo(1);
        assertThat(count(c.tenant().id(), "SELECT count(*) FROM outbox_events WHERE event_type = 'billing.package_refund.approved.v1' " +
                "AND payload->>'refundId' = ?", refundId)).isEqualTo(1);
    }

    // AC3: session redeemed between request and approval -> recomputed, approver must confirm the new amount.
    @Test
    void redeemBeforeRequestThenApprovalRecomputesAndRequiresConfirmedAmount() {
        Ctx c = ctx();
        ResponseEntity<Map> preview0 = exchange("/v1/packages/instances/" + c.instanceId() + "/refund-preview", HttpMethod.GET, authed(c.maker()), Map.class);
        String refundId = (String) request(c.maker(), c.instanceId()).getBody().get("id");
        double requested = ((Number) preview0.getBody().get("refundAmount")).doubleValue();

        // A redemption cannot happen through the API while pending (AC4), so simulate one that landed between
        // the request's preview and approval (e.g. a pre-FIN-004 request) directly in the ledger.
        inTenantTx(c.tenant().id(), () -> jdbc.update(
                "UPDATE package_instance_items SET quantity_consumed = 1 WHERE id = ?::uuid", c.itemIds().get(0)));

        ResponseEntity<Map> noConfirm = approve(c.checker(), refundId, null);
        assertThat(noConfirm.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(type(noConfirm)).endsWith("amount-changed");
        double current = ((Number) noConfirm.getBody().get("currentAmount")).doubleValue();
        assertThat(((Number) noConfirm.getBody().get("requestedAmount")).doubleValue()).isEqualTo(requested);
        assertThat(requested - current).isEqualTo(1000.00); // one session at full single-session list price, no fee

        ResponseEntity<Map> stale = approve(c.checker(), refundId, requested);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(type(stale)).endsWith("amount-changed");
        assertThat(count(c.tenant().id(), "SELECT count(*) FROM package_refunds WHERE id = ?::uuid AND status = 'pending'", refundId)).isEqualTo(1);

        ResponseEntity<Map> ok = approve(c.checker(), refundId, current);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) ok.getBody().get("recomputedAmount")).doubleValue()).isEqualTo(current);
        assertThat(((Number) ok.getBody().get("refundAmount")).doubleValue()).isEqualTo(requested);
        assertThat(ok.getBody().get("creditNoteNumber")).asString().startsWith("CN-");
        Map<String, Object> payload = inTenantTx(c.tenant().id(), () -> jdbc.queryForMap(
                "SELECT (payload->>'refundMinor')::bigint AS refund_minor, payload->>'creditNoteNumber' AS cn FROM outbox_events " +
                        "WHERE event_type = 'billing.package_refund.approved.v1' AND payload->>'refundId' = ?", refundId));
        assertThat(((Number) payload.get("refund_minor")).longValue()).isEqualTo(Math.round(current * 100));
        assertThat(payload.get("cn")).isEqualTo(ok.getBody().get("creditNoteNumber"));
    }

    // AC4: book or redeem while a refund is pending -> 409 REFUND_PENDING; allowed again after reject.
    @Test
    void bookAndRedeemBlockedWhileRefundPending() {
        Ctx c = ctx();
        String refundId = (String) request(c.maker(), c.instanceId()).getBody().get("id");
        ResponseEntity<Map> book = itemAction(c.maker(), c.itemIds().get(0), "book");
        ResponseEntity<Map> redeem = itemAction(c.maker(), c.itemIds().get(0), "redeem");
        assertThat(book.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(type(book)).endsWith("refund-pending");
        assertThat(redeem.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(type(redeem)).endsWith("refund-pending");

        reject(c.checker(), refundId);
        assertThat(itemAction(c.maker(), c.itemIds().get(0), "redeem").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rejectIsAuditedOnceAndDecidedRefundCannotBeDecidedAgain() {
        Ctx c = ctx();
        String refundId = (String) request(c.maker(), c.instanceId()).getBody().get("id");
        ResponseEntity<Map> rej = reject(c.checker(), refundId);
        assertThat(rej.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rej.getBody().get("status")).isEqualTo("rejected");
        assertThat(rej.getBody().get("creditNoteNumber")).isNull();

        ResponseEntity<Map> again = reject(c.checker(), refundId);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(type(again)).endsWith("already-decided");
        ResponseEntity<Map> approveAfter = approve(c.checker(), refundId, null);
        assertThat(approveAfter.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(type(approveAfter)).endsWith("already-decided");

        assertThat(count(c.tenant().id(), "SELECT count(*) FROM audit_log WHERE entity_id = ?::uuid AND action = 'packages.refund_rejected'",
                refundId)).isEqualTo(1);
        ResponseEntity<Map> inst = exchange("/v1/packages/instances/" + c.instanceId(), HttpMethod.GET, authed(c.maker()), Map.class);
        assertThat(inst.getBody().get("status")).isEqualTo("active");
    }

    @Test
    void makerCannotApproveAndRejectNeedsApprovePermission() {
        Ctx c = ctx();
        String refundId = (String) request(c.maker(), c.instanceId()).getBody().get("id");
        ResponseEntity<Map> self = approve(c.maker(), refundId, null);
        assertThat(self.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(type(self)).endsWith("same-user");

        UUID editOnly = seedRole(c.tenant().id(), "PkgDesk", false, new ModuleGrant("packages", true, true, true, false, false, false, false),
                new ModuleGrant("patients", true, false, false, false, false, false, false));
        String desk = loginAndGetAccessToken(seedStaff(c.tenant(), editOnly, "desk" + UUID.randomUUID() + "@a.com", phone(), false));
        assertThat(reject(desk, refundId).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(approve(desk, refundId, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void otherTenantCannotSeeOrDecideTheRefund() {
        Ctx a = ctx();
        String refundId = (String) request(a.maker(), a.instanceId()).getBody().get("id");
        Ctx b = ctx();
        assertThat(approve(b.checker(), refundId, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(reject(b.checker(), refundId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(request(b.maker(), a.instanceId()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(count(a.tenant().id(), "SELECT count(*) FROM package_refunds WHERE id = ?::uuid AND status = 'pending'", refundId)).isEqualTo(1);
    }

    // AC5: extension earlier than (or equal to) the current end, or on a refunded instance -> 422.
    @Test
    void extendMustBeLaterThanCurrentEndAndNotOnRefundedInstance() {
        Ctx c = ctx();
        LocalDate end = LocalDate.parse((String) exchange("/v1/packages/instances/" + c.instanceId(), HttpMethod.GET,
                authed(c.maker()), Map.class).getBody().get("validityEnd"));
        ResponseEntity<Map> earlier = extend(c.maker(), c.instanceId(), end.minusDays(1));
        assertThat(earlier.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(type(earlier)).endsWith("extension-not-later");
        assertThat(extend(c.maker(), c.instanceId(), end).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(extend(c.maker(), c.instanceId(), end.plusDays(1)).getStatusCode()).isEqualTo(HttpStatus.OK);

        String refundId = (String) request(c.maker(), c.instanceId()).getBody().get("id");
        approve(c.checker(), refundId, null);
        ResponseEntity<Map> refunded = extend(c.maker(), c.instanceId(), end.plusDays(30));
        assertThat(refunded.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(type(refunded)).endsWith("instance-not-extendable");
    }

    // AC6 (J12 API path): sale -> redeem -> refund maker-checker; second refund refused; one credit note; < 2 min.
    @Test
    void j12SaleRedeemRefundMakerChecker() {
        long t0 = System.nanoTime();
        Ctx c = ctx();
        assertThat(itemAction(c.maker(), c.itemIds().get(0), "redeem").getStatusCode()).isEqualTo(HttpStatus.OK);
        Map preview = exchange("/v1/packages/instances/" + c.instanceId() + "/refund-preview", HttpMethod.GET, authed(c.maker()), Map.class).getBody();
        ResponseEntity<Map> req = request(c.maker(), c.instanceId());
        assertThat(req.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(request(c.maker(), c.instanceId()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        String refundId = (String) req.getBody().get("id");

        ResponseEntity<Map> ok = approve(c.checker(), refundId, preview.get("refundAmount"));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approve(c.checker(), refundId, preview.get("refundAmount")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(count(c.tenant().id(), "SELECT count(*) FROM package_refunds WHERE instance_id = ?::uuid AND credit_note_number IS NOT NULL",
                c.instanceId())).isEqualTo(1);
        assertThat(count(c.tenant().id(), "SELECT count(*) FROM audit_log WHERE entity_id = ?::uuid AND action IN " +
                "('packages.refund_requested','packages.refund_approved')", refundId)).isEqualTo(2);
        assertThat((System.nanoTime() - t0) / 1_000_000_000L).isLessThan(120);
    }
}

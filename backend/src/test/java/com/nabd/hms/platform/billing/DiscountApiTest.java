package com.nabd.hms.platform.billing;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DiscountApiTest extends ApiTestBase {

    private String billingToken(String email) {
        return platformLoginAndGetAccessToken(seedOperator(email, "billing", false));
    }

    private String superAdminToken(String email) {
        return platformLoginAndGetAccessToken(seedOperator(email, "super_admin", false));
    }

    private UUID subscribedTenant(String requesterToken, int mrrCents) {
        SeededTenant tenant = seedTenant();
        UUID planId = seedPlan("plan-" + tenant.slug(), mrrCents, "INR", 5);
        exchange("/v1/platform/billing/subscriptions/" + tenant.id(), HttpMethod.POST,
                authedJsonBody(requesterToken, Map.of("planId", planId.toString(), "mrrCents", mrrCents,
                        "renewalDate", LocalDate.now().plusDays(30).toString())),
                Map.class);
        return tenant.id();
    }

    @Test
    void withinCapDiscountAutoApprovesAndReducesMrrImmediately() {
        String token = billingToken("billing5@nabd.internal");
        UUID tenantId = subscribedTenant(token, 100000);

        ResponseEntity<Map> resp = exchange("/v1/platform/billing/discounts", HttpMethod.POST,
                authedJsonBody(token, Map.of("tenantId", tenantId.toString(), "percent", 10, "reason", "loyalty")),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("status")).isEqualTo("auto_approved");

        // mrr after a 10% cut on 100000 is 90000
        ResponseEntity<Map> list = exchange("/v1/platform/billing/subscriptions", HttpMethod.GET, authed(token), Map.class);
        java.util.List data = (java.util.List) list.getBody().get("data");
        Map row = (Map) data.stream().filter(o -> ((Map) o).get("tenantId").equals(tenantId.toString())).findFirst().orElseThrow();
        assertThat(row.get("mrrCents")).isEqualTo(90000);
    }

    @Test
    void aboveCapDiscountQueuesAndOnlyAppliesOnApproval() {
        String requester = billingToken("billing6@nabd.internal");
        String approver = superAdminToken("super7@nabd.internal");
        UUID tenantId = subscribedTenant(requester, 100000);

        ResponseEntity<Map> requested = exchange("/v1/platform/billing/discounts", HttpMethod.POST,
                authedJsonBody(requester, Map.of("tenantId", tenantId.toString(), "percent", 25, "reason", "enterprise deal")),
                Map.class);
        assertThat(requested.getBody().get("status")).isEqualTo("pending");
        String id = (String) requested.getBody().get("id");

        ResponseEntity<Map> approved = exchange("/v1/platform/billing/discounts/" + id + "/approve", HttpMethod.POST,
                authed(approver), Map.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().get("status")).isEqualTo("approved");

        ResponseEntity<Map> list = exchange("/v1/platform/billing/subscriptions", HttpMethod.GET, authed(requester), Map.class);
        java.util.List data = (java.util.List) list.getBody().get("data");
        Map row = (Map) data.stream().filter(o -> ((Map) o).get("tenantId").equals(tenantId.toString())).findFirst().orElseThrow();
        assertThat(row.get("mrrCents")).isEqualTo(75000);
    }

    @Test
    void requesterCannotApproveTheirOwnDiscountRequest() {
        String token = superAdminToken("super8@nabd.internal"); // holds billing_revenue:approve, so the maker-checker rule is what stops it
        UUID tenantId = subscribedTenant(token, 100000);
        ResponseEntity<Map> requested = exchange("/v1/platform/billing/discounts", HttpMethod.POST,
                authedJsonBody(token, Map.of("tenantId", tenantId.toString(), "percent", 30, "reason", "self")), Map.class);
        String id = (String) requested.getBody().get("id");

        ResponseEntity<Map> resp = exchange("/v1/platform/billing/discounts/" + id + "/approve", HttpMethod.POST,
                authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectingAPendingDiscountLeavesMrrUnchanged() {
        String requester = billingToken("billing9@nabd.internal");
        String reviewer = superAdminToken("super10@nabd.internal");
        UUID tenantId = subscribedTenant(requester, 100000);
        ResponseEntity<Map> requested = exchange("/v1/platform/billing/discounts", HttpMethod.POST,
                authedJsonBody(requester, Map.of("tenantId", tenantId.toString(), "percent", 40, "reason", "big ask")), Map.class);
        String id = (String) requested.getBody().get("id");

        ResponseEntity<Map> rejected = exchange("/v1/platform/billing/discounts/" + id + "/reject", HttpMethod.POST,
                authed(reviewer), Map.class);
        assertThat(rejected.getBody().get("status")).isEqualTo("rejected");

        ResponseEntity<Map> list = exchange("/v1/platform/billing/subscriptions", HttpMethod.GET, authed(requester), Map.class);
        java.util.List data = (java.util.List) list.getBody().get("data");
        Map row = (Map) data.stream().filter(o -> ((Map) o).get("tenantId").equals(tenantId.toString())).findFirst().orElseThrow();
        assertThat(row.get("mrrCents")).isEqualTo(100000);
    }

    @Test
    void requestingADiscountForATenantWithNoSubscriptionFails() {
        String token = billingToken("billing11@nabd.internal");
        SeededTenant tenant = seedTenant();

        ResponseEntity<Map> resp = exchange("/v1/platform/billing/discounts", HttpMethod.POST,
                authedJsonBody(token, Map.of("tenantId", tenant.id().toString(), "percent", 5, "reason", "n/a")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // V2-005: billing can request and auto-approve within the cap, but cannot approve or reject above-cap requests.
    @Test
    void billingRoleCannotApproveOrRejectAboveCapDiscounts() {
        String requester = billingToken("discount-billing12@nabd.internal");
        String otherBilling = billingToken("discount-billing13@nabd.internal");
        UUID tenantId = subscribedTenant(requester, 100000);
        ResponseEntity<Map> requested = exchange("/v1/platform/billing/discounts", HttpMethod.POST,
                authedJsonBody(requester, Map.of("tenantId", tenantId.toString(), "percent", 25, "reason", "enterprise")), Map.class);
        String id = (String) requested.getBody().get("id");

        assertThat(exchange("/v1/platform/billing/discounts/" + id + "/approve", HttpMethod.POST, authed(otherBilling), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/platform/billing/discounts/" + id + "/reject", HttpMethod.POST, authed(otherBilling), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> list = exchange("/v1/platform/billing/discounts", HttpMethod.GET, authed(otherBilling), Map.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK); // reads stay available to billing_revenue:view
    }

    // V2-005: commercial shares billing_revenue:view but is read-only.
    @Test
    void commercialRoleIsReadOnlyOnBilling() {
        String billing = billingToken("billing14@nabd.internal");
        UUID tenantId = subscribedTenant(billing, 100000);
        String commercial = platformLoginAndGetAccessToken(seedOperator("commercial2@nabd.internal", "commercial", false));

        assertThat(exchange("/v1/platform/billing/subscriptions", HttpMethod.GET, authed(commercial), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/platform/billing/discounts", HttpMethod.POST,
                authedJsonBody(commercial, Map.of("tenantId", tenantId.toString(), "percent", 5, "reason", "x")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/platform/billing/subscriptions/" + tenantId + "/transitions", HttpMethod.POST,
                authedJsonBody(commercial, Map.of("toStatus", "suspended", "reason", "no authority here")), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange("/v1/platform/billing/subscriptions/" + tenantId, HttpMethod.POST,
                authedJsonBody(commercial, Map.of("planId", UUID.randomUUID().toString(), "mrrCents", 1,
                        "renewalDate", LocalDate.now().plusDays(30).toString())), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void approvingADiscountIsAudited() {
        String requester = billingToken("billing15@nabd.internal");
        String approver = superAdminToken("super15@nabd.internal");
        UUID tenantId = subscribedTenant(requester, 100000);
        ResponseEntity<Map> requested = exchange("/v1/platform/billing/discounts", HttpMethod.POST,
                authedJsonBody(requester, Map.of("tenantId", tenantId.toString(), "percent", 25, "reason", "audit me")), Map.class);
        String id = (String) requested.getBody().get("id");
        exchange("/v1/platform/billing/discounts/" + id + "/approve", HttpMethod.POST, authed(approver), Map.class);

        // second approval of the same request is refused, and the discount is applied once
        assertThat(exchange("/v1/platform/billing/discounts/" + id + "/approve", HttpMethod.POST, authed(approver), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Integer rows = inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'platform_billing.discount_approved'", Integer.class, tenantId));
        assertThat(rows).isEqualTo(1);
    }
}

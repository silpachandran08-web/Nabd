package com.nabd.hms.platform.billing;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BillingApiTest extends ApiTestBase {

    @Test
    void upsertingASubscriptionDerivesCurrencyFromTenantRegionAndReportsSeatUsage() {
        SeededTenant tenant = seedTenant(); // region "IN"
        UUID roleId = seedFullAccessRole(tenant.id());
        seedStaff(tenant, roleId, "doc1@acme.health", "+911111111111", false);
        seedStaff(tenant, roleId, "doc2@acme.health", "+911111111112", false);
        UUID planId = seedPlan("growth-" + tenant.slug(), 500000, "INR", 5);
        String token = platformLoginAndGetAccessToken(seedOperator("billing3@nabd.internal", "billing", false));

        ResponseEntity<Map> resp = exchange("/v1/platform/billing/subscriptions/" + tenant.id(), HttpMethod.POST,
                authedJsonBody(token, Map.of("planId", planId.toString(), "mrrCents", 500000,
                        "renewalDate", LocalDate.now().plusDays(30).toString())),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("currency")).isEqualTo("INR");
        assertThat(resp.getBody().get("seatsUsed")).isEqualTo(2);
        assertThat(resp.getBody().get("seatLimit")).isEqualTo(5);

        // The suite shares one Postgres container across every test class, so this list legitimately
        // carries other tests' subscriptions too — assert this tenant's row is in there, not the count.
        ResponseEntity<Map> list = exchange("/v1/platform/billing/subscriptions", HttpMethod.GET, authed(token), Map.class);
        assertThat((List) list.getBody().get("data"))
                .extracting(o -> ((Map) o).get("tenantId"))
                .contains(tenant.id().toString());
    }

    @Test
    void billingCanMoveATenantToOverdueButNotToOffboarding() {
        SeededTenant tenant = seedTenant();
        UUID planId = seedPlan("starter-" + tenant.slug(), 200000, "INR", 3);
        String token = platformLoginAndGetAccessToken(seedOperator("billing4@nabd.internal", "billing", false));
        exchange("/v1/platform/billing/subscriptions/" + tenant.id(), HttpMethod.POST,
                authedJsonBody(token, Map.of("planId", planId.toString(), "mrrCents", 200000,
                        "renewalDate", LocalDate.now().plusDays(30).toString())),
                Map.class);

        ResponseEntity<Map> overdue = exchange("/v1/platform/billing/subscriptions/" + tenant.id() + "/transitions",
                HttpMethod.POST, authedJsonBody(token, Map.of("toStatus", "overdue", "reason", "payment failed")), Map.class);
        assertThat(overdue.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(overdue.getBody().get("tenantStatus")).isEqualTo("overdue");

        ResponseEntity<Map> offboarding = exchange("/v1/platform/billing/subscriptions/" + tenant.id() + "/transitions",
                HttpMethod.POST, authedJsonBody(token, Map.of("toStatus", "offboarding", "reason", "nope")), Map.class);
        assertThat(offboarding.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void roleWithoutBillingRevenueIsForbidden() {
        String token = platformLoginAndGetAccessToken(seedOperator("compliance1@nabd.internal", "compliance_dpo", false));

        ResponseEntity<Map> resp = exchange("/v1/platform/billing/subscriptions", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}

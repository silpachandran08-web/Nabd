package com.nabd.hms.platform.tenant;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantLifecycleApiTest extends ApiTestBase {

    @Test
    void aValidTransitionUpdatesStatusAndWritesAnAuditEvent() {
        SeededTenant tenant = seedTenant(); // seeded as 'active'
        String token = superAdminToken();

        ResponseEntity<Map> resp = exchange("/v1/platform/tenants/" + tenant.id() + "/lifecycle/transitions",
                HttpMethod.POST, authedJsonBody(token, Map.of("toStatus", "suspended", "reason", "non-payment")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("suspended");
        List<Map<String, Object>> events = (List<Map<String, Object>>) resp.getBody().get("events");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("fromStatus")).isEqualTo("active");
        assertThat(events.get(0).get("toStatus")).isEqualTo("suspended");
        assertThat(events.get(0).get("reason")).isEqualTo("non-payment");
    }

    @Test
    void anInvalidTransitionIsRejectedAndNothingChanges() {
        SeededTenant tenant = seedTenant(); // 'active'
        String token = superAdminToken();

        ResponseEntity<Map> resp = exchange("/v1/platform/tenants/" + tenant.id() + "/lifecycle/transitions",
                HttpMethod.POST, authedJsonBody(token, Map.of("toStatus", "provisioning", "reason", "nonsense")), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String status = jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenant.id());
        assertThat(status).isEqualTo("active");
    }

    @Test
    void offboardedIsTerminalNoTransitionOut() {
        SeededTenant tenant = seedTenant();
        String token = superAdminToken();

        exchange("/v1/platform/tenants/" + tenant.id() + "/lifecycle/transitions", HttpMethod.POST,
                authedJsonBody(token, Map.of("toStatus", "offboarding", "reason", "customer churned")), Map.class);
        ResponseEntity<Map> offboarded = exchange("/v1/platform/tenants/" + tenant.id() + "/lifecycle/transitions", HttpMethod.POST,
                authedJsonBody(token, Map.of("toStatus", "offboarded", "reason", "grace window elapsed")), Map.class);
        assertThat(offboarded.getBody().get("status")).isEqualTo("offboarded");

        ResponseEntity<Map> resurrect = exchange("/v1/platform/tenants/" + tenant.id() + "/lifecycle/transitions", HttpMethod.POST,
                authedJsonBody(token, Map.of("toStatus", "active", "reason", "changed our minds")), Map.class);
        assertThat(resurrect.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void billingRoleCannotTransitionATenant() {
        SeededTenant tenant = seedTenant();
        SeededOperator billing = seedOperator("billing-lifecycle@nabd.health", "billing", false);
        String token = platformLoginAndGetAccessToken(billing);

        ResponseEntity<Map> resp = exchange("/v1/platform/tenants/" + tenant.id() + "/lifecycle/transitions",
                HttpMethod.POST, authedJsonBody(token, Map.of("toStatus", "suspended", "reason", "nope")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void supportEngineerCanTransitionATenant() {
        SeededTenant tenant = seedTenant();
        SeededOperator support = seedOperator("support-lifecycle@nabd.health", "support_engineer", false);
        String token = platformLoginAndGetAccessToken(support);

        ResponseEntity<Map> resp = exchange("/v1/platform/tenants/" + tenant.id() + "/lifecycle/transitions",
                HttpMethod.POST, authedJsonBody(token, Map.of("toStatus", "suspended", "reason", "abuse")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void goLiveAutomaticallyPromotesProvisioningToTrialingWithAnAuditedEvent() {
        String token = superAdminToken();
        ResponseEntity<Map> created = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST,
                authedJsonBody(token, Map.of(
                        "tenantSlug", "clinic-lifecycle-golive",
                        "tenantName", "Test Clinic",
                        "region", "IN",
                        "ownerEmail", "owner-lifecycle@nabd.health",
                        "ownerName", "Test Owner",
                        "ownerMobile", "+919876543210",
                        "brandName", "Test Brand Lifecycle",
                        "path", "self_serve"
                )), Map.class);
        UUID jobId = UUID.fromString((String) created.getBody().get("id"));

        Map<String, Object> jobResult = null;
        for (int i = 0; i < 6; i++) {
            jobResult = exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance", HttpMethod.POST, authed(token), Map.class).getBody();
        }
        UUID tenantId = UUID.fromString((String) jobResult.get("createdTenantId"));

        ResponseEntity<Map> lifecycle = exchange("/v1/platform/tenants/" + tenantId + "/lifecycle", HttpMethod.GET, authed(token), Map.class);
        assertThat(lifecycle.getBody().get("status")).isEqualTo("trialing");
        List<Map<String, Object>> events = (List<Map<String, Object>>) lifecycle.getBody().get("events");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("fromStatus")).isEqualTo("provisioning");
        assertThat(events.get(0).get("toStatus")).isEqualTo("trialing");
        assertThat(events.get(0).get("reason")).isEqualTo("provisioning completed");
    }

    private String superAdminToken() {
        SeededOperator operator = seedOperator("super-lifecycle-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        return platformLoginAndGetAccessToken(operator);
    }
}

package com.nabd.hms.platform.provisioning;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProvisioningJobApiTest extends ApiTestBase {

    @Test
    void onlySuperAdminOrImplementationCanCreateAJob() {
        SeededOperator billing = seedOperator("billing-prov@nabd.health", "billing", false);
        String token = platformLoginAndGetAccessToken(billing);

        ResponseEntity<Map> resp = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST,
                authedJsonBody(token, jobRequest("clinic-a")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void creatingAJobQueuesAllSixStepsInOrder() {
        String token = superAdminToken();

        ResponseEntity<Map> resp = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST,
                authedJsonBody(token, jobRequest("clinic-b")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("status")).isEqualTo("queued");

        List<Map<String, Object>> steps = (List<Map<String, Object>>) resp.getBody().get("steps");
        assertThat(steps).hasSize(6);
        assertThat(steps.stream().map(s -> s.get("stepName")).toList()).containsExactly(
                "create_tenant", "migrate_schema", "seed_masters",
                "provision_whatsapp", "verify_invite_owner", "go_live");
        assertThat(steps).allMatch(s -> "queued".equals(s.get("status")));
    }

    @Test
    void advancingSixTimesRunsTheJobToCompletionAndCreatesTheTenant() {
        String token = superAdminToken();
        UUID jobId = createJob(token, "clinic-c");

        Map<String, Object> body = null;
        for (int i = 0; i < 6; i++) {
            ResponseEntity<Map> resp = exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance",
                    HttpMethod.POST, authed(token), Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            body = resp.getBody();
        }
        assertThat(body.get("status")).isEqualTo("done");
        assertThat(body.get("createdTenantId")).isNotNull();
        List<Map<String, Object>> steps = (List<Map<String, Object>>) body.get("steps");
        assertThat(steps).allMatch(s -> "done".equals(s.get("status")));

        UUID tenantId = UUID.fromString((String) body.get("createdTenantId"));
        String status = jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, tenantId);
        assertThat(status).isEqualTo("trial");
        Integer roleCount = inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM roles WHERE tenant_id = ? AND built_in = true", Integer.class, tenantId));
        assertThat(roleCount).isEqualTo(1);
    }

    @Test
    void advancingAFinishedJobIsIdempotent() {
        String token = superAdminToken();
        UUID jobId = createJob(token, "clinic-d");
        for (int i = 0; i < 6; i++) {
            exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance", HttpMethod.POST, authed(token), Map.class);
        }
        ResponseEntity<Map> resp = exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance",
                HttpMethod.POST, authed(token), Map.class);
        assertThat(resp.getBody().get("status")).isEqualTo("done");
    }

    @Test
    void aDuplicateTenantSlugFailsTheCreateTenantStepWithoutHalfCreatingAnything() {
        String token = superAdminToken();
        UUID firstJob = createJob(token, "clinic-dup");
        exchange("/v1/platform/provisioning-jobs/" + firstJob + "/advance", HttpMethod.POST, authed(token), Map.class);

        UUID secondJob = createJob(token, "clinic-dup"); // same slug
        ResponseEntity<Map> resp = exchange("/v1/platform/provisioning-jobs/" + secondJob + "/advance",
                HttpMethod.POST, authed(token), Map.class);

        assertThat(resp.getBody().get("status")).isEqualTo("failed");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) resp.getBody().get("steps");
        Map<String, Object> createTenantStep = steps.get(0);
        assertThat(createTenantStep.get("status")).isEqualTo("failed");
        assertThat(createTenantStep.get("errorDetail")).asString().contains("already exists");

        Integer tenantCount = jdbc.queryForObject(
                "SELECT count(*) FROM tenants WHERE slug = ?::citext", Integer.class, "clinic-dup");
        assertThat(tenantCount).isEqualTo(1); // the first job's tenant only — the second attempt left nothing behind
    }

    @Test
    void retryingAFailedStepPicksUpWhereItLeftOff() {
        String token = superAdminToken();
        UUID firstJob = createJob(token, "clinic-retry");
        exchange("/v1/platform/provisioning-jobs/" + firstJob + "/advance", HttpMethod.POST, authed(token), Map.class);

        UUID secondJob = createJob(token, "clinic-retry"); // collides -> create_tenant fails
        exchange("/v1/platform/provisioning-jobs/" + secondJob + "/advance", HttpMethod.POST, authed(token), Map.class);
        ResponseEntity<Map> failed = exchange("/v1/platform/provisioning-jobs/" + secondJob, HttpMethod.GET, authed(token), Map.class);
        assertThat(failed.getBody().get("status")).isEqualTo("failed");

        // no way to un-collide the slug in this test without a second tenant, but retrying the same
        // failed step must not silently skip ahead to a later step
        ResponseEntity<Map> retried = exchange("/v1/platform/provisioning-jobs/" + secondJob + "/advance",
                HttpMethod.POST, authed(token), Map.class);
        List<Map<String, Object>> steps = (List<Map<String, Object>>) retried.getBody().get("steps");
        assertThat(steps.get(0).get("stepName")).isEqualTo("create_tenant");
        assertThat(steps.get(0).get("status")).isEqualTo("failed");
        assertThat(steps.get(1).get("status")).isEqualTo("queued"); // migrate_schema never started
    }

    @Test
    void listAndGetRequireTheProvisioningAuthorityToo() {
        SeededOperator sre = seedOperator("sre-prov@nabd.health", "sre", false);
        String token = platformLoginAndGetAccessToken(sre);
        assertThat(exchange("/v1/platform/provisioning-jobs", HttpMethod.GET, authed(token), List.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private UUID createJob(String token, String slug) {
        ResponseEntity<Map> resp = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST,
                authedJsonBody(token, jobRequest(slug)), Map.class);
        return UUID.fromString((String) resp.getBody().get("id"));
    }

    private String superAdminToken() {
        SeededOperator operator = seedOperator("super-prov-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        return platformLoginAndGetAccessToken(operator);
    }

    private Map<String, String> jobRequest(String slug) {
        return Map.of(
                "tenantSlug", slug,
                "tenantName", "Test Clinic " + slug,
                "region", "IN",
                "ownerEmail", "owner-" + slug + "@nabd.health",
                "ownerName", "Test Owner",
                "brandName", "Test Brand " + slug
        );
    }
}

package com.nabd.hms.platform.provisioning;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nabd.hms.platform.provisioning.ProvisioningModels.Job;
import static org.assertj.core.api.Assertions.assertThat;

class ProvisioningJobApiTest extends ApiTestBase {

    @Autowired
    private ProvisioningRepository provisioningRepo;
    @Autowired
    private ProvisioningStepRunner stepRunner;

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
    void aMixedCaseTenantSlugIsAcceptedAndStoredLowercase() {
        String token = superAdminToken();
        ResponseEntity<Map> resp = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST,
                authedJsonBody(token, jobRequest("Clinic-MixedCase")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("tenantSlug")).isEqualTo("clinic-mixedcase");
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
        assertThat(status).isEqualTo("trialing");
        Integer roleCount = inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM roles WHERE tenant_id = ? AND built_in = true", Integer.class, tenantId));
        assertThat(roleCount).isEqualTo(1);
    }

    // ---- NB-353: verify_invite_owner creates a real, login-capable staff row for the owner ----

    @Test
    void verifyInviteOwnerStepCreatesAStaffRowAndReturnsAOneTimeInviteToken() {
        String token = superAdminToken();
        UUID jobId = createJob(token, "clinic-owner-login");

        Map<String, Object> body = null;
        for (int i = 0; i < 6; i++) {
            ResponseEntity<Map> resp = exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance",
                    HttpMethod.POST, authed(token), Map.class);
            body = resp.getBody();
            if (i == 4) { // 0-indexed: the 5th advance() call runs verify_invite_owner
                assertThat(body.get("ownerInviteToken")).isNotNull();
            } else {
                assertThat(body.get("ownerInviteToken")).isNull();
            }
        }
        assertThat(body.get("status")).isEqualTo("done");

        Job job = provisioningRepo.findJob(jobId).orElseThrow();
        UUID tenantId = job.createdTenantId();
        Map<String, Object> staffRow = inTenantTx(tenantId, () ->
                jdbc.queryForMap("SELECT email::text, status FROM staff WHERE tenant_id = ?", tenantId));
        assertThat(staffRow.get("email")).isEqualTo("owner-clinic-owner-login@nabd.health");
        assertThat(staffRow.get("status")).isEqualTo("invited");
    }

    @Test
    void undoingVerifyInviteOwnerRemovesTheStaffRowItCreated() {
        String token = superAdminToken();
        UUID jobId = createJob(token, "clinic-undo-invite");
        for (int i = 0; i < 5; i++) { // through verify_invite_owner
            exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance", HttpMethod.POST, authed(token), Map.class);
        }
        Job job = provisioningRepo.findJob(jobId).orElseThrow();
        UUID tenantId = job.createdTenantId();
        Integer before = inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM staff WHERE tenant_id = ?", Integer.class, tenantId));
        assertThat(before).isEqualTo(1);

        stepRunner.undo(job, "verify_invite_owner");

        Integer after = inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM staff WHERE tenant_id = ?", Integer.class, tenantId));
        assertThat(after).isEqualTo(0);
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

    // ---- NB-259: fatal failures roll back automatically, no half-created tenant ----

    @Test
    void aRegionMismatchForAnExistingOwnerRollsBackWithNoTenantCreated() {
        String token = superAdminToken();
        String ownerEmail = "region-owner@nabd.health";

        UUID firstJob = createJob(token, "clinic-region-in", ownerEmail, "IN");
        for (int i = 0; i < 6; i++) {
            exchange("/v1/platform/provisioning-jobs/" + firstJob + "/advance", HttpMethod.POST, authed(token), Map.class);
        }
        ResponseEntity<Map> firstDone = exchange("/v1/platform/provisioning-jobs/" + firstJob, HttpMethod.GET, authed(token), Map.class);
        assertThat(firstDone.getBody().get("status")).isEqualTo("done");

        UUID secondJob = createJob(token, "clinic-region-ksa", ownerEmail, "KSA"); // same owner, different region
        ResponseEntity<Map> resp = exchange("/v1/platform/provisioning-jobs/" + secondJob + "/advance",
                HttpMethod.POST, authed(token), Map.class);

        assertThat(resp.getBody().get("status")).isEqualTo("rolled_back");
        assertThat(resp.getBody().get("createdTenantId")).isNull();
        List<Map<String, Object>> steps = (List<Map<String, Object>>) resp.getBody().get("steps");
        assertThat(steps.get(0).get("status")).isEqualTo("failed");
        assertThat(steps.get(0).get("errorDetail")).asString().contains("region");

        Integer tenantCount = jdbc.queryForObject(
                "SELECT count(*) FROM tenants WHERE slug = ?::citext", Integer.class, "clinic-region-ksa");
        assertThat(tenantCount).isEqualTo(0);
    }

    /**
     * The only fatal check today (the region lock) always fires on create_tenant, the very first
     * step, so no real job ever reaches rollback with an earlier step already 'done' to undo. This
     * exercises that exact path directly against the step runner — the same mechanism a future
     * second fatal check on a later step would rely on — rather than leaving it unverified.
     */
    @Test
    void undoingASeededTenantRemovesTheRoleTenantBrandAndOwnerItCreated() {
        String token = superAdminToken();
        UUID jobId = createJob(token, "clinic-undo", "undo-owner@nabd.health", "IN");
        exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance", HttpMethod.POST, authed(token), Map.class); // create_tenant
        exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance", HttpMethod.POST, authed(token), Map.class); // migrate_schema
        exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance", HttpMethod.POST, authed(token), Map.class); // seed_masters

        Job job = provisioningRepo.findJob(jobId).orElseThrow();
        assertThat(job.createdTenantId()).isNotNull();
        assertThat(job.ownerNewlyCreated()).isTrue();
        assertThat(job.brandNewlyCreated()).isTrue();
        UUID tenantId = job.createdTenantId();
        UUID ownerId = job.createdOwnerId();
        UUID brandId = job.createdBrandId();

        stepRunner.undo(job, "seed_masters");
        stepRunner.undo(job, "create_tenant");

        Integer tenantCount = jdbc.queryForObject("SELECT count(*) FROM tenants WHERE id = ?", Integer.class, tenantId);
        Integer brandCount = jdbc.queryForObject("SELECT count(*) FROM brands WHERE id = ?", Integer.class, brandId);
        Integer ownerCount = jdbc.queryForObject("SELECT count(*) FROM owners WHERE id = ?", Integer.class, ownerId);
        assertThat(tenantCount).isEqualTo(0);
        assertThat(brandCount).isEqualTo(0);
        assertThat(ownerCount).isEqualTo(0);
    }

    @Test
    void undoingCreateTenantNeverDeletesAPreExistingOwnerOrBrand() {
        String token = superAdminToken();
        String sharedEmail = "shared-owner@nabd.health";

        UUID firstJob = createJob(token, "clinic-shared-1", sharedEmail, "IN", "Shared Brand");
        for (int i = 0; i < 3; i++) {
            exchange("/v1/platform/provisioning-jobs/" + firstJob + "/advance", HttpMethod.POST, authed(token), Map.class);
        }
        Job firstJobState = provisioningRepo.findJob(firstJob).orElseThrow();
        UUID ownerId = firstJobState.createdOwnerId();

        UUID secondJob = createJob(token, "clinic-shared-2", sharedEmail, "IN", "Shared Brand"); // same owner AND brand name -> both pre-existing
        exchange("/v1/platform/provisioning-jobs/" + secondJob + "/advance", HttpMethod.POST, authed(token), Map.class);
        Job secondJobState = provisioningRepo.findJob(secondJob).orElseThrow();
        assertThat(secondJobState.ownerNewlyCreated()).isFalse();
        assertThat(secondJobState.brandNewlyCreated()).isFalse();

        stepRunner.undo(secondJobState, "create_tenant");

        Integer ownerCount = jdbc.queryForObject("SELECT count(*) FROM owners WHERE id = ?", Integer.class, ownerId);
        assertThat(ownerCount).isEqualTo(1); // the owner the first job created must survive the second job's rollback
        Integer secondTenantCount = jdbc.queryForObject(
                "SELECT count(*) FROM tenants WHERE slug = ?::citext", Integer.class, "clinic-shared-2");
        assertThat(secondTenantCount).isEqualTo(0);
    }

    // ---- NB-260: self-serve vs enterprise paths — same engine, only the gate differs ----

    @Test
    void anEnterpriseJobDoesNotAdvanceUntilApproved() {
        String token = superAdminToken();
        UUID jobId = createEnterpriseJob(token, "clinic-ent-1");

        ResponseEntity<Map> stillQueued = exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance",
                HttpMethod.POST, authed(token), Map.class);
        assertThat(stillQueued.getBody().get("status")).isEqualTo("queued");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) stillQueued.getBody().get("steps");
        assertThat(steps).allMatch(s -> "queued".equals(s.get("status"))); // nothing ran — the gate held
    }

    @Test
    void approvingAnEnterpriseJobLetsItRunToCompletion() {
        String token = superAdminToken();
        UUID jobId = createEnterpriseJob(token, "clinic-ent-2");

        ResponseEntity<Map> approved = exchange("/v1/platform/provisioning-jobs/" + jobId + "/approve",
                HttpMethod.POST, authed(token), Map.class);
        assertThat(approved.getBody().get("approvedAt")).isNotNull();

        Map<String, Object> body = null;
        for (int i = 0; i < 6; i++) {
            body = exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance", HttpMethod.POST, authed(token), Map.class).getBody();
        }
        assertThat(body.get("status")).isEqualTo("done");
        assertThat(body.get("createdTenantId")).isNotNull(); // identical end state to a self-serve job (see next test)
    }

    @Test
    void selfServeAndEnterpriseProduceIdenticalTenantStateOnceApproved() {
        String token = superAdminToken();

        UUID selfServeJob = createJob(token, "clinic-path-self");
        Map<String, Object> selfServeResult = null;
        for (int i = 0; i < 6; i++) {
            selfServeResult = exchange("/v1/platform/provisioning-jobs/" + selfServeJob + "/advance", HttpMethod.POST, authed(token), Map.class).getBody();
        }

        UUID enterpriseJob = createEnterpriseJob(token, "clinic-path-ent");
        exchange("/v1/platform/provisioning-jobs/" + enterpriseJob + "/approve", HttpMethod.POST, authed(token), Map.class);
        Map<String, Object> enterpriseResult = null;
        for (int i = 0; i < 6; i++) {
            enterpriseResult = exchange("/v1/platform/provisioning-jobs/" + enterpriseJob + "/advance", HttpMethod.POST, authed(token), Map.class).getBody();
        }

        assertThat(enterpriseResult.get("status")).isEqualTo(selfServeResult.get("status")).isEqualTo("done");
        List<Map<String, Object>> selfServeSteps = (List<Map<String, Object>>) selfServeResult.get("steps");
        List<Map<String, Object>> enterpriseSteps = (List<Map<String, Object>>) enterpriseResult.get("steps");
        assertThat(enterpriseSteps.stream().map(s -> s.get("status")).toList())
                .isEqualTo(selfServeSteps.stream().map(s -> s.get("status")).toList());

        UUID enterpriseTenantId = UUID.fromString((String) enterpriseResult.get("createdTenantId"));
        String tenantStatus = jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, enterpriseTenantId);
        assertThat(tenantStatus).isEqualTo("trialing"); // same tenant state a self-serve job produces
    }

    @Test
    void approvingASelfServeJobIsRejected() {
        String token = superAdminToken();
        UUID jobId = createJob(token, "clinic-self-approve");

        ResponseEntity<Map> resp = exchange("/v1/platform/provisioning-jobs/" + jobId + "/approve",
                HttpMethod.POST, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listAndGetRequireTheProvisioningAuthorityToo() {
        SeededOperator sre = seedOperator("sre-prov@nabd.health", "sre", false);
        String token = platformLoginAndGetAccessToken(sre);
        assertThat(exchange("/v1/platform/provisioning-jobs", HttpMethod.GET, authed(token), List.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private UUID createJob(String token, String slug) {
        return createJob(token, slug, "owner-" + slug + "@nabd.health", "IN");
    }

    private UUID createJob(String token, String slug, String ownerEmail, String region) {
        return createJob(token, slug, ownerEmail, region, "Test Brand " + slug);
    }

    private UUID createJob(String token, String slug, String ownerEmail, String region, String brandName) {
        ResponseEntity<Map> resp = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST,
                authedJsonBody(token, jobRequest(slug, ownerEmail, region, brandName)), Map.class);
        return UUID.fromString((String) resp.getBody().get("id"));
    }

    private UUID createEnterpriseJob(String token, String slug) {
        ResponseEntity<Map> resp = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST,
                authedJsonBody(token, jobRequest(slug, "owner-" + slug + "@nabd.health", "IN", "Test Brand " + slug, "enterprise")),
                Map.class);
        return UUID.fromString((String) resp.getBody().get("id"));
    }

    private String superAdminToken() {
        SeededOperator operator = seedOperator("super-prov-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        return platformLoginAndGetAccessToken(operator);
    }

    private Map<String, String> jobRequest(String slug) {
        return jobRequest(slug, "owner-" + slug + "@nabd.health", "IN", "Test Brand " + slug);
    }

    private Map<String, String> jobRequest(String slug, String ownerEmail, String region, String brandName) {
        return jobRequest(slug, ownerEmail, region, brandName, "self_serve");
    }

    private Map<String, String> jobRequest(String slug, String ownerEmail, String region, String brandName, String path) {
        return Map.of(
                "tenantSlug", slug,
                "tenantName", "Test Clinic " + slug,
                "region", region,
                "ownerEmail", ownerEmail,
                "ownerName", "Test Owner",
                "ownerMobile", "+9198765" + Math.abs(slug.hashCode() % 100000),
                "brandName", brandName,
                "path", path
        );
    }
}

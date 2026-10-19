package com.nabd.hms.platform.provisioning;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** OPS-048 AC4: while the Phase 0 gate has not passed, the provisioning flow creates and advances no tenant. */
@TestPropertySource(properties = "app.provisioning.real-data-onboarding=blocked")
class ProvisioningRealDataGateApiTest extends ApiTestBase {

    @Autowired
    private ProvisioningRepository provisioningRepo;

    private SeededOperator superAdmin() {
        return seedOperator("super-gate-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
    }

    private String superAdminToken() {
        return platformLoginAndGetAccessToken(superAdmin());
    }

    @Test
    void createIsRefusedWith409ProblemAndNoJobIsQueued() {
        String slug = "gate-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> resp = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(superAdminToken(), Map.of(
                "tenantSlug", slug, "tenantName", "Gate Clinic", "region", "IN", "ownerEmail", "owner-" + slug + "@nabd.health",
                "ownerName", "Gate Owner", "ownerMobile", "+919876500001", "brandName", "Gate Brand", "path", "self_serve")), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(resp.getBody().get("type"))).contains("real-data-onboarding-blocked");
        assertThat(provisioningRepo.listJobs()).noneMatch(j -> slug.equals(j.tenantSlug()));
    }

    @Test
    void aJobQueuedBeforeTheBlockCannotAdvanceToCreateTheTenant() {
        UUID jobId = UUID.randomUUID();
        SeededOperator op = superAdmin();
        provisioningRepo.insertJob(jobId, op.id(), "gate-old-" + jobId.toString().substring(0, 6), "Old Clinic", "IN",
                "old-" + jobId + "@nabd.health", "Old Owner", "+919876500002", "Old Brand", "self_serve");
        provisioningRepo.insertStep(UUID.randomUUID(), jobId, "create_tenant", 1);
        ResponseEntity<Map> resp = exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance", HttpMethod.POST,
                authedJsonBody(platformLoginAndGetAccessToken(op), Map.of()), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(provisioningRepo.findNextIncompleteStep(jobId).orElseThrow().stepName()).isEqualTo("create_tenant");
    }
}

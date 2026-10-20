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

/** Independent QA (S11 OPS-048 AC4): REAL_DATA_ONBOARDING=blocked through the env-variable name, end to end over HTTP. */
@TestPropertySource(properties = "REAL_DATA_ONBOARDING=blocked")
class S11QaOps048ProvisioningBlockedTest extends ApiTestBase {

    @Autowired
    private ProvisioningRepository provisioningRepo;

    private String token() {
        return platformLoginAndGetAccessToken(seedOperator("s11qa-gate-" + UUID.randomUUID() + "@nabd.health", "super_admin", false));
    }

    private Map<String, Object> job(String slug) {
        return Map.of("tenantSlug", slug, "tenantName", "QA Gate Clinic", "region", "IN", "ownerEmail", "owner-" + slug + "@nabd.health",
                "ownerName", "QA Owner", "ownerMobile", "+919876500011", "brandName", "QA Brand", "path", "self_serve");
    }

    @Test
    void createIs409ProblemJsonAndNothingIsQueued() {
        String slug = "qa5-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<String> resp = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(token(), job(slug)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(resp.getHeaders().getContentType())).contains("application/problem+json");
        assertThat(resp.getBody()).contains("real-data-onboarding-blocked").contains("Phase 0 exit gate");
        assertThat(provisioningRepo.listJobs()).noneMatch(j -> slug.equals(j.tenantSlug()));
    }

    @Test
    void readingJobsStillWorksWhileBlocked() {
        ResponseEntity<String> resp = exchange("/v1/platform/provisioning-jobs", HttpMethod.GET, authed(token()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void withoutAuthTheSecurityLayerAnswersFirst() {
        ResponseEntity<String> resp = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST,
                authedJsonBody("not-a-token", job("qa5-anon")), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}

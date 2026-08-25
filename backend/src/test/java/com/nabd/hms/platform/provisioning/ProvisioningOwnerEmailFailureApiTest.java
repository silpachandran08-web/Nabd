package com.nabd.hms.platform.provisioning;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NB-353: a real production incident — Render blocks outbound SMTP, so EmailSender.send() failed
 * every time, and because it ran inside the same @Transactional step as the staff insert, the
 * owner's account was silently rolled back along with it. Forces spring.mail.host to an unreachable
 * address so SmtpEmailSender (the real class, not a stub) genuinely fails here the same way it did
 * on Render, proving the fix: the staff row and the step's "done" status must survive that failure.
 */
class ProvisioningOwnerEmailFailureApiTest extends ApiTestBase {

    @DynamicPropertySource
    static void unreachableSmtp(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> "1"); // refused instantly, no need to wait out a timeout
    }

    @Test
    void aFailingEmailSendDoesNotRollBackTheOwnerStaffRow() {
        SeededOperator operator = seedOperator("super-mailfail-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        String token = platformLoginAndGetAccessToken(operator);

        ResponseEntity<Map> created = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST,
                authedJsonBody(token, Map.of(
                        "tenantSlug", "clinic-mailfail",
                        "tenantName", "Test Clinic",
                        "region", "IN",
                        "ownerEmail", "owner-mailfail@nabd.health",
                        "ownerName", "Test Owner",
                        "ownerMobile", "+919876500000",
                        "brandName", "Test Brand Mailfail",
                        "path", "self_serve"
                )), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID jobId = UUID.fromString((String) created.getBody().get("id"));

        Map<String, Object> body = null;
        for (int i = 0; i < 5; i++) { // through verify_invite_owner
            body = exchange("/v1/platform/provisioning-jobs/" + jobId + "/advance", HttpMethod.POST, authed(token), Map.class).getBody();
        }
        List<Map<String, Object>> steps = (List<Map<String, Object>>) body.get("steps");
        Map<String, Object> inviteStep = steps.stream().filter(s -> "verify_invite_owner".equals(s.get("stepName"))).findFirst().orElseThrow();
        assertThat(inviteStep.get("status")).isEqualTo("done"); // not "failed" — the email exception must not have escaped the step

        UUID tenantId = UUID.fromString((String) body.get("createdTenantId"));
        Integer staffCount = inTenantTx(tenantId, () -> jdbc.queryForObject(
                "SELECT count(*) FROM staff WHERE tenant_id = ?", Integer.class, tenantId));
        assertThat(staffCount).isEqualTo(1);
    }
}

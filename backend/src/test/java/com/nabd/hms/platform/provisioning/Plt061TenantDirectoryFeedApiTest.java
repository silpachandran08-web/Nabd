package com.nabd.hms.platform.provisioning;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-061, Core's half of the tenant directory feed: a clinic provisioned in the IN cell leaves, in the outbox the relay
 * drains to ev:in:organization, exactly the events and fields the Control Plane's TenantFeed reads
 * (services/control-plane; its TenantFeedTest is the other half). No new event type: both already existed.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class Plt061TenantDirectoryFeedApiTest extends ApiTestBase {

    private final JdbcTemplate su = new JdbcTemplate(superuserDataSource());

    @Test
    void aClinicCreatedInTheInCellPublishesWhatTheControlPlaneDirectoryNeeds() throws Exception {
        String token = platformLoginAndGetAccessToken(seedOperator("super-plt061-" + UUID.randomUUID() + "@nabd.health", "super_admin", false));
        String slug = "cp-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> created = exchange("/v1/platform/provisioning-jobs", HttpMethod.POST, authedJsonBody(token, Map.of(
                "tenantSlug", slug, "tenantName", "Directory Clinic", "region", "IN", "ownerEmail", "owner-" + slug + "@nabd.health",
                "ownerName", "Test Owner", "ownerMobile", "98450 22110", "brandName", "Brand " + slug, "path", "self_serve")), Map.class);
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = null;
        for (int i = 0; i < 6; i++) {
            body = exchange("/v1/platform/provisioning-jobs/" + created.getBody().get("id") + "/advance", HttpMethod.POST, authed(token), Map.class).getBody();
        }
        assertThat(body.get("status")).isEqualTo("done");
        UUID tenantId = UUID.fromString((String) body.get("createdTenantId"));

        List<String> envelopes = su.queryForList("SELECT envelope::text FROM outbox_cloudevents WHERE tenant_id = ? "
                + "AND envelope ->> 'type' IN ('organization.tenant.status_changed.v1', 'organization.tenant.region_pinned.v1') "
                + "ORDER BY (envelope -> 'data' ->> 'version')::bigint", String.class, tenantId);
        assertThat(envelopes).hasSize(2);
        Map status = objectMapper.readValue(envelopes.get(0), Map.class);
        Map pinned = objectMapper.readValue(envelopes.get(1), Map.class);
        for (Map e : List.of(status, pinned)) {
            // stream = ev:<region>:<first segment of type> (events.md section 2): the one the Control Plane reads
            assertThat(e).containsEntry("region", "in").containsEntry("tenantid", tenantId.toString());
            assertThat(((Map) e.get("data")).get("version")).isInstanceOf(Number.class);
        }
        assertThat(status).containsEntry("type", "organization.tenant.status_changed.v1");
        assertThat((Map) status.get("data")).containsEntry("from", "provisioning").containsEntry("to", "trialing");
        assertThat(pinned).containsEntry("type", "organization.tenant.region_pinned.v1");
        assertThat((Map) pinned.get("data")).containsEntry("regionCode", "IN").containsEntry("cell", "in");
    }
}

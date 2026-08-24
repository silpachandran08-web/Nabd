package com.nabd.hms.platform.plans;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlanApiTest extends ApiTestBase {

    @Test
    void billingRoleCanCreateListAndUpdateAPlan() {
        String token = platformLoginAndGetAccessToken(seedOperator("billing1@nabd.internal", "billing", false));
        String code = "growth-" + UUID.randomUUID();

        ResponseEntity<Map> created = exchange("/v1/platform/plans", HttpMethod.POST,
                authedJsonBody(token, Map.of("code", code, "name", "Growth", "monthlyPriceCents", 500000,
                        "currency", "INR", "seatLimit", 10, "active", true)), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) created.getBody().get("id");

        ResponseEntity<List> list = exchange("/v1/platform/plans", HttpMethod.GET, authed(token), List.class);
        assertThat(list.getBody()).extracting(o -> ((Map) o).get("code")).contains(code);

        ResponseEntity<Map> updated = exchange("/v1/platform/plans/" + id, HttpMethod.PATCH,
                authedJsonBody(token, Map.of("code", code, "name", "Growth Plus", "monthlyPriceCents", 600000,
                        "currency", "INR", "seatLimit", 15, "active", true)), Map.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().get("name")).isEqualTo("Growth Plus");
        assertThat(updated.getBody().get("seatLimit")).isEqualTo(15);
    }

    @Test
    void duplicatePlanCodeIsRejectedWithConflict() {
        String token = platformLoginAndGetAccessToken(seedOperator("billing2@nabd.internal", "billing", false));
        Map<String, Object> body = Map.of("code", "starter-" + UUID.randomUUID(), "name", "Starter", "monthlyPriceCents", 200000,
                "currency", "INR", "seatLimit", 5, "active", true);

        exchange("/v1/platform/plans", HttpMethod.POST, authedJsonBody(token, body), Map.class);
        ResponseEntity<Map> dupe = exchange("/v1/platform/plans", HttpMethod.POST, authedJsonBody(token, body), Map.class);

        assertThat(dupe.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void roleWithoutPricingPackagingIsForbidden() {
        String token = platformLoginAndGetAccessToken(seedOperator("support1@nabd.internal", "support_engineer", false));

        ResponseEntity<Map> resp = exchange("/v1/platform/plans", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}

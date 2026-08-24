package com.nabd.hms.platform.territory;

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

class TerritoryApiTest extends ApiTestBase {

    @Test
    void regionsWithNoClinicsStillAppearAsACoverageGap() {
        String token = platformLoginAndGetAccessToken(seedOperator("sre1@nabd.internal", "sre", false));
        // No KSA tenant seeded anywhere in this test run's data.

        ResponseEntity<List> resp = exchange("/v1/platform/territories", HttpMethod.GET, authed(token), List.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).extracting(o -> ((Map) o).get("region")).contains("IN", "KSA");
        Map ksa = (Map) resp.getBody().stream().filter(o -> ((Map) o).get("region").equals("KSA")).findFirst().orElseThrow();
        assertThat(ksa.get("clinicCount")).isEqualTo(0);
        assertThat(ksa.get("currency")).isEqualTo("SAR");
    }

    @Test
    void regionRollupAggregatesClinicsUsersMrrAndPlanMix() {
        SeededTenant tenant = seedTenant(); // region IN
        jdbc.update("UPDATE tenants SET tax_id_type = 'GSTIN' WHERE id = ?", tenant.id());
        UUID roleId = seedFullAccessRole(tenant.id());
        seedStaff(tenant, roleId, "u1@acme.health", "+911111100001", false);
        seedStaff(tenant, roleId, "u2@acme.health", "+911111100002", false);
        UUID planId = seedPlan("region-plan-" + tenant.slug(), 300000, "INR", 5);
        String billingToken = platformLoginAndGetAccessToken(seedOperator("billing12@nabd.internal", "billing", false));
        exchange("/v1/platform/billing/subscriptions/" + tenant.id(), HttpMethod.POST,
                authedJsonBody(billingToken, Map.of("planId", planId.toString(), "mrrCents", 300000,
                        "renewalDate", LocalDate.now().plusDays(30).toString())),
                Map.class);

        String sreToken = platformLoginAndGetAccessToken(seedOperator("sre2@nabd.internal", "sre", false));
        ResponseEntity<List> resp = exchange("/v1/platform/territories", HttpMethod.GET, authed(sreToken), List.class);

        Map in = (Map) resp.getBody().stream().filter(o -> ((Map) o).get("region").equals("IN")).findFirst().orElseThrow();
        assertThat((Integer) in.get("clinicCount")).isGreaterThanOrEqualTo(1);
        assertThat((Integer) in.get("userCount")).isGreaterThanOrEqualTo(2);
        assertThat((Integer) in.get("mrrCents")).isGreaterThanOrEqualTo(300000);
        assertThat((List) in.get("taxIdTypes")).contains("GSTIN");
    }

    @Test
    void roleWithoutTerritoriesIsForbidden() {
        String token = platformLoginAndGetAccessToken(seedOperator("billing13@nabd.internal", "billing", false));

        ResponseEntity<Map> resp = exchange("/v1/platform/territories", HttpMethod.GET, authed(token), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}

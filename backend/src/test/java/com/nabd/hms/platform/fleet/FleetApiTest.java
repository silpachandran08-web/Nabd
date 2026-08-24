package com.nabd.hms.platform.fleet;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FleetApiTest extends ApiTestBase {

    @Test
    void listReturnsTenantsWithBrandAndOwnerNames() {
        SeededOwner owner = seedOwner("fleet-owner@nabd.health");
        SeededBrand brand = seedBrand(owner, "Fleet Brand");
        SeededTenant tenant = seedClinicInBrand(brand);
        String token = superAdminToken();

        ResponseEntity<Map> resp = exchange("/v1/platform/tenants?limit=100", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.getBody().get("data");
        Map<String, Object> row = rows.stream().filter(r -> tenant.id().toString().equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(row.get("slug")).isEqualTo(tenant.slug());
        assertThat(row.get("region")).isEqualTo("IN");
        assertThat(row.get("status")).isEqualTo("active");
        assertThat(row.get("brandName")).isEqualTo("Fleet Brand");
        assertThat(row.get("ownerName")).isEqualTo("Test Owner");
        assertThat(row.get("ownerEmail")).isEqualTo("fleet-owner@nabd.health");
    }

    @Test
    void aTenantWithNoBrandStillAppearsWithNullBrandAndOwnerFields() {
        SeededTenant tenant = seedTenant(); // no brand_id — matches every pre-provisioning-flow seed path
        String token = superAdminToken();

        ResponseEntity<Map> resp = exchange("/v1/platform/tenants?limit=200", HttpMethod.GET, authed(token), Map.class);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.getBody().get("data");
        Map<String, Object> row = rows.stream().filter(r -> tenant.id().toString().equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(row.get("brandName")).isNull();
        assertThat(row.get("ownerName")).isNull();
    }

    @Test
    void consecutivePagesNeverOverlap() {
        // The fleet list is deliberately global, not tenant-scoped — other tests in this class share
        // the same table, so this only asserts the pagination mechanic (no row repeats across pages),
        // not an exact count starting from a clean slate.
        seedTenant();
        seedTenant();
        seedTenant();
        String token = superAdminToken();

        ResponseEntity<Map> page1 = exchange("/v1/platform/tenants?limit=2", HttpMethod.GET, authed(token), Map.class);
        List<Map<String, Object>> firstRows = (List<Map<String, Object>>) page1.getBody().get("data");
        assertThat(firstRows).hasSize(2);
        Map<String, Object> pageMeta = (Map<String, Object>) page1.getBody().get("page");
        String cursor = (String) pageMeta.get("nextCursor");
        assertThat(cursor).isNotNull();

        ResponseEntity<Map> page2 = exchange("/v1/platform/tenants?limit=2&cursor=" + cursor, HttpMethod.GET, authed(token), Map.class);
        List<Map<String, Object>> secondRows = (List<Map<String, Object>>) page2.getBody().get("data");

        Set<String> firstIds = firstRows.stream().map(r -> (String) r.get("id")).collect(java.util.stream.Collectors.toSet());
        Set<String> secondIds = secondRows.stream().map(r -> (String) r.get("id")).collect(java.util.stream.Collectors.toSet());
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
    }

    @Test
    void everySeededTenantAppearsInAFullListing() {
        SeededTenant t1 = seedTenant();
        SeededTenant t2 = seedTenant();
        SeededTenant t3 = seedTenant();
        String token = superAdminToken();

        ResponseEntity<Map> resp = exchange("/v1/platform/tenants?limit=1000", HttpMethod.GET, authed(token), Map.class);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.getBody().get("data");
        Set<String> ids = rows.stream().map(r -> (String) r.get("id")).collect(java.util.stream.Collectors.toSet());
        assertThat(ids).contains(t1.id().toString(), t2.id().toString(), t3.id().toString());
    }

    @Test
    void sreCannotSeeTheFleetButEveryOtherRoleCan() {
        seedTenant();
        SeededOperator sre = seedOperator("sre-fleet@nabd.health", "sre", false);
        String sreToken = platformLoginAndGetAccessToken(sre);
        ResponseEntity<Map> resp = exchange("/v1/platform/tenants", HttpMethod.GET, authed(sreToken), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        for (String role : new String[]{"super_admin", "implementation", "support_engineer", "billing", "commercial", "compliance_dpo"}) {
            SeededOperator op = seedOperator(role + "-fleet@nabd.health", role, false);
            String token = platformLoginAndGetAccessToken(op);
            ResponseEntity<Map> allowed = exchange("/v1/platform/tenants", HttpMethod.GET, authed(token), Map.class);
            assertThat(allowed.getStatusCode()).as("role " + role).isEqualTo(HttpStatus.OK);
        }
    }

    private String superAdminToken() {
        SeededOperator operator = seedOperator("super-fleet-" + UUID.randomUUID() + "@nabd.health", "super_admin", false);
        return platformLoginAndGetAccessToken(operator);
    }
}

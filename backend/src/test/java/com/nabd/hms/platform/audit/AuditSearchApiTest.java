package com.nabd.hms.platform.audit;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AuditSearchApiTest extends ApiTestBase {

    @Autowired
    AuditService auditService;

    @Test
    void searchReturnsEntriesAcrossMultipleTenantsByDefault() {
        SeededTenant tenantA = seedTenant();
        SeededTenant tenantB = seedTenant();
        auditService.record(tenantA.id(), "system", null, "Nabd System", "System", null,
                "patient.create", "patient", UUID.randomUUID(), null, Map.of("x", 1));
        auditService.record(tenantB.id(), "system", null, "Nabd System", "System", null,
                "patient.create", "patient", UUID.randomUUID(), null, Map.of("x", 2));

        String token = complianceToken();
        ResponseEntity<Map> resp = exchange("/v1/platform/audit-log?limit=100", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.getBody().get("data");
        Set<String> tenantIds = rows.stream().map(r -> (String) r.get("tenantId")).collect(Collectors.toSet());
        assertThat(tenantIds).contains(tenantA.id().toString(), tenantB.id().toString());
    }

    @Test
    void filteringByTenantIdNarrowsToThatTenantOnly() {
        SeededTenant tenantA = seedTenant();
        SeededTenant tenantB = seedTenant();
        auditService.record(tenantA.id(), "system", null, "Nabd System", "System", null,
                "invoice.create", "invoice", UUID.randomUUID(), null, Map.of("amount", 10));
        auditService.record(tenantB.id(), "system", null, "Nabd System", "System", null,
                "invoice.create", "invoice", UUID.randomUUID(), null, Map.of("amount", 20));

        String token = complianceToken();
        ResponseEntity<Map> resp = exchange("/v1/platform/audit-log?tenantId=" + tenantA.id() + "&limit=100",
                HttpMethod.GET, authed(token), Map.class);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) resp.getBody().get("data");
        assertThat(rows).isNotEmpty();
        assertThat(rows).allMatch(r -> tenantA.id().toString().equals(r.get("tenantId")));
    }

    @Test
    void beforeAndAfterComeBackAsParsedJsonNotAnEscapedString() {
        SeededTenant tenant = seedTenant();
        UUID entityId = UUID.randomUUID();
        auditService.record(tenant.id(), "system", null, "Nabd System", "System", null,
                "patient.update", "patient", entityId, Map.of("name", "old"), Map.of("name", "new"));

        String token = complianceToken();
        ResponseEntity<Map> resp = exchange("/v1/platform/audit-log?tenantId=" + tenant.id(), HttpMethod.GET, authed(token), Map.class);
        Map<String, Object> row = ((List<Map<String, Object>>) resp.getBody().get("data")).get(0);
        assertThat(row.get("after")).isInstanceOf(Map.class);
        assertThat(((Map<String, Object>) row.get("after")).get("name")).isEqualTo("new");
    }

    @Test
    void rolesWithoutAuditComplianceViewAreForbidden() {
        SeededOperator billing = seedOperator("audit-billing@nabd.health", "billing", false);
        String token = platformLoginAndGetAccessToken(billing);
        ResponseEntity<Map> resp = exchange("/v1/platform/audit-log", HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void consecutivePagesNeverOverlap() {
        SeededTenant tenant = seedTenant();
        for (int i = 0; i < 3; i++) {
            auditService.record(tenant.id(), "system", null, "Nabd System", "System", null,
                    "patient.create", "patient", UUID.randomUUID(), null, Map.of("i", i));
        }

        String token = complianceToken();
        ResponseEntity<Map> page1 = exchange("/v1/platform/audit-log?tenantId=" + tenant.id() + "&limit=2",
                HttpMethod.GET, authed(token), Map.class);
        List<Map<String, Object>> firstRows = (List<Map<String, Object>>) page1.getBody().get("data");
        assertThat(firstRows).hasSize(2);
        String cursor = (String) ((Map<String, Object>) page1.getBody().get("page")).get("nextCursor");
        assertThat(cursor).isNotNull();

        ResponseEntity<Map> page2 = exchange("/v1/platform/audit-log?tenantId=" + tenant.id() + "&limit=2&cursor=" + cursor,
                HttpMethod.GET, authed(token), Map.class);
        List<Map<String, Object>> secondRows = (List<Map<String, Object>>) page2.getBody().get("data");
        assertThat(secondRows).hasSize(1);

        Set<Object> firstIds = firstRows.stream().map(r -> r.get("id")).collect(Collectors.toSet());
        Set<Object> secondIds = secondRows.stream().map(r -> r.get("id")).collect(Collectors.toSet());
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        assertThat(((Map<String, Object>) page2.getBody().get("page")).get("nextCursor")).isNull();
    }

    private String complianceToken() {
        SeededOperator op = seedOperator("audit-dpo-" + UUID.randomUUID() + "@nabd.health", "compliance_dpo", false);
        return platformLoginAndGetAccessToken(op);
    }
}

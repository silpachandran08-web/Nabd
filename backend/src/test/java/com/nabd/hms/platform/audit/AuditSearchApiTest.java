package com.nabd.hms.platform.audit;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    // ---- PLT-028: date range, actor filter, audited CSV export ----

    @Test
    void createdAfterAndCreatedBeforeAreHonoured() {
        SeededTenant tenant = seedTenant();
        auditService.record(tenant.id(), "system", null, "Nabd System", "System", null,
                "patient.create", "patient", UUID.randomUUID(), null, Map.of("x", 1));
        String token = complianceToken();
        Instant now = Instant.now();
        String base = "/v1/platform/audit-log?tenantId=" + tenant.id();

        assertThat(rows(token, base + "&createdAfter=" + now.plus(1, ChronoUnit.HOURS))).isEmpty();
        assertThat(rows(token, base + "&createdBefore=" + now.minus(1, ChronoUnit.HOURS))).isEmpty();
        assertThat(rows(token, base + "&createdAfter=" + now.minus(1, ChronoUnit.HOURS)
                + "&createdBefore=" + now.plus(1, ChronoUnit.HOURS))).hasSize(1);
    }

    @Test
    void createdAfterLaterThanCreatedBeforeIsBadRequest() {
        Instant now = Instant.now();
        ResponseEntity<Map> resp = exchange("/v1/platform/audit-log?createdAfter=" + now
                        + "&createdBefore=" + now.minus(1, ChronoUnit.DAYS),
                HttpMethod.GET, authed(complianceToken()), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void actorFiltersNarrowByIdTypeRoleAndName() {
        SeededTenant tenant = seedTenant();
        UUID staffActor = UUID.randomUUID();
        UUID operatorActor = UUID.randomUUID();
        auditService.record(tenant.id(), "staff", staffActor, "Dr Actorfilter Test", "Doctor", null,
                "patient.create", "patient", UUID.randomUUID(), null, Map.of("x", 1));
        auditService.record(tenant.id(), "operator", operatorActor, "Ops Person", "super_admin", null,
                "support_access.grant", "tenant", tenant.id(), null, Map.of("x", 2));
        auditService.record(tenant.id(), "system", null, "Nabd System", "System", null,
                "patient.create", "patient", UUID.randomUUID(), null, Map.of("x", 3));
        String token = complianceToken();
        String base = "/v1/platform/audit-log?limit=100&tenantId=" + tenant.id();

        List<Map<String, Object>> byId = rows(token, base + "&actorId=" + staffActor);
        assertThat(byId).hasSize(1);
        assertThat(byId.get(0).get("actorId")).isEqualTo(staffActor.toString());

        List<Map<String, Object>> byType = rows(token, base + "&actorType=operator");
        assertThat(byType).hasSize(1);
        assertThat(byType.get(0).get("actorId")).isEqualTo(operatorActor.toString());

        List<Map<String, Object>> byRole = rows(token, base + "&actorRole=doctor"); // case-insensitive
        assertThat(byRole).hasSize(1);
        assertThat(byRole.get(0).get("actorName")).isEqualTo("Dr Actorfilter Test");

        List<Map<String, Object>> byName = rows(token, base + "&actorName=ACTORFILTER"); // substring, case-insensitive
        assertThat(byName).hasSize(1);
        assertThat(byName.get(0).get("actorId")).isEqualTo(staffActor.toString());

        assertThat(rows(token, base + "&actorName=_")).isEmpty(); // a literal "_" is not a LIKE wildcard
        assertThat(rows(token, base)).hasSize(3); // filters are additive; none given = everything
    }

    @Test
    void unknownActorTypeIsBadRequest() {
        ResponseEntity<Map> resp = exchange("/v1/platform/audit-log?actorType=robot", HttpMethod.GET,
                authed(complianceToken()), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void exportReturnsCsvOfTheFilteredRowsAndWritesAnAuditEventIntoThatTenantsTrail() {
        SeededTenant tenant = seedTenant();
        SeededTenant other = seedTenant();
        auditService.record(tenant.id(), "system", null, "Nabd System", "System", null,
                "invoice.create", "invoice", UUID.randomUUID(), null, Map.of("amount", 10));
        auditService.record(tenant.id(), "system", null, "Nabd System", "System", null,
                "invoice.create", "invoice", UUID.randomUUID(), null, Map.of("amount", 20));
        auditService.record(other.id(), "system", null, "Nabd System", "System", null,
                "invoice.create", "invoice", UUID.randomUUID(), null, Map.of("amount", 30));
        SeededOperator op = seedOperator("audit-export-" + UUID.randomUUID() + "@nabd.health", "compliance_dpo", false);
        String token = platformLoginAndGetAccessToken(op);

        ResponseEntity<String> resp = exchange("/v1/platform/audit-log/export?tenantId=" + tenant.id()
                + "&action=invoice.create", HttpMethod.GET, authed(token), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment").contains(".csv");
        String[] lines = resp.getBody().split("\n");
        assertThat(lines[0]).startsWith("id,createdAt,tenantId,tenantName");
        assertThat(lines).hasSize(3); // header + this tenant's two rows, not the other tenant's
        assertThat(resp.getBody()).doesNotContain(other.id().toString());

        Map<String, Object> audited = inTenantTx(tenant.id(), () -> jdbc.queryForMap(
                "SELECT actor_type, actor_id, actor_role, entity_type, after::text AS after FROM audit_log " +
                        "WHERE tenant_id = ? AND action = 'audit_log.export' ORDER BY id DESC LIMIT 1",
                tenant.id()));
        assertThat(audited.get("actor_type")).isEqualTo("operator");
        assertThat(audited.get("actor_id").toString()).isEqualTo(op.id().toString());
        assertThat(audited.get("actor_role")).isEqualTo("compliance_dpo");
        assertThat(audited.get("entity_type")).isEqualTo("audit_log");
        assertThat(audited.get("after").toString()).contains("\"rowCount\": 2").contains("invoice.create");

        Integer otherTenantExports = inTenantTx(other.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'audit_log.export'",
                Integer.class, other.id()));
        assertThat(otherTenantExports).isZero(); // none of its rows were exported
    }

    @Test
    void exportAuditEventDoesNotStoreTheActorNameSearchText() {
        SeededTenant tenant = seedTenant();
        auditService.record(tenant.id(), "staff", UUID.randomUUID(), "Dr Zyxwname", "Doctor", null,
                "patient.create", "patient", UUID.randomUUID(), null, Map.of("x", 1));
        String token = complianceToken();

        ResponseEntity<String> resp = exchange("/v1/platform/audit-log/export?tenantId=" + tenant.id()
                + "&actorName=zyxw", HttpMethod.GET, authed(token), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("Dr Zyxwname");

        String after = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT after::text FROM audit_log WHERE tenant_id = ? AND action = 'audit_log.export' " +
                        "ORDER BY id DESC LIMIT 1", String.class, tenant.id()));
        assertThat(after).contains("actorNameFilter").doesNotContainIgnoringCase("zyxw");
    }

    @Test
    void exportNeutralisesSpreadsheetFormulasInTextColumns() {
        SeededTenant tenant = seedTenant();
        auditService.record(tenant.id(), "staff", UUID.randomUUID(), "=HYPERLINK(\"http://x\")", "Doctor", null,
                "patient.create", "patient", UUID.randomUUID(), null, Map.of("x", 1));

        ResponseEntity<String> resp = exchange("/v1/platform/audit-log/export?tenantId=" + tenant.id(),
                HttpMethod.GET, authed(complianceToken()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"'=HYPERLINK(\"\"http://x\"\")\"");
    }

    @Test
    void exportIsForbiddenWithoutAuditComplianceViewAndWritesNothing() {
        SeededTenant tenant = seedTenant();
        auditService.record(tenant.id(), "system", null, "Nabd System", "System", null,
                "patient.create", "patient", UUID.randomUUID(), null, Map.of("x", 1));
        for (String role : List.of("billing", "support_engineer", "commercial")) {
            SeededOperator op = seedOperator("ax-" + UUID.randomUUID() + "@nabd.health", role, false);
            ResponseEntity<String> resp = exchange("/v1/platform/audit-log/export?tenantId=" + tenant.id(),
                    HttpMethod.GET, authed(platformLoginAndGetAccessToken(op)), String.class);
            assertThat(resp.getStatusCode()).as(role).isEqualTo(HttpStatus.FORBIDDEN);
        }
        Integer exports = inTenantTx(tenant.id(), () -> jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'audit_log.export'",
                Integer.class, tenant.id()));
        assertThat(exports).isZero();
    }

    @Test
    void sreAndSuperAdminMayExport() {
        SeededTenant tenant = seedTenant();
        auditService.record(tenant.id(), "system", null, "Nabd System", "System", null,
                "patient.create", "patient", UUID.randomUUID(), null, Map.of("x", 1));
        for (String role : List.of("sre", "super_admin")) {
            SeededOperator op = seedOperator("ax-" + UUID.randomUUID() + "@nabd.health", role, false);
            ResponseEntity<String> resp = exchange("/v1/platform/audit-log/export?tenantId=" + tenant.id(),
                    HttpMethod.GET, authed(platformLoginAndGetAccessToken(op)), String.class);
            assertThat(resp.getStatusCode()).as(role).isEqualTo(HttpStatus.OK);
        }
    }

    private List<Map<String, Object>> rows(String token, String path) {
        ResponseEntity<Map> resp = exchange(path, HttpMethod.GET, authed(token), Map.class);
        assertThat(resp.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) resp.getBody().get("data");
    }

    private String complianceToken() {
        SeededOperator op = seedOperator("audit-dpo-" + UUID.randomUUID() + "@nabd.health", "compliance_dpo", false);
        return platformLoginAndGetAccessToken(op);
    }
}

package com.nabd.hms.platform.tenant;

import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S25 QA t1 (ADM-020): a suspended clinic is read-only on every module, not only the endpoints the engineer's test happened to try;
 * another clinic and the operator are untouched; a staff token cannot move a tenant.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class S25QaAdm020WriteBlockApiTest extends ApiTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger(25_000);
    private final java.util.Map<UUID, UUID> ownerRole = new java.util.HashMap<>(); // roles are unique by name per clinic: one full-access role per clinic

    private record Probe(HttpMethod method, String path, Object body) {
    }

    private String ownerToken(SeededTenant t) {
        return loginAndGetAccessToken(seedStaff(t, ownerRole.computeIfAbsent(t.id(), this::seedFullAccessRole), "qa25w" + SEQ.incrementAndGet() + "@a.com", "+9197" + (50000000 + SEQ.incrementAndGet() * 11), false));
    }

    @Test
    void suspended_everyWriteOnEveryModuleIs423_otherClinicAndStaffMoveAttemptsAreUnaffected() {
        SeededTenant clinic = seedTenant();
        SeededTenant other = seedTenant();
        String owner = ownerToken(clinic);
        String otherOwner = ownerToken(other);
        // pharmacy routes are in the plan only once the pharmacy is in-house; otherwise they answer 403 MODULE_NOT_IN_PLAN before the suspension is looked at
        assertThat(exchange("/v1/pharmacy/settings", HttpMethod.PATCH, authedJsonBody(owner, Map.of("mode", "in_house", "reason", "QA setup")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String op = platformLoginAndGetAccessToken(seedOperator("qa25w-" + UUID.randomUUID() + "@nabd.health", "super_admin", false));

        // a staff token cannot move a tenant, its own included
        for (String bad : List.of("suspended", "offboarded")) {
            ResponseEntity<Map> r = exchange("/v1/platform/tenants/" + clinic.id() + "/lifecycle/transitions", HttpMethod.POST,
                    authedJsonBody(owner, Map.of("toStatus", bad, "reason", "staff trying to move itself")), Map.class);
            assertThat(r.getStatusCode().value()).as("staff move to %s: %s", bad, r.getBody()).isIn(401, 403);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE id = ?", String.class, clinic.id())).isNotIn("suspended", "offboarded");

        assertThat(exchange("/v1/platform/tenants/" + clinic.id() + "/lifecycle/transitions", HttpMethod.POST,
                authedJsonBody(op, Map.of("toStatus", "suspended", "reason", "QA suspension for the sweep")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String suspendedOwner = loginAndGetAccessToken(seedStaffForLogin(clinic));

        UUID anyId = UUID.randomUUID();
        List<Probe> writes = List.of(
                new Probe(HttpMethod.POST, "/v1/patients", Map.of("name", "QA Person", "phone", "+919800000001", "dob", "1990-01-01", "gender", "male")),
                new Probe(HttpMethod.POST, "/v1/queue/check-in", Map.of()),
                new Probe(HttpMethod.POST, "/v1/billing/otc-checkout", Map.of("lineItems", List.of())),
                new Probe(HttpMethod.POST, "/v1/pharmacy/items", Map.of("name", "X", "price", 1)),
                new Probe(HttpMethod.POST, "/v1/pharmacy/batches/" + anyId + "/quarantine", Map.of("reason", "damaged")),
                new Probe(HttpMethod.POST, "/v1/pharmacy/supplier-returns", Map.of("supplierName", "S", "lines", List.of())),
                new Probe(HttpMethod.POST, "/v1/pharmacy/write-offs", Map.of("method", "lost", "lines", List.of())),
                new Probe(HttpMethod.POST, "/v1/pharmacy/paper-prescriptions", Map.of()),
                new Probe(HttpMethod.POST, "/v1/lab/tests", Map.of()),
                new Probe(HttpMethod.POST, "/v1/lab/panels", Map.of()),
                new Probe(HttpMethod.POST, "/v1/lab/catalogue/imports", Map.of("pack", "IN_STARTER", "dryRun", false)),
                new Probe(HttpMethod.POST, "/v1/packages/sell", Map.of()),
                new Probe(HttpMethod.PATCH, "/v1/packages/settings", Map.of("priceFloorPercent", 0)),
                new Probe(HttpMethod.POST, "/v1/training/completions", Map.of("moduleId", "phi-handling", "score", 100)),
                new Probe(HttpMethod.POST, "/v1/setup/import-jobs", Map.of()),
                new Probe(HttpMethod.POST, "/v1/setup/offboarding", Map.of("waiveExport", true, "reason", "x")),
                new Probe(HttpMethod.POST, "/v1/owner/branches", Map.of()),
                new Probe(HttpMethod.PATCH, "/v1/pharmacy/settings", Map.of("mode", "hybrid", "reason", "x")),
                new Probe(HttpMethod.POST, "/v1/roles", Map.of("name", "R", "grants", List.of())),
                new Probe(HttpMethod.DELETE, "/v1/departments/" + anyId, null));
        List<String> notLocked = new ArrayList<>();
        for (Probe p : writes) {
            ResponseEntity<Map> r = exchange(p.path(), p.method(), p.body() == null ? authed(suspendedOwner) : authedJsonBody(suspendedOwner, p.body()), Map.class);
            if (r.getStatusCode() != HttpStatus.LOCKED) notLocked.add(p.method() + " " + p.path() + " -> " + r.getStatusCode().value());
        }
        assertThat(notLocked).as("writes of a suspended clinic that were not refused with 423").isEmpty();

        // reads and the other clinic are untouched
        assertThat(exchange("/v1/staff", HttpMethod.GET, authed(suspendedOwner), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange("/v1/roles", HttpMethod.POST, authedJsonBody(otherOwner, Map.of("name", "Fine " + UUID.randomUUID().toString().substring(0, 5), "grants", List.of())), Map.class).getStatusCode().value())
                .as("another clinic's write").isNotEqualTo(423);

        // reactivation lifts it on the very next call
        assertThat(exchange("/v1/platform/tenants/" + clinic.id() + "/lifecycle/transitions", HttpMethod.POST,
                authedJsonBody(op, Map.of("toStatus", "active", "reason", "QA reactivation")), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        String back = loginAndGetAccessToken(seedStaffForLogin(clinic));
        assertThat(exchange("/v1/roles", HttpMethod.POST, authedJsonBody(back, Map.of("name", "Back " + UUID.randomUUID().toString().substring(0, 5), "grants", List.of())), Map.class).getStatusCode().value())
                .isNotEqualTo(423);
    }

    private SeededStaff seedStaffForLogin(SeededTenant t) {
        return seedStaff(t, ownerRole.computeIfAbsent(t.id(), this::seedFullAccessRole), "qa25w" + SEQ.incrementAndGet() + "@a.com", "+9197" + (50000000 + SEQ.incrementAndGet() * 11), false);
    }
}

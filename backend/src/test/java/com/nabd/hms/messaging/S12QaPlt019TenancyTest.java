package com.nabd.hms.messaging;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantMismatchException;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S12 QA-3, PLT-019 (independent): binding at transaction start, mismatch + rollback, test-only repository guard,
 * TenantScope for workers, pooled-connection and concurrent leakage attempts, HTTP isolation on the migrated module. */
class S12QaPlt019TenancyTest extends ApiTestBase {

    @Autowired private TenantScope tenantScope;
    @Autowired private AuditService audit;
    @Autowired private WhatsAppTemplateRepository templates;
    @Autowired private WhatsAppTemplateService templateService;

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signInAs(UUID tenantId) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("t").header("alg", "none")
                .subject(UUID.randomUUID().toString()).claim("tenantId", tenantId.toString()).build()));
    }

    private String guc(String name) {
        return jdbc.queryForObject("SELECT current_setting(?, true)", String.class, name);
    }

    private String boundNow() {
        return new TransactionTemplate(txManager).execute(s -> guc("app.tenant_id"));
    }

    private UUID seedTemplate(UUID tenantId, String name) {
        return inTenantTx(tenantId, () -> templates.insert(tenantId, name, "m_" + name, "en", "UTILITY", "Clinic",
                "Visit at {{1}}.", 1, "approved", "meta-" + name));
    }

    private long su(String sql, Object... args) {
        return new JdbcTemplate(superuserDataSource()).queryForObject(sql, Long.class, args);
    }

    private static String n() {
        return "q3" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    // ---------- AC1: RLS repository outside a transaction ----------

    @Test
    void ac1_rlsRepositoryCallsOutsideATransactionThrowIllegalState_andLeaveNoSentinelOnThePool() {
        UUID a = seedTenant().id();
        UUID id = seedTemplate(a, n());
        assertThatThrownBy(() -> templates.list(a)).isInstanceOf(IllegalStateException.class).hasMessageContaining("outside a transaction");
        assertThatThrownBy(() -> templates.find(a, id)).isInstanceOf(IllegalStateException.class);
        // the guard's sentinel must not survive on a pooled connection
        for (int i = 0; i < 10; i++) {
            assertThat(boundNow()).isNullOrEmpty();
        }
        assertThat(tenantScope.run(a, () -> templates.find(a, id))).isPresent();
    }

    @Test
    void ac1_strictGuardIsTestOnly_productionConfigNeverEnablesIt() throws Exception {
        assertThat(Files.readString(Path.of("src/test/resources/application.properties"))).contains("nabd.tenancy.strict-repositories=true");
        for (Path p : Files.list(Path.of("src/main/resources")).filter(f -> f.getFileName().toString().startsWith("application")).toList()) {
            assertThat(Files.readString(p)).as(p.toString()).doesNotContain("strict-repositories");
        }
    }

    // ---------- AC2: auto-bind at transaction start ----------

    @Test
    void ac2_signedInTenantIsBoundAtStart_evenInRequiresNew_andSiblingClinicsAreNotVisible() {
        SeededBrand brand = seedBrand(seedOwner("q3owner" + UUID.randomUUID() + "@a.com"), "QA3 brand " + n());
        UUID a = seedClinicInBrand(brand).id();
        UUID sibling = seedClinicInBrand(brand).id();
        seedTemplate(sibling, n());
        UUID mine = seedTemplate(a, n());
        signInAs(a);
        TransactionTemplate outer = new TransactionTemplate(txManager);
        TransactionTemplate inner = new TransactionTemplate(txManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        String[] seen = outer.execute(s -> {
            String o = guc("app.tenant_id") + "|" + guc("app.visible_tenant_ids");
            String i = inner.execute(s2 -> guc("app.tenant_id") + "|" + guc("app.visible_tenant_ids"));
            return new String[]{o, i};
        });
        assertThat(seen).containsExactly(a + "|" + a, a + "|" + a);
        assertThat(templateService.list()).extracting(WhatsAppTemplateService.TemplateResponse::id).containsExactly(mine);
    }

    @Test
    void ac2_pooledConnectionsNeverCarryTheLastTenantIntoAnUnboundTransaction() {
        UUID a = seedTenant().id();
        for (int i = 0; i < 15; i++) {
            signInAs(a);
            assertThat(boundNow()).isEqualTo(a.toString());
            SecurityContextHolder.clearContext();
            assertThat(boundNow()).isNullOrEmpty();
        }
    }

    @Test
    void ac2_http_migratedTemplateEndpointsAreTenantScopedWithoutAnExplicitTenantId() {
        SeededTenant ta = seedTenant();
        SeededTenant tb = seedTenant();
        String tokenA = loginAndGetAccessToken(seedStaff(ta, seedFullAccessRole(ta.id()), "q3a" + ta.id() + "@a.com", null, false));
        UUID mineId = seedTemplate(ta.id(), n());
        String theirName = n();
        UUID theirsId = inTenantTx(tb.id(), () -> templates.insert(tb.id(), theirName, "m_" + theirName, "en", "UTILITY", "Clinic",
                "Visit at {{1}}.", 1, "rejected", "meta-" + theirName)); // editable by its own tenant: a leak would 200
        ResponseEntity<List> list = exchange("/v1/setup/whatsapp-templates", HttpMethod.GET, authed(tokenA), List.class);
        assertThat(list.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(list.getBody().stream().map(x -> ((Map<?, ?>) x).get("id")).toList()).contains(mineId.toString()).doesNotContain(theirsId.toString());
        ResponseEntity<Map> patch = exchange("/v1/setup/whatsapp-templates/" + theirsId, HttpMethod.PATCH,
                authedJsonBody(tokenA, Map.of("name", theirName, "language", "en", "category", "UTILITY",
                        "body", "Your visit is at {{1}}.", "examples", List.of("10:00"))), Map.class);
        assertThat(patch.getStatusCode().value()).as("%s", patch.getBody()).isEqualTo(404);
        assertThat(su("SELECT count(*) FROM whatsapp_templates WHERE id = ? AND status = 'rejected'", theirsId)).isEqualTo(1);
    }

    // ---------- AC3: mismatch throws + rollback ----------

    @Test
    void ac3_auditRecordForAnotherTenant_swallowedByTheCaller_stillRollsBackTheWholeTransaction() {
        UUID a = seedTenant().id();
        UUID b = seedTenant().id();
        signInAs(a);
        String name = n();
        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
            templates.insert(a, name, "m", "en", "UTILITY", "C", "x {{1}}", 1, "approved", "m1");
            try {
                audit.record(b, "system", null, "System", "System", null, "q3.cross", "test", null, null, null);
            } catch (TenantMismatchException expected) {
                // a careless caller swallows it
            }
            assertThat(guc("app.tenant_id")).isEqualTo(a.toString());
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(su("SELECT count(*) FROM whatsapp_templates WHERE name = ?", name)).isZero();
        assertThat(su("SELECT count(*) FROM audit_log WHERE action = 'q3.cross'")).isZero();
    }

    @Test
    void ac3_sameTenantRebindIsAllowed_andAWorkerScopeForAnotherTenantInsideARequestIsRefused() {
        UUID a = seedTenant().id();
        UUID b = seedTenant().id();
        signInAs(a);
        assertThat((UUID) new TransactionTemplate(txManager).execute(s -> {
            tenantContext.set(a);
            tenantContext.set(a);
            return tenantContext.current();
        })).isEqualTo(a);
        // a request thread (bound to A) that opens a worker scope for B
        assertThatThrownBy(() -> tenantScope.run(b, () -> templates.list(b))).isInstanceOf(TenantMismatchException.class);
    }

    /** Hardening probe beyond the literal AC (story: "can never silently change"): bypassing TenantContext with a raw
     * set_config inside a bound transaction switches the tenant silently and nothing (runtime or static rule) stops it. */
    @Test
    void ac3_probe_rawSetConfigCannotSilentlySwitchABoundTransaction() {
        UUID a = seedTenant().id();
        UUID b = seedTenant().id();
        UUID theirs = seedTemplate(b, n());
        signInAs(a);
        Object leaked = null;
        try {
            leaked = new TransactionTemplate(txManager).execute(s -> {
                jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, b.toString());
                jdbc.queryForObject("SELECT set_config('app.visible_tenant_ids', ?, true)", String.class, b.toString());
                return templates.find(b, theirs).orElse(null);
            });
        } catch (RuntimeException refused) {
            // a refusal would be the fix
        }
        assertThat(leaked).as("tenant A's transaction read tenant B's row after a raw set_config").isNull();
    }

    // ---------- AC4: TenantScope for workers ----------

    @Test
    void ac4_concurrentWorkerScopesNeverSeeEachOthersTenant() throws Exception {
        UUID a = seedTenant().id();
        UUID b = seedTenant().id();
        UUID ra = seedTemplate(a, n());
        UUID rb = seedTemplate(b, n());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            UUID t = i % 2 == 0 ? a : b;
            UUID own = i % 2 == 0 ? ra : rb;
            UUID other = i % 2 == 0 ? rb : ra;
            results.add(pool.submit((Callable<Boolean>) () -> tenantScope.run(t, () ->
                    t.toString().equals(guc("app.tenant_id")) && templates.find(t, own).isPresent()
                            && templates.list(t).stream().noneMatch(x -> x.toString().contains(other.toString())))));
        }
        pool.shutdown();
        for (Future<Boolean> f : results) assertThat(f.get()).isTrue();
        assertThat(boundNow()).isNullOrEmpty();
    }

    @Test
    void ac4_tenantScopeRollsBackItsWorkOnFailure_andTheNextScopeIsClean() {
        UUID a = seedTenant().id();
        String name = n();
        assertThatThrownBy(() -> tenantScope.run(a, () -> {
            templates.insert(a, name, "m", "en", "UTILITY", "C", "x {{1}}", 1, "approved", "m2");
            throw new IllegalStateException("handler failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(su("SELECT count(*) FROM whatsapp_templates WHERE name = ?", name)).isZero();
        UUID b = seedTenant().id();
        assertThat(tenantScope.run(b, () -> guc("app.tenant_id"))).isEqualTo(b.toString());
    }
}

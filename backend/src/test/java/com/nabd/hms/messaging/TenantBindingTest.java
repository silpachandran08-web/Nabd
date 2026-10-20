package com.nabd.hms.messaging;

import com.nabd.hms.common.AuditService;
import com.nabd.hms.common.TenantMismatchException;
import com.nabd.hms.common.TenantScope;
import com.nabd.hms.support.ApiTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PLT-019: the tenant is bound at transaction start and can never silently change. Lives in messaging (the module
 * migrated off tenantId parameters) so it can use WhatsAppTemplateRepository as the RLS repository under test. */
class TenantBindingTest extends ApiTestBase {

    @Autowired
    private TenantScope tenantScope;

    @Autowired
    private AuditService audit;

    @Autowired
    private WhatsAppTemplateRepository templates;

    @Autowired
    private WhatsAppTemplateService templateService;

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

    private UUID seedTemplate(UUID tenantId, String name) {
        return inTenantTx(tenantId, () -> templates.insert(tenantId, name, "m_" + name, "en", "UTILITY", "Clinic",
                "Visit at {{1}}.", 1, "approved", "meta-" + name));
    }

    private long superuserCount(String sql, Object... args) {
        return new JdbcTemplate(superuserDataSource()).queryForObject(sql, Long.class, args);
    }

    @Test
    void transactionStartBindsSignedInTenantWithoutAnyExplicitCall() {
        UUID a = seedTenant().id();
        UUID mine = seedTemplate(a, "mine");
        seedTemplate(seedTenant().id(), "theirs");
        signInAs(a);
        String[] seen = new TransactionTemplate(txManager).execute(s -> new String[]{guc("app.tenant_id"), guc("app.visible_tenant_ids")});
        assertThat(seen).containsExactly(a.toString(), a.toString());
        // a migrated service (no tenantId parameter, no set call) sees its own tenant's rows only
        assertThat(templateService.list()).extracting(WhatsAppTemplateService.TemplateResponse::id).containsExactly(mine);
    }

    @Test
    void noSignedInTenantBindsNothing() {
        String bound = new TransactionTemplate(txManager).execute(s -> guc("app.tenant_id"));
        assertThat(bound).isNullOrEmpty();
        assertThatThrownBy(() -> new TransactionTemplate(txManager).execute(s -> tenantContext.current()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void switchingTenantMidTransactionThrowsAndRollsBack() {
        UUID a = seedTenant().id();
        UUID b = seedTenant().id();
        signInAs(a);
        String name = "t" + UUID.randomUUID().toString().substring(0, 8);
        String[] boundAfter = new String[1];
        // the caller even swallows the exception: the transaction still cannot commit
        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
            templates.insert(a, name, "m", "en", "UTILITY", "C", "x {{1}}", 1, "approved", "m1");
            assertThatThrownBy(() -> tenantContext.set(b)).isInstanceOf(TenantMismatchException.class)
                    .hasMessageContaining(a.toString()).hasMessageContaining(b.toString());
            boundAfter[0] = guc("app.tenant_id");
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(boundAfter[0]).isEqualTo(a.toString()); // B was never set
        assertThat(superuserCount("SELECT count(*) FROM whatsapp_templates WHERE name = ?", name)).isZero();
    }

    @Test
    void auditRecordForAnotherTenantThrowsAndRollsBack() {
        UUID a = seedTenant().id();
        UUID b = seedTenant().id();
        signInAs(a);
        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
            audit.record(a, "system", null, "System", "System", null, "plt019.same_tenant", "test", null, null, null);
            audit.record(b, "system", null, "System", "System", null, "plt019.other_tenant", "test", null, null, null);
        })).isInstanceOf(TenantMismatchException.class);
        assertThat(superuserCount("SELECT count(*) FROM audit_log WHERE tenant_id IN (?, ?) AND action LIKE 'plt019.%'", a, b)).isZero();
        // same tenant is fine (bind-or-assert), and an unbound transaction (operator, worker) binds on first use
        audit.record(a, "system", null, "System", "System", null, "plt019.same_tenant", "test", null, null, null);
        SecurityContextHolder.clearContext();
        audit.record(b, "system", null, "System", "System", null, "plt019.other_tenant", "test", null, null, null);
        assertThat(superuserCount("SELECT count(*) FROM audit_log WHERE tenant_id IN (?, ?) AND action LIKE 'plt019.%'", a, b)).isEqualTo(2);
    }

    @Test
    void tenantContextSetOutsideATransactionThrows() {
        assertThatThrownBy(() -> tenantContext.set(UUID.randomUUID())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a transaction");
        assertThatThrownBy(() -> tenantContext.current()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tenantScopeBindsForWorkersJoinsSameTenantAndRefusesAnother() {
        UUID a = seedTenant().id();
        UUID b = seedTenant().id();
        UUID mine = seedTemplate(a, "w");
        assertThat(tenantScope.run(a, () -> {
            assertThat(tenantContext.current()).isEqualTo(a);
            return tenantScope.run(a, () -> templates.list(a)).size(); // same tenant joins
        })).isEqualTo(1);
        assertThat(tenantScope.run(b, () -> templates.find(a, mine))).isEmpty(); // RLS: B cannot see A's row
        assertThatThrownBy(() -> tenantScope.run(a, () -> tenantScope.run(b, () -> templates.list(b))))
                .isInstanceOf(TenantMismatchException.class);
    }

    @Test
    void rlsRepositoryCallOutsideATransactionThrowsInTests() {
        UUID a = seedTenant().id();
        seedTemplate(a, "r");
        assertThatThrownBy(() -> templates.list(a)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WhatsAppTemplateRepository.list").hasMessageContaining("outside a transaction");
        assertThat(templates.clinic(a).slug()).isNotBlank(); // tenants has no RLS: the same repository works outside a transaction
        assertThat(tenantScope.run(a, () -> templates.list(a))).hasSize(1);
    }
}

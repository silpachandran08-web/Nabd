package com.nabd.hms.architecture;

import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TenantScope;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** S12 QA-3, PLT-019: the TenancyRulesTest rules against fixtures the engineer's own test does not use. */
class S12QaPlt019ArchRulesTest {

    static class PatientRepository { int count(UUID t) { return 0; } }

    /** A scheduled job (not an outbox handler) that switches tenant by hand: must be flagged. */
    static class NightlyJob {
        TenantContext ctx;
        void run(UUID t) { ctx.set(t); }
    }

    /** Repository called straight from a plain method: must be flagged. */
    static class PlainCaller {
        PatientRepository repo;
        int count(UUID t) { return repo.count(t); }
    }

    /** Repository called in a stream lambda of a NON-transactional method: still outside any transaction. */
    static class LambdaCaller {
        PatientRepository repo;
        int countAll(List<UUID> tenants) { return tenants.stream().mapToInt(t -> repo.count(t)).sum(); }
    }

    static class Clean {
        PatientRepository repo; TenantScope scope;
        @Transactional int a(UUID t) { return repo.count(t); }
        int b(UUID t) { return scope.run(t, () -> repo.count(t)); }
    }

    private static JavaClasses fx(Class<?>... c) {
        return new ClassFileImporter().importClasses(c);
    }

    @Test
    void tenantContextSetInAnyNonCoreClassIsFlagged_andCleanCodeIsNot() {
        assertThat(TenancyRulesTest.tenantContextSetCallers(fx(NightlyJob.class, Clean.class)))
                .containsExactly(NightlyJob.class.getName() + ".run");
    }

    @Test
    void directRepositoryCallOutsideATransactionIsFlagged_andCleanCodeIsNot() {
        assertThat(TenancyRulesTest.repositoryCallsOutsideTransactions(fx(PlainCaller.class, Clean.class, PatientRepository.class)))
                .containsExactly(PlainCaller.class.getName() + ".count");
    }

    /** Probe of the limit E10 reported ("treats any lambda as inside a transaction"): a stream lambda in a plain method. */
    @Test
    void repositoryCallInsideALambdaOfANonTransactionalMethodIsFlagged() {
        assertThat(TenancyRulesTest.repositoryCallsOutsideTransactions(fx(LambdaCaller.class, PatientRepository.class)))
                .containsExactly(LambdaCaller.class.getName() + ".countAll");
    }
}

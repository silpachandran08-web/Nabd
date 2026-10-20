package com.nabd.hms.architecture;

import com.nabd.hms.common.OutboxEvent;
import com.nabd.hms.common.OutboxEventHandler;
import com.nabd.hms.common.TenantContext;
import com.nabd.hms.common.TenantScope;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-019 tenancy rules, separate from the frozen OPS-033 set (ArchitectureRules). Each rule has its own frozen
 * baseline of existing call sites (Class.method, src/test/resources/architecture/), which may only shrink:
 * a call site not in it fails. Regenerate after removing sites with -Dtenancy.baseline.write=true.
 * <ul>
 * <li>tenant-context-set: TenantContext.set is called only by the tenancy core (TenantContext, TenantScope,
 *     TenantTransactionBinder); outbox handlers and the outbox processor never call it, baseline or not.</li>
 * <li>repository-outside-transaction: a *Repository is called only from a @Transactional method/class, a lambda
 *     (TransactionTemplate / TenantScope.run work) or a method that opens one of those itself.</li>
 * <li>raw-tenant-guc: only TenantContext (and the test-only RepositoryTransactionGuard sentinel) may write
 *     app.tenant_id / app.visible_tenant_ids (set_config or SET [LOCAL]); no baseline, main code has no other site.</li>
 * </ul>
 */
class TenancyRulesTest {

    static final Set<String> CORE = Set.of("com.nabd.hms.common.TenantContext", "com.nabd.hms.common.TenantScope",
            "com.nabd.hms.common.TenantTransactionBinder");
    static final Path DIR = Path.of("src/test/resources/architecture");

    static JavaClasses main() {
        return new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ArchitectureRules.ROOT);
    }

    /** Class.method of every caller of TenantContext.set outside the tenancy core; lambdas count as their enclosing method. */
    static Set<String> tenantContextSetCallers(JavaClasses classes) {
        Set<String> out = new TreeSet<>();
        for (JavaClass c : classes) {
            if (CORE.contains(c.getName())) continue;
            for (JavaMethodCall call : c.getMethodCallsFromSelf()) {
                if (call.getTargetOwner().isEquivalentTo(TenantContext.class) && call.getName().equals("set")) {
                    out.add(site(call.getOrigin()));
                }
            }
        }
        return out;
    }

    /** Class.method of every *Repository call not provably inside a transaction. */
    static Set<String> repositoryCallsOutsideTransactions(JavaClasses classes) {
        Set<String> out = new TreeSet<>();
        for (JavaClass c : classes) {
            if (c.getSimpleName().endsWith("Repository") || c.getName().contains("Repository$")) continue;
            for (JavaMethodCall call : c.getMethodCallsFromSelf()) {
                JavaClass target = call.getTargetOwner();
                if (!target.getPackageName().startsWith(ArchitectureRules.ROOT) || !target.getSimpleName().endsWith("Repository")) continue;
                JavaCodeUnit origin = call.getOrigin();
                if (origin.getName().startsWith("lambda$") || transactional(origin) || opensTransaction(origin)) continue;
                out.add(site(origin));
            }
        }
        return out;
    }

    private static boolean transactional(JavaCodeUnit u) {
        return u.isAnnotatedWith(Transactional.class) || u.getOwner().isAnnotatedWith(Transactional.class);
    }

    private static boolean opensTransaction(JavaCodeUnit u) {
        return u.getMethodCallsFromSelf().stream().anyMatch(m -> m.getTargetOwner().isEquivalentTo(TransactionTemplate.class)
                || m.getTargetOwner().isEquivalentTo(TenantScope.class));
    }

    private static String site(JavaCodeUnit u) {
        String m = u.getName().startsWith("lambda$") ? u.getName().split("\\$")[1] : u.getName();
        return u.getOwner().getName().replaceFirst("\\$\\d+$", "") + "." + m;
    }

    private static void assertFrozen(String file, Set<String> now) throws IOException {
        Path f = DIR.resolve(file);
        if (Boolean.getBoolean("tenancy.baseline.write")) {
            Files.createDirectories(DIR);
            Files.write(f, now);
        }
        Set<String> base = new TreeSet<>(Files.readAllLines(f));
        Set<String> added = new TreeSet<>(now);
        added.removeAll(base);
        assertThat(added).as("new call sites (see TenancyRulesTest; use TenantScope.run / the tenant bound at transaction start)").isEmpty();
    }

    @Test
    void tenantContextSetOnlyInTenancyCoreOrFrozenBaseline() throws IOException {
        assertFrozen("tenant-context-set-baseline.txt", tenantContextSetCallers(main()));
    }

    @Test
    void outboxWorkersUseTenantScopeNeverTenantContextSet() {
        JavaClasses main = main();
        Set<String> workers = new TreeSet<>();
        for (JavaClass c : main) {
            if (c.isAssignableTo(OutboxEventHandler.class) || c.getSimpleName().equals("OutboxEventProcessor")) workers.add(c.getName());
        }
        assertThat(workers).hasSizeGreaterThan(5).contains("com.nabd.hms.common.OutboxEventProcessor", "com.nabd.hms.auth.OtpRequestedHandler");
        assertThat(tenantContextSetCallers(main)).noneMatch(s -> workers.contains(s.substring(0, s.lastIndexOf('.'))));
        assertThat(tenantContextSetCallers(main)).doesNotContain("com.nabd.hms.auth.SessionLivenessChecker.isActive");
    }

    @Test
    void repositoriesOnlyInsideTransactionsOrFrozenBaseline() throws IOException {
        assertFrozen("repository-outside-tx-baseline.txt", repositoryCallsOutsideTransactions(main()));
    }

    static final java.util.regex.Pattern RAW_TENANT_GUC = java.util.regex.Pattern.compile(
            "(?i)set_config\\s*\\(\\s*'app\\.(tenant_id|visible_tenant_ids)'|\\bset\\s+(local\\s+|session\\s+)?app\\.(tenant_id|visible_tenant_ids)\\s*(=|to\\b)");
    static final Set<String> GUC_WRITERS = Set.of("com/nabd/hms/common/TenantContext.java", "com/nabd/hms/common/RepositoryTransactionGuard.java");

    /** Source files (relative to root) that write a tenant GUC outside the tenancy core. */
    static Set<String> rawTenantGucWriters(Path root) throws IOException {
        Set<String> out = new TreeSet<>();
        try (var files = Files.walk(root)) {
            for (Path f : (Iterable<Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                if (!GUC_WRITERS.contains(rel) && RAW_TENANT_GUC.matcher(Files.readString(f)).find()) out.add(rel);
            }
        }
        return out;
    }

    @Test
    void onlyTheTenancyCoreWritesTenantGucs() throws IOException {
        assertThat(rawTenantGucWriters(Path.of("src/main/java")))
                .as("raw set_config('app.tenant_id'/'app.visible_tenant_ids') bypasses the PLT-019 binding: use TenantContext.set / TenantScope.run")
                .isEmpty();
    }

    @Test
    void rawTenantGucRuleFlagsFixtures(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("Bad1.java"), "q(\"SELECT set_config( 'app.tenant_id', ?, true)\");");
        Files.writeString(dir.resolve("Bad2.java"), "q(\"SET LOCAL app.visible_tenant_ids = 'x'\");");
        Files.writeString(dir.resolve("Ok.java"), "q(\"SELECT current_setting('app.tenant_id', true)\"); // set app.tenant_id first");
        assertThat(rawTenantGucWriters(dir)).containsExactly("Bad1.java", "Bad2.java");
    }

    @Test
    void rulesFlagFixturesAndPassCleanOnes() {
        JavaClasses fx = new ClassFileImporter().importClasses(BadWorker.class, CleanWorker.class, NoteRepository.class, TxNoteService.class);
        assertThat(tenantContextSetCallers(fx)).containsExactly(BadWorker.class.getName() + ".handle");
        assertThat(repositoryCallsOutsideTransactions(fx)).containsExactly(BadWorker.class.getName() + ".handle");
    }

    // fixtures: a worker that switches tenant by hand and reads a repository with no transaction, and clean ones
    static class NoteRepository { int count(UUID t) { return 0; } }
    static class BadWorker implements OutboxEventHandler {
        TenantContext ctx; NoteRepository repo;
        public String eventType() { return "x"; }
        public void handle(OutboxEvent e) { ctx.set(e.tenantId()); repo.count(e.tenantId()); }
    }
    static class CleanWorker implements OutboxEventHandler {
        TenantScope scope; NoteRepository repo;
        public String eventType() { return "y"; }
        public void handle(OutboxEvent e) { scope.run(e.tenantId(), () -> repo.count(e.tenantId())); }
    }
    static class TxNoteService { NoteRepository repo; @Transactional int count() { return repo.count(null); } }
}

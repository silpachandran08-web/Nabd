package com.nabd.hms.architecture;

import com.nabd.hms.common.ApiException;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLT-034 rules (ArchUnit + a source-size check):
 * <ul>
 * <li>request-body-valid: every @RequestBody parameter is also @Valid (or @Validated), so a body that breaks its DTO's
 *     constraints is a 400 problem from GlobalExceptionHandler. Raw byte[] bodies (signed webhooks, blob uploads, file
 *     imports) are exempt: they are checked as bytes and have no DTO to validate.</li>
 * <li>no-local-helpers: no class declares its own tenantId(Jwt) (use @CurrentTenant / @CurrentStaff) or notFound()
 *     (use ApiException.notFound()).</li>
 * <li>auth-internal: nothing outside com.nabd.hms.auth depends on com.nabd.hms.auth.internal.. (stores, guards, sub-services).</li>
 * <li>auth-size: every class of the auth module (the IAM implementation: AuthService, auth.internal.*, break-glass) stays
 *     at or under 300 lines.</li>
 * </ul>
 */
class Plt034RulesTest {

    static final int MAX_AUTH_LINES = 300;

    static JavaClasses main() {
        return new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ArchitectureRules.ROOT);
    }

    /** Class.method(param) of every @RequestBody parameter without @Valid/@Validated, byte[] bodies excepted. */
    static Set<String> unvalidatedBodies(JavaClasses classes) {
        Set<String> out = new TreeSet<>();
        for (JavaClass c : classes) {
            for (JavaMethod m : c.getMethods()) {
                for (JavaParameter p : m.getParameters()) {
                    if (!p.isAnnotatedWith(RequestBody.class) || p.getRawType().isEquivalentTo(byte[].class)) continue;
                    if (p.isAnnotatedWith(Valid.class) || p.isAnnotatedWith(Validated.class)) continue;
                    out.add(c.getName() + "." + m.getName() + "(" + p.getRawType().getSimpleName() + ")");
                }
            }
        }
        return out;
    }

    /** Class.method of every local tenantId(Jwt) or notFound(..) helper outside ApiException. */
    static Set<String> localHelpers(JavaClasses classes) {
        Set<String> out = new TreeSet<>();
        for (JavaClass c : classes) {
            if (c.isEquivalentTo(ApiException.class)) continue;
            for (JavaMethod m : c.getMethods()) {
                boolean tenantHelper = m.getName().equals("tenantId") && m.getRawParameterTypes().size() == 1
                        && m.getRawParameterTypes().get(0).isEquivalentTo(Jwt.class);
                boolean notFoundHelper = m.getName().equals("notFound") && m.getRawReturnType().isAssignableTo(RuntimeException.class);
                if (tenantHelper || notFoundHelper) out.add(c.getName() + "." + m.getName());
            }
        }
        return out;
    }

    @Test
    void everyRequestBodyIsValidated() {
        assertThat(unvalidatedBodies(main()))
                .as("PLT-034: annotate these @RequestBody parameters with @Valid (jakarta.validation.Valid), e.g. "
                        + "`@Valid @RequestBody MyRequest req`; byte[] bodies are exempt")
                .isEmpty();
    }

    @Test
    void noLocalTenantIdOrNotFoundHelpers() {
        assertThat(localHelpers(main()))
                .as("PLT-034: use @CurrentTenant/@CurrentStaff UUID parameters instead of tenantId(Jwt), and ApiException.notFound() instead of a local notFound()")
                .isEmpty();
    }

    /** Enforced here (ArchitectureRulesTest only reports): auth's stores, guards and sub-services are module-private. */
    @Test
    void authInternalsAreReachedOnlyFromTheAuthModule() {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that().resideOutsideOfPackage("com.nabd.hms.auth..")
                .should().dependOnClassesThat().resideInAPackage("com.nabd.hms.auth.internal..")
                .because("PLT-034: other modules go through AuthService (or iam.api), never AuthModels, the auth repositories or guards")
                .check(main());
    }

    @Test
    void authClassesStayUnder300Lines() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(Path.of("src/main/java/com/nabd/hms/auth"))) {
            files = s.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(files).anyMatch(p -> p.endsWith("internal/store/StaffDirectoryRepository.java")).hasSizeGreaterThan(30);
        for (Path f : files) {
            assertThat(Files.readAllLines(f).size()).as(f + " exceeds " + MAX_AUTH_LINES + " lines (PLT-034)").isLessThanOrEqualTo(MAX_AUTH_LINES);
        }
    }

    @Test
    void rulesFlagFixturesAndPassCleanOnes() {
        JavaClasses fx = new ClassFileImporter().importClasses(BadController.class, CleanController.class);
        assertThat(unvalidatedBodies(fx)).containsExactly(BadController.class.getName() + ".create(Body)");
        assertThat(localHelpers(fx)).containsExactly(BadController.class.getName() + ".notFound", BadController.class.getName() + ".tenantId");
    }

    // fixtures
    record Body(String name) {}
    static class BadController {
        void create(@RequestBody Body body) {}
        UUID tenantId(Jwt jwt) { return UUID.fromString(jwt.getClaimAsString("tenantId")); }
        ApiException notFound() { return ApiException.notFound(); }
    }
    static class CleanController {
        void create(@Valid @RequestBody Body body) {}
        void webhook(@RequestBody byte[] raw) {}
    }
}

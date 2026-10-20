package com.nabd.hms.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLT-033: hard rules (they fail the build, unlike ArchitectureRulesTest which only reports to the OPS-033 gate).
 * Staff lookups go through iam.api.ActorDirectory; department/setup are reached only via organization.api.
 */
class ActorDirectoryRulesTest {

    static final String ROOT = ArchitectureRules.ROOT;

    static final ArchRule NO_ACTOR_INFO_COPIES = noClasses().that().resideOutsideOfPackage(ROOT + ".iam..")
            .should().haveSimpleNameEndingWith("ActorInfo")
            .because("PLT-033: staff name/role lookups use iam.api.ActorDirectory");

    static final ArchRule NO_FIND_ACTOR_COPIES = noMethods().that().areDeclaredInClassesThat().resideOutsideOfPackage(ROOT + ".iam..")
            .should().haveNameMatching("findActorInfo|findActor|findActors")
            .because("PLT-033: staff name/role lookups use iam.api.ActorDirectory");

    static final ArchRule ORGANIZATION_ONLY_VIA_API = noClasses().that().resideOutsideOfPackage(ROOT + ".organization..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".organization.department..", ROOT + ".organization.setup..")
            .because("PLT-033: other modules use organization.api");

    static final List<ArchRule> RULES = List.of(NO_ACTOR_INFO_COPIES, NO_FIND_ACTOR_COPIES, ORGANIZATION_ONLY_VIA_API);

    @Test
    void mainCodeFollowsActorDirectoryAndOrganizationApiRules() {
        JavaClasses main = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);
        assertTrue(main.contain(ROOT + ".iam.api.ActorDirectory"), "ActorDirectory imported");
        assertTrue(main.contain(ROOT + ".organization.api.OrganizationConfig"), "organization.api imported");
        assertFalse(main.contain(ROOT + ".department.DepartmentService"), "department package moved under organization");
        RULES.forEach(r -> r.check(main));
    }

    @Test
    void rulesCatchFixtureViolations() {
        JavaClasses fx = new ClassFileImporter().importClasses(ActorInfo.class, CopyRepository.class);
        assertTrue(NO_ACTOR_INFO_COPIES.evaluate(fx).hasViolation());
        assertTrue(NO_FIND_ACTOR_COPIES.evaluate(fx).hasViolation());
    }

    // fixtures: a module-local copy of the actor lookup, the pattern PLT-033 removed
    record ActorInfo(String name, String role) {}
    static class CopyRepository { ActorInfo findActorInfo(java.util.UUID t, java.util.UUID s) { return null; } }
}

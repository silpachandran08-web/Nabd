package com.nabd.hms.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLT-035: hard rules (they fail the build) for the queue-billing-packages cycle, the Java half of the blocking SQL-ownership
 * pairs in ci/architecture-gate.json. Billing reaches the visit only through queue.api (VisitCommands, VisitQueries); the visit
 * module depends on neither billing nor packages (packages answers it through the queue.api.LeaveImpact port); billing never
 * depends on packages (packages implements billing.api.PackageRedemption).
 */
class Plt035CycleArchTest {

    static final String ROOT = ArchitectureRules.ROOT;

    static final ArchRule BILLING_REACHES_VISIT_ONLY_VIA_API = noClasses().that().resideInAPackage(ROOT + ".billing..")
            .should().dependOnClassesThat(resideInAPackage(ROOT + ".queue..").and(resideOutsideOfPackage(ROOT + ".queue.api..")))
            .because("PLT-035: billing moves and reads visits only through queue.api.VisitCommands / VisitQueries");

    static final ArchRule VISIT_INDEPENDENT_OF_BILLING_AND_PACKAGES = noClasses().that().resideInAPackage(ROOT + ".queue..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".billing..", ROOT + ".packages..")
            .because("PLT-035: the visit module learns about settlement from billing.invoice.settled.v1, about packages through LeaveImpact");

    static final ArchRule BILLING_INDEPENDENT_OF_PACKAGES = noClasses().that().resideInAPackage(ROOT + ".billing..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".packages..")
            .because("PLT-035: package redemption is billing.api.PackageRedemption, implemented by the packages module");

    static final List<ArchRule> RULES = List.of(BILLING_REACHES_VISIT_ONLY_VIA_API, VISIT_INDEPENDENT_OF_BILLING_AND_PACKAGES, BILLING_INDEPENDENT_OF_PACKAGES);

    @Test
    void mainCodeHasNoQueueBillingPackagesCycle() {
        JavaClasses main = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);
        assertTrue(main.contain(ROOT + ".queue.api.VisitCommands") && main.contain(ROOT + ".billing.api.PackageRedemption"), "module APIs imported");
        RULES.forEach(r -> r.check(main));
    }

    @Test
    void rulesCatchFixtureViolations() {
        JavaClasses fx = new ClassFileImporter().importClasses(com.nabd.hms.billing.Plt035BadBillingFixture.class,
                com.nabd.hms.queue.Plt035BadQueueFixture.class);
        assertTrue(BILLING_REACHES_VISIT_ONLY_VIA_API.evaluate(fx).hasViolation());
        assertTrue(VISIT_INDEPENDENT_OF_BILLING_AND_PACKAGES.evaluate(fx).hasViolation());
    }
}

package com.nabd.hms.architecture;

import com.nabd.hms.billing.api.PayerLedger;
import com.nabd.hms.billing.api.VisitCoverageSource;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIN-036: hard module rules. Billing owns the payer split and knows nothing of the insurance module: it asks through the
 * billing.api.VisitCoverageSource port, which only insurance implements. Insurance reaches billing only through billing.api
 * (PayerLedger, implemented only by billing), so it can never write a billing table.
 */
class Fin036InsuranceArchTest {

    static final String ROOT = ArchitectureRules.ROOT;

    static final ArchRule BILLING_INDEPENDENT_OF_INSURANCE = noClasses().that().resideInAPackage(ROOT + ".billing..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".insurance..")
            .because("FIN-036: billing reads a visit's coverage through billing.api.VisitCoverageSource, never insurance classes");

    static final ArchRule INSURANCE_REACHES_BILLING_ONLY_VIA_API = noClasses().that().resideInAPackage(ROOT + ".insurance..")
            .should().dependOnClassesThat(resideInAPackage(ROOT + ".billing..").and(resideOutsideOfPackage(ROOT + ".billing.api..")))
            .because("FIN-036: the split and the payer payments are billing's; insurance uses billing.api.PayerLedger");

    static final ArchRule COVERAGE_SOURCE_IS_INSURANCE = classes().that().implement(VisitCoverageSource.class)
            .should().resideInAPackage(ROOT + ".insurance..");

    static final ArchRule PAYER_LEDGER_IS_BILLING = classes().that().implement(PayerLedger.class).should().resideInAPackage(ROOT + ".billing..");

    static final ArchRule INSURANCE_INTERNALS_STAY_INSIDE = noClasses().that().resideOutsideOfPackage(ROOT + ".insurance..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".insurance.internal..", ROOT + ".insurance.web..");

    @Test
    void mainCodeKeepsBillingAndInsuranceApart() {
        JavaClasses main = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);
        assertTrue(main.contain(ROOT + ".insurance.internal.InsuranceService") && main.contain(ROOT + ".billing.PayerSplitService"), "both modules imported");
        List.of(BILLING_INDEPENDENT_OF_INSURANCE, INSURANCE_REACHES_BILLING_ONLY_VIA_API, COVERAGE_SOURCE_IS_INSURANCE, PAYER_LEDGER_IS_BILLING,
                INSURANCE_INTERNALS_STAY_INSIDE).forEach(r -> r.check(main));
    }

    /** The rule is not vacuous: a billing class that touched an insurance class would be caught. */
    @Test
    void theBillingRuleCatchesADependencyOnInsurance() {
        JavaClasses fx = new ClassFileImporter().importClasses(com.nabd.hms.billing.Fin036BadBillingFixture.class,
                com.nabd.hms.insurance.api.ClaimsGateway.class);
        assertTrue(BILLING_INDEPENDENT_OF_INSURANCE.evaluate(fx).hasViolation());
    }
}

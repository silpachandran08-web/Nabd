package com.nabd.hms.architecture;

import com.nabd.hms.auth.api.OwnerAccess;
import com.nabd.hms.common.ProvisioningSeeds;
import com.nabd.hms.common.TenantSettingsPort;
import com.nabd.hms.nursing.api.ProcedureBilling;
import com.nabd.hms.organization.api.ChargeHeadWriter;
import com.nabd.hms.pharmacy.api.ItemCatalogueWriter;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLT-036: hard rules (they fail the build), the Java half of "one writing module per table" in ci/architecture-gate.json
 * (sql_ownership_all_modules). Each write port is implemented only inside the module that owns the table, and the modules that
 * now write through a port did not gain a dependency on the owner: organization and pharmacy never depend on billing, patient
 * never on the visit module, nursing never on billing, and billing reaches nursing only through nursing.api.
 */
class Plt036SingleWriterArchTest {

    static final String ROOT = ArchitectureRules.ROOT;

    static ArchRule implementedOnlyIn(Class<?> port, String... ownerPackages) {
        return classes().that().implement(port).should().resideInAnyPackage(ownerPackages)
                .because("PLT-036: " + port.getSimpleName() + " writes tables owned by " + String.join(" / ", ownerPackages));
    }

    static final List<ArchRule> PORT_OWNERS = List.of(
            implementedOnlyIn(TenantSettingsPort.class, ROOT + ".platform.."),                                   // tenants
            implementedOnlyIn(ChargeHeadWriter.class, ROOT + ".billing.."),                                      // charge_catalogue
            implementedOnlyIn(ItemCatalogueWriter.class, ROOT + ".billing.."),                                   // charge_catalogue
            implementedOnlyIn(OwnerAccess.class, ROOT + ".auth..", ROOT + ".staff..", ROOT + ".iam.."),           // staff, login_attempts
            implementedOnlyIn(ProvisioningSeeds.Iam.class, ROOT + ".auth..", ROOT + ".staff..", ROOT + ".iam.."), // roles, staff
            implementedOnlyIn(ProvisioningSeeds.Owners.class, ROOT + ".owner.."),                                // owners
            implementedOnlyIn(ProvisioningSeeds.Departments.class, ROOT + ".organization.."),                    // departments
            implementedOnlyIn(ProcedureBilling.class, ROOT + ".nursing.."));                                     // procedure_orders

    static final ArchRule SETUP_AND_PHARMACY_INDEPENDENT_OF_BILLING = noClasses().that().resideInAnyPackage(ROOT + ".organization..", ROOT + ".pharmacy..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".billing..")
            .because("PLT-036: charge_catalogue is written through ChargeHeadWriter / ItemCatalogueWriter, ports billing implements");

    static final ArchRule PATIENT_INDEPENDENT_OF_VISIT = noClasses().that().resideInAPackage(ROOT + ".patient..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".queue..")
            .because("PLT-036: queue_entries is written by the visit module only (LastVisitRepairJob lives there)");

    static final ArchRule NURSING_INDEPENDENT_OF_BILLING = noClasses().that().resideInAPackage(ROOT + ".nursing..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".billing..")
            .because("PLT-036: checkout calls nursing.api.ProcedureBilling, never the other way round");

    static final ArchRule BILLING_REACHES_NURSING_ONLY_VIA_API = noClasses().that().resideInAPackage(ROOT + ".billing..")
            .should().dependOnClassesThat(resideInAPackage(ROOT + ".nursing..").and(resideOutsideOfPackage(ROOT + ".nursing.api..")))
            .because("PLT-036: procedure_orders is written through nursing.api.ProcedureBilling");

    static List<ArchRule> rules() {
        List<ArchRule> r = new ArrayList<>(PORT_OWNERS);
        r.addAll(List.of(SETUP_AND_PHARMACY_INDEPENDENT_OF_BILLING, PATIENT_INDEPENDENT_OF_VISIT, NURSING_INDEPENDENT_OF_BILLING,
                BILLING_REACHES_NURSING_ONLY_VIA_API));
        // OPS-033 rule with no finding in main code: a module's internal packages stay private. Blocking from here on.
        r.add(ArchitectureRules.rules().get("internal-only-from-own-module"));
        return r;
    }

    @Test
    void everyWritePortIsImplementedByItsOwningModuleAndNoNewDependencyOnTheOwner() {
        JavaClasses main = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages(ROOT);
        for (Class<?> port : List.of(TenantSettingsPort.class, ChargeHeadWriter.class, ItemCatalogueWriter.class, OwnerAccess.class,
                ProvisioningSeeds.Iam.class, ProvisioningSeeds.Owners.class, ProvisioningSeeds.Departments.class, ProcedureBilling.class)) {
            assertTrue(main.stream().anyMatch(c -> !c.isInterface() && c.isAssignableTo(port)), port.getName() + " has an implementation");
        }
        rules().forEach(r -> r.check(main));
    }

    @Test
    void rulesCatchFixtureViolations() {
        JavaClasses fx = new ClassFileImporter().importClasses(com.nabd.hms.pharmacy.Plt036BadPharmacyFixture.class);
        assertTrue(implementedOnlyIn(ProcedureBilling.class, ROOT + ".nursing..").evaluate(fx).hasViolation());
        assertTrue(SETUP_AND_PHARMACY_INDEPENDENT_OF_BILLING.evaluate(fx).hasViolation());
    }
}

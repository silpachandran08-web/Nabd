package com.nabd.hms.architecture;

import com.nabd.hms.organization.api.OrganizationConfig;
import com.nabd.hms.organization.department.DepartmentService;
import com.nabd.hms.organization.setup.SetupService;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** QA-5 (S12) PLT-033 AC2/AC4: the shipped ArchUnit rules fail on bad samples and pass on good ones. */
class S12QaPlt033ArchRulesTest {

    // ---- bad samples (this package is outside iam and organization) ----
    record StaffActorInfo(String name, String role) {}
    static class LocalLookup { StaffActorInfo findActorInfo(UUID t, UUID s) { return null; } }
    static class BatchLookup { java.util.Map<UUID, String> findActors(UUID t, java.util.List<UUID> ids) { return null; } }
    static class UsesDepartmentInternals { DepartmentService svc; }
    static class CallsSetupService { Object x(SetupService r) { return r; } }
    // ---- good sample ----
    static class UsesOrganizationApi { OrganizationConfig cfg; }

    private static JavaClasses of(Class<?>... cs) { return new ClassFileImporter().importClasses(cs); }

    @Test
    void actorInfoTypeOutsideIamFails() {
        assertTrue(ActorDirectoryRulesTest.NO_ACTOR_INFO_COPIES.evaluate(of(StaffActorInfo.class)).hasViolation());
    }

    @Test
    void findActorInfoAndFindActorsMethodsOutsideIamFail() {
        assertTrue(ActorDirectoryRulesTest.NO_FIND_ACTOR_COPIES.evaluate(of(LocalLookup.class)).hasViolation());
        assertTrue(ActorDirectoryRulesTest.NO_FIND_ACTOR_COPIES.evaluate(of(BatchLookup.class)).hasViolation());
    }

    @Test
    void dependingOnOrganizationDepartmentOrSetupFromOutsideFails() {
        JavaClasses cs = new ClassFileImporter().importClasses(UsesDepartmentInternals.class, CallsSetupService.class, DepartmentService.class, SetupService.class);
        var result = ActorDirectoryRulesTest.ORGANIZATION_ONLY_VIA_API.evaluate(cs);
        assertTrue(result.hasViolation());
        String report = result.getFailureReport().toString();
        assertTrue(report.contains("UsesDepartmentInternals") && report.contains("CallsSetupService"), report);
    }

    @Test
    void organizationApiAndIamClassesPass() {
        JavaClasses cs = new ClassFileImporter().importClasses(UsesOrganizationApi.class, OrganizationConfig.class,
                com.nabd.hms.iam.api.ActorDirectory.class, com.nabd.hms.iam.api.ActorDirectory.Actor.class);
        ActorDirectoryRulesTest.RULES.forEach(r -> assertFalse(r.evaluate(cs).hasViolation(), r.getDescription()));
    }

    @Test
    void mainCodeHasNoCopiesAndNoDirectOrganizationInternalsUse() {
        JavaClasses main = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.nabd.hms");
        ActorDirectoryRulesTest.RULES.forEach(r -> assertFalse(r.evaluate(main).hasViolation(), r.evaluate(main).getFailureReport().toString()));
        // the old top-level packages are gone (moved under organization)
        assertFalse(main.containPackage("com.nabd.hms.department"));
        assertFalse(main.containPackage("com.nabd.hms.setup"));
        assertTrue(main.containPackage("com.nabd.hms.organization.department") && main.containPackage("com.nabd.hms.organization.setup"));
    }
}

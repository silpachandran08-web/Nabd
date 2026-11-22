package com.nabd.hms.common;

import java.util.List;
import java.util.UUID;

/**
 * PLT-036: tenant provisioning (platform) creates and rolls back the first rows of a new clinic through the modules that own the
 * tables, without importing them (same seam as {@link CatalogueTemplates}). Each method runs the statement provisioning used to
 * run itself, in the provisioning step's transaction; for the tenant tables the step has already bound the new tenant.
 */
public interface ProvisioningSeeds {

    /** owners: implemented by the owner module. */
    interface Owners {
        UUID create(String name, String email);

        /** Saga rollback of an owner this job created. */
        void delete(UUID ownerId);
    }

    /** roles and staff: implemented by iam. */
    interface Iam {
        /** PLT-016: one preset role (only the Owner preset is built in). */
        void insertPresetRole(UUID tenantId, String name, boolean builtIn, String grantsJson, String presetCode, int presetVersion);

        /** PLT-022: a branch's copied roles (ConfigBundle rows, JSON per row), insert-if-absent under a fresh id. Returns rows written. */
        int copyRoles(UUID tenantId, List<String> rowsJson);

        /** PLT-022: replaces the seeded presets before {@link #copyRoles} and undoes it; the built-in Owner stays. */
        void deleteCopiedRoles(UUID tenantId);

        /** Saga rollback of seed_masters: the built-in and preset roles. */
        void deleteSeededRoles(UUID tenantId);

        /** NB-353: saga rollback of verify_invite_owner; a failed job must leave no login behind. */
        void deleteStaff(UUID tenantId);
    }

    /** departments: implemented by organization. */
    interface Departments {
        /** The 'General' default department, so check-in works before anyone assigns doctors to departments. */
        void seedDefault(UUID tenantId);

        /** Saga rollback of seedDefault; must run before the tenant row is deleted (FK). */
        void deleteAll(UUID tenantId);
    }
}

package com.nabd.hms.organization.api;

import java.util.Optional;
import java.util.UUID;

/** Clinic policy values (clinic_policies) other modules may read; implemented by organization.setup.SetupService. */
public interface ClinicPolicies {

    Optional<String> policyValue(UUID tenantId, String policyKey);
}

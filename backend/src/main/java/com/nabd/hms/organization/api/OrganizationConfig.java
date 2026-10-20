package com.nabd.hms.organization.api;

import java.util.List;
import java.util.UUID;

/** PLT-033: the organization config other modules may read (departments, workflows); implemented in organization.department. */
public interface OrganizationConfig {

    /** A department's queue pipeline: checked_in/waiting first, checkout_pending/completed last, its workflow steps in between. */
    List<String> resolveStatusSequence(UUID tenantId, UUID departmentId);
}

package com.nabd.hms.auth.dto;

import java.util.List;
import java.util.UUID;

/** PLT-023: the branches of the signed-in person (one entry for a single-branch user: the shell then shows no switcher). */
public record BranchListResponse(UUID currentTenantId, boolean reportsOnly, List<BranchSummary> branches) {
}

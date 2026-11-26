package com.nabd.hms.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * PLT-023: branchSelectToken is the one a multi-branch sign-in answered with; it is left out when the caller switches with
 * their current access token. reportsOnly asks for the 'All branches (reports)' session of that branch.
 */
public record BranchSelectRequest(@NotNull UUID tenantId, @Size(max = 4096) String branchSelectToken, Boolean reportsOnly) {
}

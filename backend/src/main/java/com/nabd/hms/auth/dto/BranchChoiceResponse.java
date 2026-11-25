package com.nabd.hms.auth.dto;

import java.util.List;

/** PLT-023: sign-in passed, but the person works in more than one branch: POST /v1/auth/branches/select with the token and one tenantId. */
public record BranchChoiceResponse(String branchSelectToken, long expiresIn, List<BranchSummary> branches) {
}

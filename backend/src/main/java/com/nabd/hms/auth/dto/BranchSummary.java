package com.nabd.hms.auth.dto;

import java.util.UUID;

/** PLT-023: one branch the signed-in person may enter. branchCode is null for a clinic created before branches had codes. */
public record BranchSummary(UUID tenantId, String name, String branchCode) {
}

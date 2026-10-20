package com.nabd.hms.owner.dto;

/** PLT-005: the owner_select token, issued only after the second factor. remainingRecoveryCodes is set on the recovery path. */
public record OwnerWorkspaceTokenResponse(String workspaceToken, long expiresIn, Integer remainingRecoveryCodes) {
}

package com.nabd.hms.owner.dto;

import java.util.List;

/** Recovery codes are shown once; the workspace token continues straight to workspace selection. */
public record OwnerMfaConfirmResponse(List<String> recoveryCodes, String workspaceToken, long expiresIn) {
}

package com.nabd.hms.staff.dto;

import java.util.UUID;

/** Just enough for StaffController to hand off to AuthService.issueTokensForStaff after accept-invite. */
public record AcceptedStaffIdentity(UUID tenantId, UUID staffId) {
}

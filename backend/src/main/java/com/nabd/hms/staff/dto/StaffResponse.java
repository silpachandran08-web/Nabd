package com.nabd.hms.staff.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StaffResponse(UUID id, String email, String name, String mobilePhone, UUID roleId, String status,
                             String scope, boolean emailVerified, boolean mobileVerified,
                             List<String> fieldGrants, Instant lastSeenAt) {
}

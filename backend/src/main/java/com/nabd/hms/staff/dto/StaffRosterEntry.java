package com.nabd.hms.staff.dto;

import java.util.UUID;

/** id+name only — deliberately not StaffResponse's shape (email/mobile/verification flags are
 * staff:view-only HR data), so a queue:view role can populate a doctor picker without seeing it. */
public record StaffRosterEntry(UUID id, String name) {
}

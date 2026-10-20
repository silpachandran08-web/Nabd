package com.nabd.hms.department.dto;

import com.nabd.hms.staff.dto.StaffRosterEntry;

import java.util.List;
import java.util.UUID;

/** An allowed transfer destination plus its doctor roster, so the consult-page transfer picker
 * needs one call, not a client-side join across the transfer graph and a separate roster fetch. */
public record TransferTargetResponse(UUID departmentId, String departmentName, List<StaffRosterEntry> doctors) {
}
